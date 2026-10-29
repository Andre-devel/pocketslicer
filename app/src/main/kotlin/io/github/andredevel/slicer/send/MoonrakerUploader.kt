package io.github.andredevel.slicer.send

import io.github.andredevel.slicer.R
import io.github.andredevel.slicer.send.Http.resolve
import io.github.andredevel.slicer.send.Http.withPort
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.net.URL

/**
 * Moonraker (API do Klipper), como `src/slic3r/Utils/Moonraker.cpp` do Orca: o G-code vai para
 * `POST /server/files/upload` na raiz `gcodes`, com `print=false`, e a impressão começa por
 * `POST /printer/print/start` com o caminho que a resposta do envio devolve. Num .gcode.3mf o
 * G-code é o `Metadata/plate_1.gcode`, que o campo `plateindex` indica (servidores sem suporte
 * o ignoram).
 */
object MoonrakerUploader : Uploader {
    override val canStart = true

    /** Porta própria do Moonraker, quando o nginx do Fluidd/Mainsail (porta 80) não repassa a API. */
    private const val MOONRAKER_PORT = 7125

    override suspend fun test(host: PrinterHost) {
        api(host)
    }

    override suspend fun upload(host: PrinterHost, file: File, name: String, start: Boolean, progress: (Float) -> Unit) {
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
            // O servidor pode renomear o arquivo (nome repetido): vale o caminho da resposta.
            val path = jsonOrNull(response.body)?.optJSONObject("result")?.optJSONObject("item")?.optString("path")?.takeIf { it.isNotEmpty() } ?: name
            check(Http.postJson(api.resolve("/printer/print/start"), headers(host), JSONObject().put("filename", path).toString()), host)
        }
    }

    /** Falha se o Klipper estiver imprimindo ou pausado (print_stats.state). */
    private suspend fun checkIdle(api: URL, host: PrinterHost) {
        val response = Http.get(api.resolve("/printer/objects/query?print_stats"), headers(host))
        check(response, host)
        val state = jsonOrNull(response.body)?.optJSONObject("result")?.optJSONObject("status")
            ?.optJSONObject("print_stats")?.optString("state").orEmpty()
        if (state == "printing" || state == "paused") throw SendException(R.string.send_error_busy, host.name, state)
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
