package io.github.andredevel.slicer.send

import android.util.Log
import io.github.andredevel.slicer.R
import io.github.andredevel.slicer.send.Http.resolve
import io.github.andredevel.slicer.send.Http.withPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL
import java.net.URLEncoder

/**
 * Moonraker (API do Klipper), como `src/slic3r/Utils/Moonraker.cpp` do Orca: o G-code vai para
 * `POST /server/files/upload` na raiz `gcodes`, com `print=false`, e a impressão começa por
 * `POST /printer/print/start` com o caminho que a resposta do envio devolve. Num .gcode.3mf o
 * G-code é o `Metadata/plate_1.gcode`, que o campo `plateindex` indica (servidores sem suporte
 * o ignoram).
 *
 * A Snapmaker U1 (Klipper e Moonraker da Snapmaker, github.com/Snapmaker/u1-klipper) mapeia
 * filamento do projeto para cabeça: o objeto `print_task_config` diz o filamento de cada cabeça
 * ([slots]) e `SDCARD_PRINT_FILE_WITH_PARAMETERS ... MAP_TABLE` inicia com o mapa, que vale para
 * os `T`/`M104`/`M109` do G-code até o fim da impressão.
 *
 * Nas Creality com CFS (K2, K3) o mapa vai pela interface delas ([CrealitySocket]), como no Creality
 * Print: `boxsInfo` diz o filamento de cada posição das caixas, `colorMatch` liga cada filamento do
 * G-code (T1A, T1B...) a uma posição e `multiColorPrint` inicia o arquivo enviado pelo Moonraker.
 */
object MoonrakerUploader : Uploader {
    override val canStart = true

    /** Porta própria do Moonraker, quando o nginx do Fluidd/Mainsail (porta 80) não repassa a API. */
    private const val TAG = "Moonraker"
    private const val MOONRAKER_PORT = 7125

    private const val METADATA_RETRIES = 5

    private const val CREALITY_CONNECT_MS = 3_000
    private const val CREALITY_READ_MS = 5_000
    /**
     * Espera pelo estado de impressão depois de `multiColorPrint`: a K3 aquece e carrega o CFS
     * antes (2,5 min no teste de 2026-10-29).
     */
    private const val CREALITY_START_MS = 300_000
    private const val CREALITY_POLL_MS = 5_000
    /** `state` da interface da Creality que o Creality Print lê como imprimindo. */
    private val CREALITY_PRINTING = setOf(1, 5)

    /** Campos que o Moonraker da U1 repassa ao iniciar (`metadata_fields` de _fill_metadata). */
    private val START_FIELDS = listOf(
        "line_width", "layer_height", "outer_wall_speed", "nozzle_diameter_list",
        "nozzle_temp", "filament_type", "filament_flow_ratio", "filament_diameter",
        "filament_max_volumetric_speed", "filament_used_g", "filament_used_mm",
        "outer_wall_acceleration", "filament_volume_type", "print_sequence",
        "outer_wall_speed_list", "outer_wall_acceleration_list",
        "process_flow_support",
        "slicer", "slicer_version",
    )

    override suspend fun test(host: PrinterHost): Connection {
        val api = api(host)
        // De novo na porta que respondeu, para medir sem as tentativas de [api].
        val start = System.nanoTime()
        val response = Http.get(api.resolve("/server/info"), headers(host))
        val millis = (System.nanoTime() - start) / 1_000_000
        check(response, host)
        // "v0.9.3-12-gabc1234": a parte do git não interessa aqui.
        val version = jsonOrNull(response.body)?.optJSONObject("result")?.optString("moonraker_version")
            ?.substringBefore('-')?.takeIf { it.isNotEmpty() }
        return Connection(listOfNotNull("Moonraker", version).joinToString(" "), millis)
    }

    override suspend fun upload(
        host: PrinterHost, file: File, name: String, start: Boolean, progress: (Float) -> Unit,
        starting: () -> Unit, mapping: List<Slot?>?,
    ) {
        val api = api(host)
        if (start) checkIdle(api, host)
        val response = Http.postFile(
            api.resolve("/server/files/upload"),
            headers(host),
            mapOf("root" to "gcodes", "print" to "false") + if (name.endsWith(".3mf")) mapOf("plateindex" to "1") else emptyMap(),
            "file",
            file,
            name,
            progress,
        )
        check(response, host)
        if (start) {
            starting()
            // O servidor pode renomear o arquivo (nome repetido): vale o caminho da resposta.
            val path = jsonOrNull(response.body)?.optJSONObject("result")?.optJSONObject("item")?.optString("path")?.takeIf { it.isNotEmpty() } ?: name
            when {
                mapping == null ->
                    check(Http.postJson(api.resolve("/printer/print/start"), headers(host), JSONObject().put("filename", path).toString()), host)
                mapping.any { it?.cfsBox != null } -> crealityStart(api, host, path, mapping)
                else -> {
                    val script = mappedStart(path, metadata(api, host, path), mapping)
                    check(Http.postJson(api.resolve("/printer/gcode/script"), headers(host), JSONObject().put("script", script).toString()), host)
                }
            }
        }
    }

