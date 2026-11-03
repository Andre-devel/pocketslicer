package io.github.andredevel.slicer.send

import android.util.Log
import io.github.andredevel.slicer.R
import io.github.andredevel.slicer.send.Http.resolve
import io.github.andredevel.slicer.send.Http.withPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

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
 * Nas Creality com CFS (K2, K3) o mapa vai pela interface delas ([WebSocketClient]), como no Creality
 * Print: `boxsInfo` diz o filamento de cada posição das caixas, `colorMatch` liga cada filamento do
 * G-code (T1A, T1B...) a uma posição e `multiColorPrint` inicia o arquivo enviado pelo Moonraker.
 */
object MoonrakerUploader : Uploader {
    override val canStart = true
    override val hasFiles = true

    /** Porta própria do Moonraker, quando o nginx do Fluidd/Mainsail (porta 80) não repassa a API. */
    private const val TAG = "Moonraker"
    private const val MOONRAKER_PORT = 7125

    private const val METADATA_RETRIES = 5
    /** Impressões que a aba Arquivos mostra do histórico. */
    private const val HISTORY_JOBS = 50

    /** Bicos que [status] lê (a U1 tem quatro cabeças). */
    private val EXTRUDERS = listOf("extruder", "extruder1", "extruder2", "extruder3")
    /** Sensor da câmara: da Creality (K2), da QIDI (aquecedor) e um nome comum. */
    private val CHAMBERS = listOf("temperature_sensor chamber_temp", "heater_generic chamber", "temperature_sensor chamber")
    /** Sensor de temperatura e umidade de cada Box da QIDI (Q2). */
    private val QIDI_BOXES = (1..4).map { "aht20_f heater_box$it" }
    /** Aquecedor da câmara da K3 (módulo da Creality), com o alvo do `M141`. */
    private const val CREALITY_CHAMBER = "heater_chamber chamber"
    /** Modos de impressão da Creality (`SET_PRINT_MODE`): objeto e seção do configfile. */
    private const val PRINT_MODE = "print_mode"
    private const val EXCLUDE_OBJECT = "exclude_object"
    private const val SPEED_FACTOR = "_speed_factor"
    private val STATUS_OBJECTS = listOf("webhooks", "print_stats", "virtual_sdcard", "heater_bed", "box", CREALITY_CHAMBER) + EXTRUDERS + CHAMBERS + QIDI_BOXES

    /** Objetos de luz do Klipper, todos ligados por `SET_LED`. */
    private val LED_PREFIXES = listOf("led ", "neopixel ", "dotstar ", "pca9533 ", "pca9632 ")
    /** Velocidade do jog em mm/min: XY e Z (a K3 aceita no máximo 10 mm/s em Z). */
    private const val JOG_XY_FEED = 6_000
    private const val JOG_Z_FEED = 600
    /** Consulta do objeto `box` enquanto o CFS carrega ou descarrega. */
    private const val FILAMENT_POLL_MS = 2_000L
    /**
     * Resposta do comando de carga, se o firmware só responder no fim (na K3 a carga e a
     * descarga levaram ~100 s em 2026-11-02); acima do limite da fila no ViewModel.
     */
    private const val FILAMENT_RESPONSE_MS = 15 * 60_000
    /** O comando já respondeu e a posição não começou a carregar: o firmware não fez nada. */
    private const val FILAMENT_START_MS = 15_000L

    /** Uma calibração leva minutos (a malha da K3 aquece a mesa antes); o limite é de quem chama. */
    private const val MAINTENANCE_RESPONSE_MS = 60 * 60_000

    /** Um comando do console pode demorar (G28, aquecer com M109...). */
    private const val CONSOLE_RESPONSE_MS = 15 * 60_000

    /** Quanto o Klipper pode levar para voltar depois do `SAVE_CONFIG`. */
    private const val RESTART_WAIT_MS = 120_000L
    private const val RESTART_POLL_MS = 2_000L

    private val CREALITY_MAINTENANCE = linkedMapOf(
        Maintenance.BED_LEVELING to "G29",
        Maintenance.Z_AXIS to "Z_AXIS_CALIBRATION",
        Maintenance.INPUT_SHAPING to "INPUTSHAPER",
        Maintenance.NOZZLE_PID to "NOZZLE_PID",
        Maintenance.BED_PID to "BEDPID",
        Maintenance.NOZZLE_CLEAN to "NOZZLE_CLEAR",
    )

    /** Sinalização WebRTC da câmera da Creality K2 (`/call/webrtc_local`). */
    private const val CREALITY_WEBRTC_PORT = 8000

    /**
     * Módulo dos códigos de erro da Creality pelo número (tabela da K2 Plus no fórum da Creality);
     * o WebSocket só manda o número. AC = detecção por IA.
     */
    private val CREALITY_CODE_PREFIX = mapOf(101 to "AC", 103 to "AC", 104 to "AC", 500 to "AC", 503 to "AC")

    private const val CREALITY_CONNECT_MS = 3_000
    private const val CREALITY_READ_MS = 5_000
    /** Tempo com o WebSocket aberto depois de um comando de [control]. */
    private const val CREALITY_COMMAND_MS = 3_000
    /** Espera pelo `printFileName` que a Creality manda ao aceitar o início (veio em ~3 s na K2). */
    private const val CREALITY_ACCEPT_MS = 15_000
    /**
     * Espera pelo estado de impressão depois de `multiColorPrint`: a K3 aquece e carrega o CFS
     * antes (2,5 min no teste de 2026-10-29).
     */
    private const val CREALITY_START_MS = 300_000
    private const val CREALITY_POLL_MS = 5_000

    private const val LIVE_CONNECT_MS = 10_000
    /** Cada espera por mensagem do WebSocket do Moonraker, para ver o cancelamento e o silêncio. */
    private const val LIVE_READ_MS = 1_000
    /** Silêncio do WebSocket do Moonraker que derruba a conexão; na metade, um ping. */
    private const val LIVE_SILENCE_MS = 20_000L

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

    override suspend fun identity(host: PrinterHost): Set<String> = identityAt(api(host), headers(host))

