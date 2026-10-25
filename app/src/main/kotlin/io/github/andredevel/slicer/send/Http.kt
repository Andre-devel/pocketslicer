package io.github.andredevel.slicer.send

import io.github.andredevel.slicer.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.net.UnknownHostException
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** HTTP em texto puro das impressoras da rede local (network_security_config). */
internal object Http {
    private const val CONNECT_TIMEOUT_MS = 10_000
    /** A impressora só responde ao upload depois de gravar (e às vezes analisar) o arquivo. */
    private const val READ_TIMEOUT_MS = 120_000
    private const val BUFFER = 64 * 1024

    /** `192.168.0.10`, `impressora.local:7125` ou `http://...` em URL sem barra no fim. */
    fun baseUrl(address: String): URL {
        val text = address.trim().trimEnd('/')
        val withScheme = if ("://" in text) text else "http://$text"
        val uri = try {
            URI(withScheme)
        } catch (e: URISyntaxException) {
            throw SendException(R.string.send_error_address, address, cause = e)
        }
        if (uri.scheme != "http" && uri.scheme != "https" || uri.host.isNullOrEmpty()) throw SendException(R.string.send_error_address, address)
        return try {
            uri.toURL()
        } catch (e: MalformedURLException) {
            throw SendException(R.string.send_error_address, address, cause = e)
        }
    }

    fun URL.withPort(port: Int) = URL(protocol, host, port, file)

    fun URL.resolve(path: String) = URL(toString() + path)

    class Response(val code: Int, val body: String)

    suspend fun get(url: URL, headers: Map<String, String>): Response = request(url) { connection ->
        headers.forEach(connection::setRequestProperty)
    }

    /**
     * POST multipart/form-data com [fields] e o arquivo no campo [fileField], em streaming
     * (o G-code pode ter centenas de MB).
     */
    suspend fun postFile(
        url: URL,
        headers: Map<String, String>,
        fields: Map<String, String>,
        fileField: String,
        file: File,
        name: String,
        progress: (Float) -> Unit,
    ): Response {
        val boundary = "----PocketSlicer" + UUID.randomUUID().toString().replace("-", "")
        val head = buildString {
            for ((key, value) in fields) {
                append("--$boundary\r\nContent-Disposition: form-data; name=\"$key\"\r\n\r\n$value\r\n")
            }
            append("--$boundary\r\nContent-Disposition: form-data; name=\"$fileField\"; filename=\"${quote(name)}\"\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }.toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val size = file.length()
        val context = coroutineContext
        return request(url) { connection ->
            connection.requestMethod = "POST"
            connection.doOutput = true
            headers.forEach(connection::setRequestProperty)
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setFixedLengthStreamingMode(head.size + size + tail.size)
            connection.outputStream.use { output ->
                output.write(head)
                file.inputStream().use { input ->
                    val buffer = ByteArray(BUFFER)
                    var sent = 0L
                    while (true) {
                        context.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        sent += read
                        progress(if (size > 0) sent.toFloat() / size else 1f)
                    }
                }
                output.write(tail)
            }
        }
    }

    /** Aspas e quebras de linha quebrariam o cabeçalho do multipart. */
    private fun quote(name: String) = name.replace(Regex("[\"\\r\\n]"), "_")

    private suspend fun request(url: URL, send: (HttpURLConnection) -> Unit): Response = withContext(Dispatchers.IO) {
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            send(connection)
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            Response(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } catch (e: IOException) {
            // Sem rota, recusada ou sem resposta: quase sempre IP errado ou rede diferente.
            val unreachable = e is UnknownHostException || e is SocketTimeoutException || e is ConnectException || e is NoRouteToHostException
            if (unreachable) throw SendException(R.string.send_error_unreachable, url.authority, cause = e)
            throw SendException(R.string.send_error_io, e.message ?: e.toString(), cause = e)
        } finally {
            connection.disconnect()
        }
    }
}