    override suspend fun slots(host: PrinterHost): List<Slot> {
        val api = api(host)
        return u1Slots(api, host) ?: crealitySlots(api)
    }

    /** Cabeças da U1 (`print_task_config`); null num Klipper sem esse objeto. */
    private suspend fun u1Slots(api: URL, host: PrinterHost): List<Slot>? {
        val response = Http.get(api.resolve("/printer/objects/query?print_task_config"), headers(host))
        check(response, host)
        // Klipper sem o objeto (fora da U1) responde sem ele no status.
        val config = jsonOrNull(response.body)?.optJSONObject("result")?.optJSONObject("status")?.optJSONObject("print_task_config")
        val exist = config?.optJSONArray("filament_exist") ?: return null
        val types = config.optJSONArray("filament_type")
        // RRGGBBAA.
        val colors = config.optJSONArray("filament_color_rgba")
        return List(exist.length()) { i ->
            if (!exist.optBoolean(i)) return@List Slot(i, loaded = false)
            Slot(
                i,
                loaded = true,
                type = types?.optString(i)?.takeIf { it.isNotEmpty() && it != "NONE" },
                color = colors?.optString(i)?.takeIf { it.length >= 6 }?.let { "#" + it.take(6).uppercase() },
            )
        }
    }

    /**
     * Posições das caixas CFS ligadas (`materialBoxs` de tipo 0 com `state` 1); vazia sem a
     * interface da Creality. O suporte de carretel externo (tipo 1) fica de fora. Na K3 (vários
     * bicos), `nozzleFilament` diz o bico de cada posição: o firmware dela não troca o bico do
     * `T` do G-code, então a posição de um filamento é a que alimenta o bico dele.
     */
    private suspend fun crealitySlots(api: URL): List<Slot> = withContext(Dispatchers.IO) {
        val (info, nozzles) = try {
            CrealitySocket.open(api.host, CREALITY_CONNECT_MS).use { socket ->
                socket.get("boxsInfo", CREALITY_READ_MS) to socket.get("nozzleFilament", CREALITY_READ_MS)
            }
        } catch (e: IOException) {
            null to null
        }
        val boxes = info?.optJSONObject("boxsInfo")?.optJSONArray("materialBoxs") ?: return@withContext emptyList()
        // "caixa-posição" -> bico.
        val nozzleOf = mutableMapOf<String, Int>()
        val list = nozzles?.optJSONArray("nozzleFilament")
        for (n in 0 until (list?.length() ?: 0)) {
            val nozzle = list!!.getJSONObject(n)
            val sources = nozzle.optJSONArray("sources") ?: continue
            for (i in 0 until sources.length()) {
                val source = sources.getJSONObject(i)
                nozzleOf["${source.optInt("boxId")}-${source.optInt("slotId")}"] = nozzle.optInt("id")
            }
        }
        val slots = mutableListOf<Slot>()
        for (b in 0 until boxes.length()) {
            val box = boxes.getJSONObject(b)
            if (box.optInt("type") != 0 || box.optInt("state") != 1) continue
            val materials = box.optJSONArray("materials") ?: continue
            for (m in 0 until materials.length()) {
                val material = materials.getJSONObject(m)
                val type = material.optString("type").takeIf { it.isNotEmpty() }
                slots += Slot(
                    slots.size,
                    loaded = type != null && material.optInt("state") == 1,
                    type = type,
                    color = crealityColor(material.optString("color")),
                    cfsBox = box.optInt("id"),
                    cfsSlot = material.optInt("id"),
                    nozzle = nozzleOf["${box.optInt("id")}-${material.optInt("id")}"],
                )
            }
        }
        slots
    }