    /**
     * MACs (minúsculos) das interfaces em `/machine/system_info`, sem os zerados; sem nenhum, o
     * hostname de `/printer/info` como `hostname:<nome>`. Vazio se a impressora não informa.
     * [api]: a URL do Moonraker; usada também pelo [PrinterScanner].
     */
    internal suspend fun identityAt(api: URL, headers: Map<String, String> = emptyMap()): Set<String> {
        val system = jsonOrNull(Http.get(api.resolve("/machine/system_info"), headers).body)
            ?.optJSONObject("result")?.optJSONObject("system_info")
        val network = system?.optJSONObject("network")
        val macs = network?.keys()?.asSequence().orEmpty()
            .mapNotNull { network!!.optJSONObject(it)?.optString("mac_address")?.lowercase() }
            .filter { mac -> mac.isNotBlank() && mac.any { it in '1'..'9' || it in 'a'..'f' } }
            .toSet()
        if (macs.isNotEmpty()) return macs
        val hostname = jsonOrNull(Http.get(api.resolve("/printer/info"), headers).body)
            ?.optJSONObject("result")?.optString("hostname")?.takeIf { it.isNotBlank() }
        return setOfNotNull(hostname?.let { "hostname:$it" })
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
            start(api, host, path, mapping)
        }
    }

    override suspend fun startFile(host: PrinterHost, path: String, mapping: List<Slot?>?) {
        val api = api(host)
        checkIdle(api, host)
        start(api, host, path, mapping)
    }

    /** Inicia [path] da raiz `gcodes` com o mapa de filamentos de cada impressora. */
    private suspend fun start(api: URL, host: PrinterHost, path: String, mapping: List<Slot?>?) {
        when {
            mapping == null ->
                check(Http.postJson(api.resolve("/printer/print/start"), headers(host), JSONObject().put("filename", path).toString()), host)
            mapping.any { it?.cfsBox != null } -> crealityStart(api, host, path, mapping)
            mapping.any { it?.qidiSlot != null } -> {
                check(Http.postJson(api.resolve("/printer/gcode/script"), headers(host), JSONObject().put("script", qidiMapping(mapping)).toString()), host)
                check(Http.postJson(api.resolve("/printer/print/start"), headers(host), JSONObject().put("filename", path).toString()), host)
            }
            else -> {
                val script = mappedStart(path, metadata(api, host, path), mapping)
                check(Http.postJson(api.resolve("/printer/gcode/script"), headers(host), JSONObject().put("script", script).toString()), host)
            }
        }
    }

    /** `/server/files/list` da raiz `gcodes`, sem pastas ocultas (miniaturas `.thumbs`). */
    override suspend fun files(host: PrinterHost): List<PrinterFile> {
        val api = api(host)
        val response = Http.get(api.resolve("/server/files/list?root=gcodes"), headers(host))
        check(response, host)
        val list = jsonOrNull(response.body)?.optJSONArray("result") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val item = list.optJSONObject(i) ?: return@mapNotNull null
            val path = item.optString("path").takeIf { p -> p.isNotEmpty() && p.split('/').none { it.startsWith('.') } } ?: return@mapNotNull null
            PrinterFile(path, (item.optDouble("modified", 0.0) * 1000).toLong(), item.optLong("size"))
        }.sortedByDescending { it.modified }
    }

    /**
     * Começo e fim do arquivo por `Range` ([GcodeInfo.HEAD_BYTES], [GcodeInfo.TAIL_BYTES]), guardado
     * por arquivo e data: os metadados do Moonraker da K3 não têm miniatura nem tempo.
     */
    override suspend fun fileInfo(host: PrinterHost, file: PrinterFile): GcodeInfo {
        val key = "${host.id}/${file.path}/${file.modified}"
        fileInfos[key]?.let { return it }
        val api = api(host)
        val url = fileUrl(api, file.path)
        val head = Http.getRange(url, headers(host), "0-${GcodeInfo.HEAD_BYTES - 1}", GcodeInfo.HEAD_BYTES)
        check(head, host)
        val tail = if (file.size > GcodeInfo.HEAD_BYTES) {
            Http.getRange(url, headers(host), "-${GcodeInfo.TAIL_BYTES}", GcodeInfo.TAIL_BYTES).also { check(it, host) }.body
        } else head.body
        return GcodeInfo.parse(head.body, tail).also { fileInfos[key] = it }
    }

    private val fileInfos = ConcurrentHashMap<String, GcodeInfo>()

    override suspend fun deleteFile(host: PrinterHost, file: PrinterFile) {
        val api = api(host)
        check(Http.delete(fileUrl(api, file.path), headers(host)), host)
        fileInfos.remove("${host.id}/${file.path}/${file.modified}")
    }

    /** `/server/files/gcodes/<caminho>`, cada parte codificada (nomes com espaço, acento, `#`). */
    private fun fileUrl(api: URL, path: String) =
        api.resolve("/server/files/gcodes/" + path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") })

    /**
     * `/server/history/list` (as [HISTORY_JOBS] últimas) e `/server/history/totals`; null num
     * Moonraker sem o componente `history` (404).
     */
    override suspend fun history(host: PrinterHost): PrintHistory? {
        val api = api(host)
        val list = Http.get(api.resolve("/server/history/list?limit=$HISTORY_JOBS&order=desc"), headers(host))
        if (list.code == 404) return null
        check(list, host)
        val totals = Http.get(api.resolve("/server/history/totals"), headers(host))
        check(totals, host)
        val jobs = jsonOrNull(list.body)?.optJSONObject("result")?.optJSONArray("jobs") ?: JSONArray()
        val sum = jsonOrNull(totals.body)?.optJSONObject("result")?.optJSONObject("job_totals")
        fun ms(json: JSONObject, key: String) = json.optDouble(key).takeIf { !it.isNaN() && it > 0 }?.let { (it * 1000).toLong() }
        return PrintHistory(
            (0 until jobs.length()).mapNotNull { i ->
                val job = jobs.optJSONObject(i) ?: return@mapNotNull null
                PrintJob(
                    job.optString("job_id"), job.optString("filename"),
                    when (job.optString("status")) {
                        "completed" -> PrintJob.State.COMPLETED
                        "cancelled" -> PrintJob.State.CANCELLED
                        "error" -> PrintJob.State.ERROR
                        "in_progress" -> PrintJob.State.IN_PROGRESS
                        // klippy_shutdown, klippy_disconnect, interrupted, server_exit
                        else -> PrintJob.State.INTERRUPTED
                    },
                    ms(job, "start_time") ?: 0L, ms(job, "end_time"),
                    job.optDouble("print_duration", 0.0).toLong(), job.optDouble("filament_used", 0.0), job.optBoolean("exists", false),
                )
            },
            sum?.optInt("total_jobs") ?: 0, sum?.optDouble("total_print_time", 0.0)?.toLong() ?: 0L,
            sum?.optDouble("total_filament_used", 0.0) ?: 0.0, sum?.optDouble("longest_print", 0.0)?.toLong() ?: 0L,
        )
    }

    override suspend fun slots(host: PrinterHost): List<Slot> {
        val api = api(host)
        return u1Slots(api, host) ?: qidiSlots(api, host) ?: crealitySlots(api)
    }

    /**
     * Posições da Box da QIDI (`multi_color_controller`, 4 por caixa ligada) e o carretel externo
     * por último; null num Klipper sem esse objeto. Como o QIDI Studio lê: uma posição tem
     * filamento com `boxN_box_inserted_P`; o externo conta sempre como carregado.
     */
    private suspend fun qidiSlots(api: URL, host: PrinterHost): List<Slot>? {
        val response = Http.get(api.resolve("/printer/objects/query?multi_color_controller"), headers(host))
        check(response, host)
        val controller = jsonOrNull(response.body)?.optJSONObject("result")?.optJSONObject("status")?.optJSONObject("multi_color_controller") ?: return null
        val box = controller.optJSONObject("box") ?: return null
        val slots = mutableListOf<Slot>()
        for (b in 0 until box.optInt("box_count")) {
            for (p in 0 until 4) {
                val info = "box${b}_filament_info_${p}_"
                slots += Slot(
                    slots.size,
                    loaded = box.optInt("box${b}_box_inserted_$p") != 0,
                    type = box.optString(info + "filament_type").takeIf { it.isNotEmpty() },
                    color = box.optJSONArray(info + "color_rgb_str")?.optString(0)?.let(::crealityColor),
                    qidiSlot = b * 4 + p,
                )
            }
        }
        val external = controller.optJSONObject("extra")?.optJSONObject("filament_info")
        slots += Slot(
            slots.size,
            loaded = true,
            type = external?.optString("filament_type")?.takeIf { it.isNotEmpty() },
            color = external?.optJSONArray("color_rgb_str")?.optString(0)?.let(::crealityColor),
            qidiSlot = QIDI_EXTERNAL,
        )
        return slots
    }

    /**
     * Mapa da Box da QIDI antes do início, como o QIDI Studio no modo local: `enable_box` liga a
     * Box (desligada quando todos os filamentos vêm do carretel externo) e `value_tN` diz a
     * posição do filamento N (`'slotP'`), lida pelo `T_MANAGER` das macros T0..T15.
     */
    private fun qidiMapping(mapping: List<Slot?>) = buildString {
        val box = mapping.any { it?.qidiSlot != null && it.qidiSlot != QIDI_EXTERNAL }
        append("SAVE_VARIABLE VARIABLE=enable_box VALUE=").append(if (box) 1 else 0)
        if (box) mapping.forEachIndexed { filament, slot ->
            if (slot?.qidiSlot != null) append("\nSAVE_VARIABLE VARIABLE=value_t").append(filament).append(" VALUE=\"'slot").append(slot.qidiSlot).append("'\"")
        }
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
            WebSocketClient.creality(api.host, CREALITY_CONNECT_MS).use { socket ->
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
     * `multiColorPrint`. O início se confirma pelo `print_stats` do Klipper com esse arquivo
     * imprimindo (na K3 em 2026-10-29 ele passa a `printing` depois de aquecer e carregar o CFS);
     * o estado do WebSocket não serve, porque depois da primeira resposta a K3 só manda os campos
     * que mudaram.
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
        withContext(Dispatchers.IO) {
            try {
                WebSocketClient.creality(api.host, CREALITY_CONNECT_MS).use { socket ->
                    socket.send(set("colorMatch", JSONObject().put("path", gcode).put("list", list)))
                    socket.send(set("multiColorPrint", JSONObject().put("gcode", gcode).put("enableSelfTest", 0)))
                    // A K2 descarta o pedido se a conexão fecha logo depois do envio (2026-10-30):
                    // fica aberta até ela anunciar o arquivo em impressão.
                    val accepted = socket.awaitMessage(CREALITY_ACCEPT_MS) { it.optString("printFileName") == gcode }
                    Log.i(TAG, "colorMatch + multiColorPrint sent for $gcode (accepted=${accepted != null}): $list")
                }
            } catch (e: IOException) {
                throw SendException(R.string.send_error_io, e.message ?: e.toString(), cause = e)
            }
        }
        val deadline = System.currentTimeMillis() + CREALITY_START_MS
        var started = false
        while (!started && System.currentTimeMillis() < deadline) {
            delay(CREALITY_POLL_MS.toLong())
            started = status(api, host).let { it.busy && it.file == path }
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

    override fun supports(command: PrinterCommand) = true

    /**
     * Parada de emergência e reinício pela API do Moonraker (`/printer/emergency_stop`,
     * `/printer/firmware_restart`). Pausar, continuar e parar: na K2/K3 (objeto `box` do CFS)
     * pela tela da Creality, como o app dela: `set` `pause` 1/0 e `stop` 1 (de
     * github.com/3dg1luk43/ha_creality_ws); só ela continua a pausa da detecção por IA, que vem
     * antes de o Klipper começar o arquivo. Nas outras, pela API do Moonraker.
     */
    override suspend fun command(host: PrinterHost, command: PrinterCommand) {
        val api = api(host)
        val action = when (command) {
            PrinterCommand.EmergencyStop -> return check(Http.postJson(api.resolve("/printer/emergency_stop"), headers(host), "{}"), host)
            PrinterCommand.FirmwareRestart -> {
                capabilities.remove(api.toString())
                return check(Http.postJson(api.resolve("/printer/firmware_restart"), headers(host), "{}"), host)
            }
            PrinterCommand.Pause, PrinterCommand.Resume, PrinterCommand.Stop -> command
            else -> return gcode(api, host, script(command, capabilities(api, host)))
        }
        val response = Http.get(api.resolve("/printer/objects/query?box"), headers(host))
        check(response, host)
        val creality = jsonOrNull(response.body)?.optJSONObject("result")?.optJSONObject("status")?.has("box") == true
        if (creality) {
            val params = when (action) {
                PrinterCommand.Pause -> JSONObject().put("pause", 1)
                PrinterCommand.Resume -> JSONObject().put("pause", 0)
                else -> JSONObject().put("stop", 1)
            }
            withContext(Dispatchers.IO) {
                try {
                    WebSocketClient.creality(api.host, CREALITY_CONNECT_MS).use { socket ->
                        socket.send(JSONObject().put("method", "set").put("params", params))
                        // A K2 ignora o que chega se o WebSocket fecha logo depois (ver crealityStart).
                        socket.awaitMessage(CREALITY_COMMAND_MS) { false }
                    }
                } catch (e: IOException) {
                    throw SendException(R.string.send_error_unreachable, "${api.host}:${WebSocketClient.CREALITY_PORT}", cause = e)
                }
            }
        } else {
            val path = when (action) {
                PrinterCommand.Pause -> "/printer/print/pause"
                PrinterCommand.Resume -> "/printer/print/resume"
                else -> "/printer/print/cancel"
            }
            check(Http.postJson(api.resolve(path), headers(host), "{}"), host)
        }
    }

    /** G-code de um comando da aba Controle. */
    private fun script(command: PrinterCommand, capabilities: Capabilities): String = when (command) {
        is PrinterCommand.Home -> "G28" + command.axes.uppercase().map { " $it" }.joinToString("")
        is PrinterCommand.Jog -> {
            val feed = if (command.axis.uppercaseChar() == 'Z') JOG_Z_FEED else JOG_XY_FEED
            // Relativo só neste movimento; o modo de antes volta.
            "SAVE_GCODE_STATE NAME=app_jog\nG91\nG1 ${command.axis.uppercaseChar()}${number(command.distance)} F$feed\nRESTORE_GCODE_STATE NAME=app_jog"
        }
        PrinterCommand.MotorsOff -> "M84"
        is PrinterCommand.SetTemperature -> {
            val key = command.heater.source to command.heater.index
            if (key !in capabilities.heaters) throw IllegalArgumentException("no heater $key")
            val target = "%.0f".format(Locale.ROOT, command.target.coerceAtLeast(0f))
            capabilities.heaters[key]?.let { "SET_HEATER_TEMPERATURE HEATER=$it TARGET=$target" } ?: "M141 S$target"
        }
        is PrinterCommand.SetFan -> {
            val speed = command.speed.coerceIn(0f, 1f)
            val pwm = (speed * 255).roundToInt()
            val creality = capabilities.crealityFans[command.fan]
            when {
                command.fan == "fan" -> "M106 S$pwm"
                creality != null -> "M106 P${creality.number} S$pwm"
                command.fan.startsWith("fan_generic ") -> "SET_FAN_SPEED FAN=${command.fan.substringAfter(' ')} SPEED=${number(speed)}"
                else -> throw IllegalArgumentException("no fan ${command.fan}")
            }
        }
        is PrinterCommand.SetLight -> {
            val value = if (command.on) 1 else 0
            "SET_LED LED=${command.light.substringAfter(' ')} RED=$value GREEN=$value BLUE=$value WHITE=$value"
        }
        // O Klipper recusa 0 (above=0); a tela da K3 manda os mesmos M220/M221.
        is PrinterCommand.SetSpeed -> "M220 S${command.percent.coerceAtLeast(1)}"
        is PrinterCommand.SetFlow -> "M221 S${command.percent.coerceAtLeast(1)}"
        is PrinterCommand.SetPrintMode -> {
            if (command.mode !in capabilities.controls.adjustments.printModes) throw IllegalArgumentException("no print mode ${command.mode}")
            "SET_PRINT_MODE MODE=${command.mode}"
        }
        is PrinterCommand.AdjustZOffset -> "SET_GCODE_OFFSET Z_ADJUST=${number(command.delta)} MOVE=1"
        is PrinterCommand.PauseAtLayer -> command.layer?.let { "SET_PAUSE_AT_LAYER ENABLE=1 LAYER=${it.coerceAtLeast(1)}" } ?: "SET_PAUSE_AT_LAYER ENABLE=0"
        // Como a tela da K3 manda (klippy.log, 2026-11-02).
        is PrinterCommand.RefreshSlot -> {
            if (!capabilities.controls.filament.refresh) throw IllegalArgumentException("no RFID refresh")
            val box = command.slot.cfsBox ?: throw IllegalArgumentException("not a CFS slot: ${command.slot}")
            "BOX_INFO_REFRESH ADDR=$box NUM=${'A' + command.slot.cfsSlot}"
        }
        is PrinterCommand.ExcludeObject -> {
            // O nome vai sem aspas no Klipper; espaço ou quebra de linha mudariam o comando.
            if (!capabilities.controls.excludeObject || command.name.any { it.isWhitespace() }) throw IllegalArgumentException("can't exclude ${command.name}")
            "EXCLUDE_OBJECT NAME=${command.name}"
        }
        else -> throw IllegalArgumentException("not a G-code command: $command")
    }

    private fun number(value: Float) = "%.3f".format(Locale.ROOT, value).trimEnd('0').trimEnd('.')

    /**
     * Manda [script] por `/printer/gcode/script`, que só responde quando o Klipper o executou
     * (esperando até [timeoutMs]); o erro dele vira a mensagem.
     */
    private suspend fun gcode(api: URL, host: PrinterHost, script: String, timeoutMs: Int? = null) {
        Log.i(TAG, "gcode ${host.name}: ${script.replace('\n', ';')}")
        val url = api.resolve("/printer/gcode/script")
        val body = JSONObject().put("script", script).toString()
        check(if (timeoutMs != null) Http.postJson(url, headers(host), body, timeoutMs) else Http.postJson(url, headers(host), body), host)
    }

    /**
     * Carga e descarga do CFS da Creality pelos comandos que a tela da K3 manda (klippy.log,
     * 2026-11-02): `BOX_LOAD_MATERIAL_STEP` e `BOX_QUIT_MATERIAL_STEP` com `TNN=T<caixa><A-D>`.
     * O fim vem do objeto `box`: `slot_status` da posição passa de "running" a "done" (com
     * `status` 100; 2..4 carregando, 11..14 descarregando) e `err_status` diferente de 0 é erro.
     * Como não se sabe se o comando responde no começo ou no fim, ele vai em paralelo com a
     * consulta; se respondeu e a posição não começou em [FILAMENT_START_MS], acabou sem mudança.
     */
    override suspend fun changeFilament(host: PrinterHost, slot: Slot, load: Boolean) {
        val api = api(host)
        if (!capabilities(api, host).controls.filament.loadUnload) throw IllegalArgumentException("no CFS load/unload")
        val box = slot.cfsBox ?: throw IllegalArgumentException("not a CFS slot: $slot")
        val name = "T$box${'A' + slot.cfsSlot}"
        checkIdle(api, host)
        boxError(boxState(api, host))?.let { throw SendException(R.string.filament_error_box, it) }
        val script = if (load) "BOX_LOAD_MATERIAL_STEP TNN=$name" else "BOX_QUIT_MATERIAL_STEP TNN=$name"
        coroutineScope {
            val sent = async { gcode(api, host, script, FILAMENT_RESPONSE_MS) }
            var started = false
            var answeredAt: Long? = null
            while (true) {
                delay(FILAMENT_POLL_MS)
                if (sent.isCompleted) {
                    sent.await()
                    if (answeredAt == null) answeredAt = System.currentTimeMillis()
                }
                val state = boxState(api, host)
                boxError(state)?.let { throw SendException(R.string.filament_error_box, it) }
                when (state.optJSONObject("slot_status")?.optString(name)) {
                    "running" -> started = true
                    "done" -> if (started) break
                }
                if (!started && answeredAt != null && System.currentTimeMillis() - answeredAt >= FILAMENT_START_MS) break
            }
            sent.await()
        }
    }

    /**
     * As macros da Creality (K3, configfile de 2026-11-02) para cada [Maintenance], sem
     * parâmetros: `G29` (aquece, home, `Z_TILT_ADJUST`, malha e `CXSAVE_CONFIG`, que grava sem
     * reiniciar), `Z_AXIS_CALIBRATION` (o mesmo sem a malha), `INPUTSHAPER` (`SHAPER_CALIBRATE`
     * nos três eixos e `CXSAVE_CONFIG`), `NOZZLE_PID` e `BEDPID` (`PID_CALIBRATE` a 230 e 100 °C e
     * `SAVE_CONFIG`, que reinicia o Klipper) e `NOZZLE_CLEAR` (o que a tela da K3 manda, visto no
     * `klippy.log`). O `/printer/gcode/script` só responde no fim.
     */
    override suspend fun runMaintenance(host: PrinterHost, task: Maintenance) {
        val api = api(host)
        if (task !in capabilities(api, host).controls.maintenance) throw IllegalArgumentException("no $task")
        checkIdle(api, host)
        try {
            gcode(api, host, CREALITY_MAINTENANCE.getValue(task), MAINTENANCE_RESPONSE_MS)
        } catch (e: SendException) {
            // O SAVE_CONFIG reinicia o Klipper antes de responder: o Moonraker devolve
            // "Klippy Disconnected" (503) ou a conexão cai. Terminou se ele voltar pronto.
            val restarted = e.text == R.string.send_error_io || e.text == R.string.send_error_http && e.args.firstOrNull() == 503
            if (!task.saves || !restarted || !awaitKlippyReady(api, host)) throw e
        }
    }

    override val hasConsole = true

    override suspend fun console(host: PrinterHost, count: Int): List<ConsoleLine> {
        val list = result(api(host), host, "/server/gcode_store?count=$count").optJSONArray("gcode_store") ?: return emptyList()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }.map {
            ConsoleLine(it.optString("message"), (it.optDouble("time", 0.0) * 1000).toLong(), it.optString("type") == "command")
        }
    }

    override suspend fun sendGcode(host: PrinterHost, script: String) = gcode(api(host), host, script, CONSOLE_RESPONSE_MS)

    override suspend fun gcodeCommands(host: PrinterHost): Map<String, String> {
        val help = result(api(host), host, "/printer/gcode/help")
        return help.keys().asSequence().associate { it.uppercase() to help.optString(it) }
    }

    /** Espera o Klipper voltar ao estado "ready" (até [RESTART_WAIT_MS]); false se não voltou. */
    private suspend fun awaitKlippyReady(api: URL, host: PrinterHost): Boolean {
        val end = System.currentTimeMillis() + RESTART_WAIT_MS
        while (System.currentTimeMillis() < end) {
            delay(RESTART_POLL_MS)
            val state = try {
                jsonOrNull(Http.get(api.resolve("/printer/info"), headers(host)).body)?.optJSONObject("result")?.optString("state")
            } catch (e: SendException) {
                null
            }
            when (state) {
                "ready" -> {
                    capabilities.remove(api.toString())
                    return true
                }
                "shutdown", "error" -> return false
            }
        }
        return false
    }

    private suspend fun boxState(api: URL, host: PrinterHost): JSONObject =
        result(api, host, "/printer/objects/query?box").optJSONObject("status")?.optJSONObject("box") ?: JSONObject()

    /** O `err_status` do CFS, null sem erro. */
    private fun boxError(box: JSONObject) = box.optInt("err_status", 0).takeIf { it != 0 }

    override suspend fun controls(host: PrinterHost): PrinterControls = capabilities(api(host), host).controls

    /**
     * O que cada impressora tem para a aba Controle, por endereço da API; lido na primeira vez
     * e de novo depois do reinício do firmware.
     */
    private val capabilities = ConcurrentHashMap<String, Capabilities>()

    /**
     * [heaters]: o nome do aquecedor no `SET_HEATER_TEMPERATURE` de cada aquecedor (fonte, índice),
     * null para o `M141` (câmara da K3). [crealityFans]: as ventoinhas da Creality pelo [Fan.id].
     * [objects]: o que o estado ao vivo assina a mais para os valores da aba e dos ajustes;
     * [statsMacro]: o nome da macro `SET_PRINT_STATS_INFO`, que guarda a pausa na camada.
     */
    private class Capabilities(
        val controls: PrinterControls,
        val heaters: Map<Pair<Temperature.Source, Int>, String?>,
        val crealityFans: Map<String, CrealityFan>,
        val objects: List<String>,
        val statsMacro: String? = null,
    )

    /**
     * Ventoinha `output_pin fan<number>` da Creality: o `M106 P<number>` dela (macro da K3) leva
     * 0..255 para [min]..255 antes de mudar o pino.
     */
    private class CrealityFan(val number: Int, val min: Int)

    /**
     * Lê `/printer/objects/list`, `/printer/gcode/help` e o `configfile`: os aquecedores do
     * Klipper (`heaters.available_heaters`, com o `max_temp`) e a câmara da K3 pelo `M141`; a
     * ventoinha da peça (`fan`, `M106`), as `fan_generic` (`SET_FAN_SPEED`) e as da Creality
     * (`output_pin fan0`.., quantas a macro PRINTER_PARAM diz: 0 a da peça, 2 a auxiliar como o
     * `M106 P2` do Orca, a que o `heater_chamber` usa em `case_fan_pins` a da câmara); as luzes
     * do Klipper com `SET_LED`. Ajustes: velocidade e fluxo (`M220`/`M221`, que todo Klipper
     * tem e o `gcode/help` não lista) e o babystep (`SET_GCODE_OFFSET Z_ADJUST`) pelo
     * `gcode_move`; o babystep não aparece na K3, que tem o `NOZZLE_PRINT_Z_OFFSET` dela para os
     * quatro bicos (parâmetros ainda não conferidos). Os modos do `SET_PRINT_MODE` da Creality,
     * pela seção `print_mode` (`<modo>_speed_factor`), e a pausa na camada da macro
     * `SET_PAUSE_AT_LAYER` do Mainsail/Fluidd (U1), que guarda o pedido na
     * `SET_PRINT_STATS_INFO`.
     */
    private suspend fun capabilities(api: URL, host: PrinterHost): Capabilities {
        capabilities[api.toString()]?.let { return it }
        val objects = result(api, host, "/printer/objects/list").optJSONArray("objects")
            .let { list -> (0 until (list?.length() ?: 0)).map { list!!.getString(it) } }.toSet()
        val help = result(api, host, "/printer/gcode/help").keys().asSequence().map { it.uppercase() }.toSet()
        val status = result(api, host, "/printer/objects/query?configfile=settings&heaters").optJSONObject("status")
        val settings = status?.optJSONObject("configfile")?.optJSONObject("settings") ?: JSONObject()
        val available = status?.optJSONObject("heaters")?.optJSONArray("available_heaters")
            .let { list -> (0 until (list?.length() ?: 0)).map { list!!.getString(it) } }.toSet()
        fun maxTemp(name: String, key: String = "max_temp") = settings.optJSONObject(name.lowercase())?.optDouble(key)?.takeIf { !it.isNaN() }?.toFloat()

        val heaters = linkedMapOf<Pair<Temperature.Source, Int>, String?>()
        val heaterList = mutableListOf<Heater>()
        EXTRUDERS.forEachIndexed { i, name ->
            if (name in available) {
                heaters[Temperature.Source.NOZZLE to i] = name
                heaterList += Heater(Temperature.Source.NOZZLE, i, maxTemp(name))
            }
        }
        if ("heater_bed" in available) {
            heaters[Temperature.Source.BED to 0] = "heater_bed"
            heaterList += Heater(Temperature.Source.BED, 0, maxTemp("heater_bed"))
        }
        val chamber = CHAMBERS.firstOrNull { it.startsWith("heater_generic ") && it in available }
        if (chamber != null) {
            heaters[Temperature.Source.CHAMBER to 0] = chamber.substringAfter(' ')
            heaterList += Heater(Temperature.Source.CHAMBER, 0, maxTemp(chamber))
        } else if (CREALITY_CHAMBER in objects && "M141" in help) {
            heaters[Temperature.Source.CHAMBER to 0] = null
            heaterList += Heater(Temperature.Source.CHAMBER, 0, maxTemp(CREALITY_CHAMBER, "target_max"))
        }

        val fans = mutableListOf<Fan>()
        val crealityFans = mutableMapOf<String, CrealityFan>()
        if ("fan" in objects) fans += Fan("fan", Fan.Kind.PART)
        val param = settings.optJSONObject("gcode_macro printer_param")
        val crealityCount = param?.optString("variable_fans")?.trim()?.toIntOrNull()
        if (crealityCount != null && "M106" in help) {
            val chamberFans = settings.optJSONObject(CREALITY_CHAMBER)?.optString("case_fan_pins").orEmpty()
                .split(',').map { it.trim() }.toSet()
            for (i in 0 until crealityCount) {
                val id = "output_pin fan$i"
                if (id !in objects) continue
                val kind = when {
                    i == 0 -> Fan.Kind.PART
                    i == 2 -> Fan.Kind.AUXILIARY
                    "fan$i" in chamberFans -> Fan.Kind.CHAMBER
                    else -> Fan.Kind.OTHER
                }
                fans += Fan(id, kind, "fan$i")
                crealityFans[id] = CrealityFan(i, param.optString("variable_fan${i}_min").trim().toIntOrNull() ?: 0)
            }
        }
        if ("SET_FAN_SPEED" in help) objects.filter { it.startsWith("fan_generic ") }.sorted().forEach { fans += Fan(it, Fan.Kind.OTHER, it.substringAfter(' ')) }
        val lights = if ("SET_LED" in help) objects.filter { name -> LED_PREFIXES.any { name.startsWith(it) } }.sorted().map { Light(it, it.substringAfter(' ')) } else emptyList()

        val motion = "toolhead" in objects
        val move = "gcode_move" in objects
        val printModes = if ("SET_PRINT_MODE" in help && PRINT_MODE in objects) {
            settings.optJSONObject(PRINT_MODE)?.keys()?.asSequence()?.filter { it.endsWith(SPEED_FACTOR) }?.map { it.removeSuffix(SPEED_FACTOR) }?.toList().orEmpty()
        } else emptyList()
        val statsMacro = objects.firstOrNull { it.equals("gcode_macro SET_PRINT_STATS_INFO", ignoreCase = true) }?.takeIf { "SET_PAUSE_AT_LAYER" in help }
        val adjustments = Adjustments(
            speed = move, flow = move, printModes = printModes,
            zOffset = move && "SET_GCODE_OFFSET" in help && "NOZZLE_PRINT_Z_OFFSET" !in help,
            pauseAtLayer = statsMacro != null,
        )
        // CFS da Creality: os _STEP que a tela manda não aparecem no gcode/help, as macros sim.
        val cfs = "box" in objects
        val filament = FilamentControls(
            loadUnload = cfs && "BOX_LOAD_MATERIAL" in help && "BOX_QUIT_MATERIAL" in help,
            refresh = cfs && "BOX_INFO_REFRESH" in help,
        )
        val exclude = EXCLUDE_OBJECT in objects && "EXCLUDE_OBJECT" in help
        // Só na Creality (macro PRINTER_PARAM): os nomes são dela e outro Klipper pode ter um G29 diferente.
        val maintenance = if (param != null) CREALITY_MAINTENANCE.filter { (_, script) -> script in help }.keys.toList() else emptyList()
        val controls = PrinterControls(motion, heaterList, fans, lights, adjustments, filament, exclude, maintenance)
        val subscribed = listOfNotNull(
            "toolhead".takeIf { motion || exclude }, "gcode_move".takeIf { move }, PRINT_MODE.takeIf { printModes.isNotEmpty() }, statsMacro,
            EXCLUDE_OBJECT.takeIf { exclude },
        ) +
            fans.map { it.id } + lights.map { it.id }
        return Capabilities(controls, heaters, crealityFans, subscribed, statsMacro).also { capabilities[api.toString()] = it }
    }

    /** O `result` de um GET da API; falha com [SendException]. */
    private suspend fun result(api: URL, host: PrinterHost, path: String): JSONObject {
        val response = Http.get(api.resolve(path), headers(host))
        check(response, host)
        return jsonOrNull(response.body)?.optJSONObject("result") ?: throw SendException(R.string.send_error_not_moonraker, api.authority)
    }

    /** Os valores da aba Controle em [objects] (assinados por [Capabilities.objects]). */
    private fun controlState(objects: JSONObject, capabilities: Capabilities, status: PrinterStatus): PrinterStatus {
        val toolhead = objects.optJSONObject("toolhead")
        val position = toolhead?.optJSONArray("position")?.let { array -> (0 until minOf(3, array.length())).map { array.optDouble(it).toFloat() } }
        val fans = capabilities.controls.fans.mapNotNull { fan ->
            val state = objects.optJSONObject(fan.id) ?: return@mapNotNull null
            val creality = capabilities.crealityFans[fan.id]
            val speed = if (creality != null) {
                // O pino recebe o mínimo mais a fração do resto (macro M106 da K3).
                val pwm = state.optDouble("value", 0.0) * 255
                if (pwm <= 0) 0.0 else ((pwm - creality.min) / (255 - creality.min)).coerceIn(0.0, 1.0)
            } else {
                state.optDouble("speed", Double.NaN)
            }
            if (speed.isNaN()) null else fan.id to speed.toFloat()
        }.toMap()
        val lights = capabilities.controls.lights.mapNotNull { light ->
            val data = objects.optJSONObject(light.id)?.optJSONArray("color_data") ?: return@mapNotNull null
            val on = (0 until data.length()).any { i -> data.optJSONArray(i)?.let { c -> (0 until c.length()).any { c.optDouble(it) > 0 } } == true }
            light.id to on
        }.toMap()
        val move = objects.optJSONObject("gcode_move")
        fun percent(key: String) = move?.optDouble(key)?.takeIf { !it.isNaN() }?.let { (it * 100).roundToInt() }
        val pause = capabilities.statsMacro?.let { objects.optJSONObject(it)?.optJSONObject("pause_at_layer") }
        val exclude = objects.optJSONObject(EXCLUDE_OBJECT)
        val excluded = exclude?.optJSONArray("excluded_objects").let { list -> (0 until (list?.length() ?: 0)).map { list!!.optString(it) } }.toSet()
        val current = exclude?.optString("current_object")?.takeIf { exclude.opt("current_object") is String }
        fun point(array: JSONArray?) = array?.takeIf { it.length() >= 2 }?.let { it.optDouble(0).toFloat() to it.optDouble(1).toFloat() }
        val printObjects = exclude?.optJSONArray("objects").let { list ->
            (0 until (list?.length() ?: 0)).mapNotNull { i ->
                val item = list!!.optJSONObject(i) ?: return@mapNotNull null
                val name = item.optString("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val polygon = item.optJSONArray("polygon").let { p -> (0 until (p?.length() ?: 0)).mapNotNull { point(p!!.optJSONArray(it)) } }
                PrintObject(name, polygon, point(item.optJSONArray("center")), name in excluded, name == current)
            }
        }
        fun axis(key: String) = toolhead?.optJSONArray(key)?.takeIf { it.length() >= 2 }
        val min = axis("axis_minimum")
        val max = axis("axis_maximum")
        // O mínimo negativo é a parada fora da mesa; a mesa começa em 0.
        val bed = if (min != null && max != null) {
            BedArea(min.optDouble(0).toFloat().coerceAtLeast(0f), min.optDouble(1).toFloat().coerceAtLeast(0f), max.optDouble(0).toFloat(), max.optDouble(1).toFloat())
                .takeIf { it.maxX > it.minX && it.maxY > it.minY }
        } else null
        return status.copy(
            homedAxes = toolhead?.optString("homed_axes"), position = position?.takeIf { it.size == 3 }, fans = fans, lights = lights,
            speed = percent("speed_factor"), flow = percent("extrude_factor"),
            printMode = objects.optJSONObject(PRINT_MODE)?.optString("mode")?.takeIf { it.isNotEmpty() },
            zOffset = move?.optJSONArray("homing_origin")?.optDouble(2)?.takeIf { !it.isNaN() }?.toFloat(),
            pauseAtLayer = pause?.takeIf { it.optBoolean("enable") }?.optInt("layer")?.takeIf { it > 0 },
            objects = printObjects, bed = bed,
        )
    }

    /** Falha se o Klipper estiver imprimindo ou pausado (print_stats.state). */
    private suspend fun checkIdle(api: URL, host: PrinterHost) {
        val status = status(api, host)
        if (status.busy) throw SendException(R.string.send_error_busy, host.name, status.raw.orEmpty())
    }

    /**
     * `print_stats` (estado, arquivo, tempo) e `virtual_sdcard` (posição no arquivo), com as
     * temperaturas, a camada e as caixas CFS da Creality (`box`). Objetos que o Klipper não tem
     * voltam vazios, então a mesma consulta serve para todas.
     */
    private suspend fun status(api: URL, host: PrinterHost): PrinterStatus {
        val response = Http.get(api.resolve("/printer/objects/query?" + STATUS_OBJECTS.joinToString("&") { URLEncoder.encode(it, "UTF-8") }), headers(host))
        check(response, host)
        val objects = jsonOrNull(response.body)?.optJSONObject("result")?.optJSONObject("status")
        val creality = if (hasCrealityScreen(objects)) crealityScreen(api) else null
        return statusOf(objects, api, host, creality)
    }

    /** Só a K2/K3 (com o objeto `box` do CFS) têm a interface da Creality; com o Klipper parado ela não importa. */
    private fun hasCrealityScreen(objects: JSONObject?) = objects?.has("box") == true && !halted(objects)

    /** Klipper parado (parada de emergência, erro do firmware): webhooks.state "shutdown" ou "error". */
    private fun halted(objects: JSONObject?) = objects?.optJSONObject("webhooks")?.optString("state") in setOf("shutdown", "error")

    /** [PrinterStatus] dos objetos de [STATUS_OBJECTS] lidos do Klipper e do estado da tela da Creality. */
    private suspend fun statusOf(objects: JSONObject?, api: URL, host: PrinterHost, creality: CrealityScreen?): PrinterStatus {
        val stats = objects?.optJSONObject("print_stats")
        val sdcard = objects?.optJSONObject("virtual_sdcard")
        if (halted(objects)) {
            val message = objects?.optJSONObject("webhooks")?.optString("state_message")?.trim()?.takeIf { it.isNotEmpty() }
            return PrinterStatus(PrinterStatus.State.ERROR, raw = message, halted = true)
        }
        val raw = stats?.optString("state").orEmpty()
        val state = when (raw) {
            "printing" -> PrinterStatus.State.PRINTING
            "paused" -> PrinterStatus.State.PAUSED
            "error" -> PrinterStatus.State.ERROR
            // A K3 aquece, nivela e carrega o CFS (e a detecção por IA pode pausar) antes de o
            // Klipper começar o arquivo: print_stats fica em standby e só a tela sabe da impressão.
            else -> creality?.state ?: PrinterStatus.State.IDLE
        }
        val active = state == PrinterStatus.State.PRINTING || state == PrinterStatus.State.PAUSED
        val file = stats?.optString("filename")?.takeIf { active && it.isNotEmpty() } ?: creality?.file?.takeIf { active }
        val progress = sdcard?.optDouble("progress")?.takeIf { active && !it.isNaN() }?.toFloat()
        // A Q2 deixa print_duration em 0 (2026-10-30).
        val elapsed = stats?.optDouble("print_duration")?.takeIf { active && it > 0 }?.toLong()
        // SET_PRINT_STATS_INFO (Orca, Klipper comum); a Creality conta no virtual_sdcard.
        val info = stats?.optJSONObject("info")
        val layer = positive(info, "current_layer") ?: positive(sdcard, "layer")
        val layers = positive(info, "total_layer") ?: positive(sdcard, "layer_count")
        val estimate = file?.let { estimatedTime(api, host, it) }
            ?: sdcard?.optJSONObject("cur_print_data")?.optJSONObject("metadata")?.optDouble("estimated_time")?.takeIf { it > 0 }
        // Como o Fluidd: a estimativa do fatiador menos o tempo já impresso; sem ela, ou já passado
        // dela (a K2 imprimiu a calibração de fluxo em 1,6x o estimado), pelo progresso no arquivo.
        val remaining = when {
            !active || elapsed == null -> null
            estimate != null && estimate > elapsed -> (estimate - elapsed).toLong()
            progress != null && progress >= 0.05f -> (elapsed / progress - elapsed).toLong()
            else -> null
        }
        return PrinterStatus(
            state, progress = progress, file = file, raw = raw,
            temperatures = temperatures(objects), layer = layer?.takeIf { active }, layers = layers?.takeIf { active },
            elapsed = elapsed, remaining = remaining, boxes = crealityBoxes(objects?.optJSONObject("box")) + qidiBoxes(objects),
            alert = creality?.alert,
        )
    }

    /**
     * Estado pelo WebSocket do Moonraker (`/websocket`): `printer.objects.subscribe` com os
     * mesmos objetos de [status], mais os da aba Controle ([capabilities]), traz tudo de uma vez,
     * e cada `notify_status_update` só os campos que mudaram, que vão sendo juntados. A tela da Creality continua lida a cada
     * [CREALITY_POLL_MS] (o WebSocket dela só manda tudo ao conectar). Com o Klipper parado
     * (`notify_klippy_shutdown`) ou de volta (`notify_klippy_ready`) assina de novo; quando o
     * Klipper desconecta (reinício do firmware) o fluxo termina. Sem nenhum quadro por
     * [LIVE_SILENCE_MS], mesmo depois do ping, a conexão é dada como caída.
     */
    override fun liveStatus(host: PrinterHost): Flow<PrinterStatus> = channelFlow {
        val api = api(host)
        val controls = capabilities(api, host)
        val port = api.port.takeIf { it != -1 } ?: api.defaultPort
        val socket = try {
            withContext(Dispatchers.IO) {
                WebSocketClient.open(api.host, port, "/websocket", headers(host), LIVE_CONNECT_MS, tls = api.protocol == "https")
            }
        } catch (e: WebSocketClient.WebSocketRefused) {
            val code = e.status.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            throw if (code == 401 || code == 403) SendException(R.string.send_error_auth, code, cause = e)
            else SendException(R.string.send_error_http, code, e.status, cause = e)
        } catch (e: IOException) {
            throw SendException(R.string.send_error_unreachable, api.authority, cause = e)
        }
        val objects = JSONObject()
        var creality: CrealityScreen? = null
        var crealityWatch: Job? = null
        suspend fun publish() {
            val snapshot = synchronized(objects) { JSONObject(objects.toString()) }
            send(controlState(snapshot, controls, statusOf(snapshot, api, host, creality)))
        }
        var request = 0
        fun subscribe() {
            val wanted = JSONObject()
            (STATUS_OBJECTS + controls.objects).forEach { wanted.put(it, JSONObject.NULL) }
            socket.send(
                JSONObject().put("jsonrpc", "2.0").put("method", "printer.objects.subscribe")
                    .put("params", JSONObject().put("objects", wanted)).put("id", ++request)
            )
        }
        try {
            withContext(Dispatchers.IO) {
                subscribe()
                var pinged = false
                while (true) {
                    ensureActive()
                    val message = socket.receive(LIVE_READ_MS)
                    val silent = System.currentTimeMillis() - socket.lastFrameAt
                    if (message == null) {
                        if (silent >= LIVE_SILENCE_MS) throw SendException(R.string.send_error_unreachable, api.authority)
                        if (silent >= LIVE_SILENCE_MS / 2 && !pinged) {
                            socket.ping()
                            pinged = true
                        }
                        continue
                    }
                    pinged = false
                    when (message.optString("method")) {
                        "notify_status_update" -> {
                            val changed = message.optJSONArray("params")?.optJSONObject(0) ?: continue
                            synchronized(objects) {
                                for (name in changed.keys()) {
                                    val fields = changed.optJSONObject(name) ?: continue
                                    val current = objects.optJSONObject(name) ?: JSONObject().also { objects.put(name, it) }
                                    for (field in fields.keys()) current.put(field, fields.get(field))
                                }
                            }
                            publish()
                        }
                        "notify_klippy_shutdown", "notify_klippy_ready" -> subscribe()
                        "notify_klippy_disconnected" -> {
                            // O firmware reinicia e pode voltar com outra configuração.
                            capabilities.remove(api.toString())
                            return@withContext
                        }
                        "" -> if (message.optInt("id") == request) {
                            message.optJSONObject("error")?.let { error ->
                                throw SendException(R.string.send_error_http, error.optInt("code"), error.optString("message"))
                            }
                            val status = message.optJSONObject("result")?.optJSONObject("status") ?: JSONObject()
                            synchronized(objects) {
                                objects.keys().asSequence().toList().forEach(objects::remove)
                                for (name in status.keys()) objects.put(name, status.get(name))
                            }
                            if (crealityWatch == null && hasCrealityScreen(status)) {
                                crealityWatch = this@channelFlow.launch {
                                    while (true) {
                                        creality = crealityScreen(api)
                                        publish()
                                        delay(CREALITY_POLL_MS.toLong())
                                    }
                                }
                            }
                            publish()
                        }
                    }
                }
            }
        } catch (e: IOException) {
            throw SendException(R.string.send_error_unreachable, api.authority, cause = e)
        } finally {
            crealityWatch?.cancel()
            socket.close()
        }
    }

    /** O que a tela da Creality mostra: erro, impressão que o Klipper ainda não começou ([state]) e o arquivo dela. */
    private class CrealityScreen(val alert: PrinterAlert?, val state: PrinterStatus.State?, val file: String?)

    /**
     * Estado da tela da Creality: o WebSocket manda tudo ao conectar, com `err` (`key` é o número
     * do código, 0 sem erro), `state` e `printFileName`. Visto na K3 em 2026-11-02: `state` 0
     * livre, 1 imprimindo (também enquanto prepara) e 5 pausada pela detecção por IA (AC0104).
     * Null se o WebSocket não responde.
     */
    private suspend fun crealityScreen(api: URL): CrealityScreen? = withContext(Dispatchers.IO) {
        val message = try {
            WebSocketClient.creality(api.host, CREALITY_CONNECT_MS).use { it.awaitMessage(CREALITY_READ_MS) { m -> m.has("err") } }
        } catch (e: IOException) {
            null
        } ?: return@withContext null
        val number = message.optJSONObject("err")?.optInt("key") ?: 0
        val alert = if (number > 0) PrinterAlert(CREALITY_CODE_PREFIX[number].orEmpty() + "%04d".format(Locale.ROOT, number), number) else null
        val file = message.optString("printFileName").substringAfterLast('/').takeIf { it.isNotEmpty() }
        val state = when (message.optInt("state", -1)) {
            1 -> PrinterStatus.State.PRINTING
            5 -> PrinterStatus.State.PAUSED
            else -> null
        }?.takeIf { file != null }
        CrealityScreen(alert, state, file)
    }

    private fun positive(json: JSONObject?, key: String) = json?.optInt(key, -1)?.takeIf { it > 0 }

    private fun temperatures(objects: JSONObject?): List<Temperature> {
        if (objects == null) return emptyList()
        val list = mutableListOf<Temperature>()
        fun add(name: String, source: Temperature.Source, index: Int = 0) {
            val heater = objects.optJSONObject(name) ?: return
            val current = heater.optDouble("temperature").takeIf { !it.isNaN() } ?: return
            val target = heater.optDouble("target").takeIf { !it.isNaN() }
            list += Temperature(source, current.toFloat(), target?.toFloat(), index)
        }
        EXTRUDERS.forEachIndexed { i, name -> add(name, Temperature.Source.NOZZLE, i) }
        add("heater_bed", Temperature.Source.BED)
        CHAMBERS.firstOrNull { objects.optJSONObject(it)?.has("temperature") == true }?.let { add(it, Temperature.Source.CHAMBER) }
        // O sensor da câmara da K3 não tem alvo; o do M141 fica no heater_chamber.
        val chamberTarget = objects.optJSONObject(CREALITY_CHAMBER)?.optDouble("target")?.takeIf { !it.isNaN() }?.toFloat()
        if (chamberTarget != null) list.replaceAll { if (it.source == Temperature.Source.CHAMBER && it.target == null) it.copy(target = chamberTarget) else it }
        return list
    }

    /** Caixas CFS ligadas (`box.T1`..`T4` com `state` "connect"): umidade e temperatura dentro delas. */
    private fun crealityBoxes(box: JSONObject?): List<FilamentBox> {
        if (box == null) return emptyList()
        return (1..4).mapNotNull { n ->
            val unit = box.optJSONObject("T$n")?.takeIf { it.optString("state") == "connect" } ?: return@mapNotNull null
            FilamentBox(
                "${unit.optString("type").takeIf { it.isNotEmpty() && it != "None" } ?: "CFS"} $n",
                humidity = unit.optString("dry_and_humidity").toDoubleOrNull()?.toInt(),
                temperature = unit.optString("temperature").toFloatOrNull(),
            )
        }
    }

    /** Box da QIDI ligadas: o AHT20 de cada uma (`aht20_f heater_boxN`) dá a temperatura e a umidade. */
    private fun qidiBoxes(objects: JSONObject?): List<FilamentBox> = QIDI_BOXES.mapIndexedNotNull { i, name ->
        val sensor = objects?.optJSONObject(name)?.takeIf { it.has("humidity") } ?: return@mapIndexedNotNull null
        FilamentBox(
            "Box ${i + 1}",
            humidity = sensor.optDouble("humidity").takeIf { !it.isNaN() }?.toInt(),
            temperature = sensor.optDouble("temperature").takeIf { !it.isNaN() }?.toFloat(),
        )
    }

    /**
     * `estimated_time` que o Moonraker leu do arquivo [file] (s), guardado por arquivo; null se o
     * cabeçalho não for de um fatiador que ele conhece (o "PocketSlicer" da Creality).
     */
    private suspend fun estimatedTime(api: URL, host: PrinterHost, file: String): Double? {
        val key = host.id + "/" + file
        estimates[key]?.let { return it.takeIf { it > 0 } }
        val response = Http.get(api.resolve("/server/files/metadata?filename=" + URLEncoder.encode(file, "UTF-8")), headers(host))
        val time = if (response.code in 200..299) jsonOrNull(response.body)?.optJSONObject("result")?.optDouble("estimated_time")?.takeIf { !it.isNaN() } else null
        estimates[key] = time ?: 0.0
        return time?.takeIf { it > 0 }
    }

    private val estimates = ConcurrentHashMap<String, Double>()

    /**
     * A primeira câmera ligada do Moonraker (`/server/webcams/list`), com o caminho relativo
     * resolvido no endereço da impressora (o nginx da porta 80, como no Fluidd). Sem câmera
     * cadastrada: na Snapmaker U1 de fábrica, a foto que ela grava em `camera/monitor.jpg`
     * enquanto recebe `camera.start_monitor` pelo WebSocket do Moonraker (u1-moonraker
     * components/repeater.py; o pedido não existe por HTTP); senão a da Creality K2, que só fala
     * WebRTC na porta 8000.
     */
    override suspend fun camera(host: PrinterHost): Camera? {
        val api = api(host)
        val response = Http.get(api.resolve("/server/webcams/list"), headers(host))
        val webcams = if (response.code in 200..299) jsonOrNull(response.body)?.optJSONObject("result")?.optJSONArray("webcams") else null
        for (i in 0 until (webcams?.length() ?: 0)) {
            val cam = webcams!!.getJSONObject(i)
            if (!cam.optBoolean("enabled", true)) continue
            val service = cam.optString("service")
            val stream = cam.optString("stream_url").takeIf { it.isNotEmpty() }
            val snapshot = cam.optString("snapshot_url").takeIf { it.isNotEmpty() }
            // O mjpegstreamer-adaptive do Fluidd/Mainsail recarrega a foto; os outros serviços (WebRTC, HLS) também caem nela.
            val (url, still) = when {
                service == "mjpegstreamer" && stream != null -> stream to false
                snapshot != null -> snapshot to true
                stream != null -> stream to false
                else -> continue
            }
            return Camera.Image(
                URL(URL(api.protocol, api.host, ""), url).toString(), still, cam.optInt("rotation"),
                cam.optBoolean("flip_horizontal"), cam.optBoolean("flip_vertical"),
                fps = cam.optInt("target_fps", 0).takeIf { it > 0 } ?: Camera.Image.SNAPSHOT_FPS,
            )
        }
        if (u1Slots(api, host) != null) {
            // java.net.URL não aceita o esquema ws.
            val socket = (if (api.protocol == "https") "wss://" else "ws://") + api.authority + "/websocket"
            return Camera.Image(api.resolve("/server/files/camera/monitor.jpg").toString(), snapshot = true, monitorSocket = socket)
        }
        val signaling = URL(api.protocol, api.host, CREALITY_WEBRTC_PORT, "/call/webrtc_local")
        val creality = try {
            Http.get(signaling, emptyMap()).code in 200..299
        } catch (e: SendException) {
            false
        }
        return if (creality) Camera.CrealityWebRtc(signaling.toString()) else null
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