    /**
     * Início com o mapa do CFS, como o Creality Print: `colorMatch` com o caminho do arquivo na
     * impressora (raiz `gcodes` do Moonraker) e uma entrada por filamento usado, depois
     * `multiColorPrint`. A impressão sai pelo caminho da Creality, que não passa pelo
     * `print_stats` do Klipper: o início se confirma quando o estado dela informa `state` 1 ou 5
     * (imprimindo, como o Creality Print lê) com esse arquivo em `printFileName`.
     */
    private suspend fun crealityStart(api: URL, host: PrinterHost, path: String, mapping: List<Slot?>) {
        val gcode = gcodesRoot(api, host).trimEnd('/') + "/" + path
        val list = JSONArray()
        mapping.forEachIndexed { filament, slot ->
            if (slot?.cfsBox == null) return@forEachIndexed
            list.put(
                JSONObject()
                    // T1A..T1D, T2A...: filamento do G-code, de 4 em 4 por caixa.
                    .put("id", "T${filament / 4 + 1}${'A' + filament % 4}")
                    .put("type", slot.type.orEmpty())
                    .put("color", slot.color.orEmpty())
                    .put("boxId", slot.cfsBox)
                    .put("materialId", slot.cfsSlot)
            )
        }
        val started = withContext(Dispatchers.IO) {
            try {
                CrealitySocket.open(api.host, CREALITY_CONNECT_MS).use { socket ->
                    socket.send(set("colorMatch", JSONObject().put("path", gcode).put("list", list)))
                    socket.send(set("multiColorPrint", JSONObject().put("gcode", gcode).put("enableSelfTest", 0)))
                    Log.i(TAG, "colorMatch + multiColorPrint sent for $gcode: $list")
                    // O estado vem em resposta a `reqPrinterPara`, pedido a cada CREALITY_POLL_MS.
                    val deadline = System.currentTimeMillis() + CREALITY_START_MS
                    var printing = false
                    while (!printing && System.currentTimeMillis() < deadline) {
                        socket.send(JSONObject().put("method", "get").put("params", JSONObject().put("reqPrinterPara", 1)))
                        printing = socket.awaitMessage(CREALITY_POLL_MS) { message ->
                            message.optInt("state", -1) in CREALITY_PRINTING && message.optString("printFileName") == gcode
                        } != null
                    }
                    printing
                }
            } catch (e: IOException) {
                throw SendException(R.string.send_error_io, e.message ?: e.toString(), cause = e)
            }
        }
        if (!started) throw SendException(R.string.send_error_start_unconfirmed, host.name)
    }

    private fun set(key: String, value: JSONObject) = JSONObject().put("method", "set").put("params", JSONObject().put(key, value))

    /** Pasta da raiz `gcodes` na impressora (`/server/files/roots`). */
    private suspend fun gcodesRoot(api: URL, host: PrinterHost): String {
        val response = Http.get(api.resolve("/server/files/roots"), headers(host))
        check(response, host)
        val roots = jsonOrNull(response.body)?.optJSONArray("result")
        for (i in 0 until (roots?.length() ?: 0)) {
            val root = roots!!.getJSONObject(i)
            if (root.optString("name") == "gcodes") return root.optString("path")
        }
        throw SendException(R.string.send_error_http, response.code, host.address)
    }

    /** A Creality informa `#0RRGGBB`; vazio sem filamento. */
    private fun crealityColor(color: String): String? = when {
        color.length == 8 && color[0] == '#' -> "#" + color.substring(2).uppercase()
        color.length == 7 && color[0] == '#' -> color.uppercase()
        else -> null
    }

    /**
     * Metadados que o Moonraker extraiu de [path]. Ele os lê ao receber o arquivo; se ainda não
     * estiverem prontos (404), tenta de novo por alguns segundos.
     */
    private suspend fun metadata(api: URL, host: PrinterHost, path: String): JSONObject {
        val url = api.resolve("/server/files/metadata?filename=" + URLEncoder.encode(path, "UTF-8"))
        var response = Http.get(url, headers(host))
        repeat(METADATA_RETRIES) {
            if (response.code != 404) return@repeat
            delay(1000)
            response = Http.get(url, headers(host))
        }
        check(response, host)
        return jsonOrNull(response.body)?.optJSONObject("result") ?: throw SendException(R.string.send_error_http, response.code, host.address)
    }

    /**
     * O comando que o Moonraker da U1 monta para iniciar com parâmetros
     * (klippy_apis.start_print_advanced e _fill_metadata): os campos de [metadata] no formato
     * `str()` do Python, que o Klipper lê com `ast.literal_eval`, e o MAP_TABLE
     * `[[filamento, cabeça], ...]` dos filamentos usados (os outros ficam no mapa padrão).
     */
    private fun mappedStart(path: String, metadata: JSONObject, mapping: List<Slot?>) = buildString {
        append("SDCARD_PRINT_FILE_WITH_PARAMETERS FILENAME=\"").append(path.replace("\"", "\\\"")).append('"')
        for (field in START_FIELDS) {
            var value = metadata.opt(if (field == "filament_used_g") "filament_weight" else field)
            if (value == null || value == JSONObject.NULL) continue
            // "PLA;PETG" vira lista, com NONE nos vazios.
            if (field == "filament_type" && value is String) value = JSONArray(value.split(';').map { it.ifEmpty { "NONE" } })
            append(' ').append(field.uppercase()).append("=\"").append(python(value)).append('"')
        }
        val pairs = mapping.withIndex().filter { it.value != null }.joinToString(",") { (filament, slot) -> "[$filament,${slot!!.index}]" }
        append(" MAP_TABLE=\"[").append(pairs).append("]\"")
    }

    /** [value] como o `str()` do Python o escreveria; texto solto vai como está. */
    private fun python(value: Any, nested: Boolean = false): String = when (value) {
        is JSONArray -> (0 until value.length()).joinToString(", ", "[", "]") { python(value.get(it), nested = true) }
        is String -> if (nested) "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'" else value
        is Boolean -> if (value) "True" else "False"
        JSONObject.NULL -> "None"
        else -> value.toString()
    }

    override suspend fun status(host: PrinterHost): PrinterStatus = status(api(host), host)

    /** Falha se o Klipper estiver imprimindo ou pausado (print_stats.state). */
    private suspend fun checkIdle(api: URL, host: PrinterHost) {
        val status = status(api, host)
        if (status.busy) throw SendException(R.string.send_error_busy, host.name, status.raw.orEmpty())
    }

    /** `print_stats` (estado e arquivo) e `virtual_sdcard.progress` (posição no arquivo). */
    private suspend fun status(api: URL, host: PrinterHost): PrinterStatus {
        val response = Http.get(api.resolve("/printer/objects/query?print_stats&virtual_sdcard"), headers(host))
        check(response, host)
        val objects = jsonOrNull(response.body)?.optJSONObject("result")?.optJSONObject("status")
        val stats = objects?.optJSONObject("print_stats")
        val raw = stats?.optString("state").orEmpty()
        val state = when (raw) {
            "printing" -> PrinterStatus.State.PRINTING
            "paused" -> PrinterStatus.State.PAUSED
            "error" -> PrinterStatus.State.ERROR
            // standby, complete, cancelled.
            else -> PrinterStatus.State.IDLE
        }
        val active = state == PrinterStatus.State.PRINTING || state == PrinterStatus.State.PAUSED
        return PrinterStatus(
            state,
            progress = objects?.optJSONObject("virtual_sdcard")?.optDouble("progress")?.takeIf { active && !it.isNaN() }?.toFloat(),
            file = stats?.optString("filename")?.takeIf { active && it.isNotEmpty() },
            raw = raw,
        )
    }

    private fun jsonOrNull(body: String): JSONObject? = try {
        JSONObject(body)
    } catch (e: JSONException) {
        null
    }

    /**
     * Endereço que responde `GET /server/info`. Sem porta no endereço, tenta a 80 e depois a
     * [MOONRAKER_PORT].
     */
    private suspend fun api(host: PrinterHost): URL {
        val base = Http.baseUrl(host.address)
        val candidates = if (base.port == -1) listOf(base, base.withPort(MOONRAKER_PORT)) else listOf(base)
        var failure: SendException? = null
        for (url in candidates) {
            try {
                val response = Http.get(url.resolve("/server/info"), headers(host))
                check(response, host)
                if (isMoonraker(response.body)) return url
                failure = SendException(R.string.send_error_not_moonraker, url.authority)
            } catch (e: SendException) {
                // A falha de autenticação vale para qualquer porta; as outras, tenta a próxima.
                if (e.text == R.string.send_error_auth) throw e
                // Uma resposta HTTP diz mais que a porta seguinte fechada.
                if (failure == null || failure.text == R.string.send_error_unreachable) failure = e
            }
        }
        throw failure!!
    }

    private fun headers(host: PrinterHost) = host.secret?.takeIf { it.isNotBlank() }?.let { mapOf("X-Api-Key" to it.trim()) }.orEmpty()

    private fun check(response: Http.Response, host: PrinterHost) {
        when (response.code) {
            in 200..299 -> {}
            401, 403 -> throw SendException(R.string.send_error_auth, response.code)
            else -> throw SendException(R.string.send_error_http, response.code, message(response.body) ?: host.address)
        }
    }

    private fun isMoonraker(body: String) = try {
        JSONObject(body).optJSONObject("result")?.has("klippy_state") == true
    } catch (e: JSONException) {
        false
    }

    /** `{"error": {"message": ...}}` das respostas de erro do Moonraker. */
    private fun message(body: String): String? = try {
        JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() }
    } catch (e: JSONException) {
        null
    }
}
