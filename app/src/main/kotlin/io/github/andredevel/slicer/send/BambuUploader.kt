package io.github.andredevel.slicer.send

import android.content.res.AssetManager
import io.github.andredevel.slicer.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import kotlin.coroutines.coroutineContext

/**
 * Impressoras Bambu Lab na rede local: FTPS implícito na porta 990, usuário `bblp` e o código de
 * acesso da tela da impressora como senha; o arquivo vai para a raiz do cartão. Funciona sem o
 * "modo somente LAN". Iniciar a impressão exigiria MQTT (fica para depois), então só envia.
 *
 * O certificado da impressora (CN = número de série, sem IP) é conferido contra as CAs da Bambu
 * em `printer.cer` (resources/cert do Orca); o nome do host não, porque o certificado não o tem.
 */
class BambuUploader(private val assets: AssetManager) : Uploader {
    override val canStart = false

    override suspend fun test(host: PrinterHost) {
        session(host) { }
    }

    override suspend fun upload(host: PrinterHost, file: File, name: String, start: Boolean, progress: (Float) -> Unit) {
        require(!start) { "Bambu: start is not supported" }
        session(host) { control ->
            control.command("TYPE I", 200)
            // Canal de dados também cifrado, retomando a sessão TLS do controle (o servidor exige).
            control.command("PBSZ 0", 200)
            control.command("PROT P", 200)
            val port = passivePort(control.command("PASV", 227))
            val raw = Socket().apply {
                soTimeout = READ_TIMEOUT_MS
                connect(InetSocketAddress(control.host, port), CONNECT_TIMEOUT_MS)
            }
            // Mesmo host e porta do controle: o cache de sessões do SSLContext retoma a sessão.
            val data = control.factory.createSocket(raw, control.host, control.port, true) as SSLSocket
            data.use {
                control.command("STOR ${ftpName(name)}", 150, 125)
                data.startHandshake()
                copy(file, data.outputStream, progress)
            }
            control.reply(226)
        }
    }

    /** Conecta, entra com o código de acesso, roda [block] e sai. */
    private suspend fun session(host: PrinterHost, block: suspend (Control) -> Unit) = withContext(Dispatchers.IO) {
        val code = host.secret?.trim().orEmpty()
        if (code.isEmpty()) throw SendException(R.string.send_error_access_code)
        val address = Http.baseUrl(host.address)
        val hostName = address.host
        val port = if (address.port == -1) PORT else address.port
        val factory = sslContext().socketFactory
        try {
            val raw = Socket().apply {
                soTimeout = READ_TIMEOUT_MS
                connect(InetSocketAddress(hostName, port), CONNECT_TIMEOUT_MS)
            }
            (factory.createSocket(raw, hostName, port, true) as SSLSocket).use { socket ->
                socket.startHandshake()
                val control = Control(socket, hostName, port, factory)
                control.reply(220)
                control.command("USER $USER", 331)
                if (control.send("PASS $code").code != 230) throw SendException(R.string.send_error_access_code)
                block(control)
                runCatching { control.send("QUIT") }
            }
        } catch (e: SSLException) {
            throw SendException(R.string.send_error_tls, address.authority, e.message ?: e.toString(), cause = e)
        } catch (e: IOException) {
            val unreachable = e is UnknownHostException || e is SocketTimeoutException || e is ConnectException || e is NoRouteToHostException
            if (unreachable) throw SendException(R.string.send_error_unreachable, address.authority, cause = e)
            throw SendException(R.string.send_error_io, e.message ?: e.toString(), cause = e)
        }
    }

    private fun sslContext(): SSLContext {
        val certificates = assets.open(CA_ASSET).use { CertificateFactory.getInstance("X.509").generateCertificates(it) }
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            certificates.forEachIndexed { i, certificate -> setCertificateEntry("bbl$i", certificate) }
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }
        return SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
    }

    private suspend fun copy(file: File, output: OutputStream, progress: (Float) -> Unit) {
        val context = coroutineContext
        val size = file.length()
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
            output.flush()
        }
    }

    private class Reply(val code: Int, val text: String)

    /** Canal de controle: comandos de uma linha e respostas (inclusive as de várias linhas). */
    private class Control(socket: SSLSocket, val host: String, val port: Int, val factory: SSLSocketFactory) {
        private val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
        private val writer = socket.outputStream

        fun send(line: String): Reply {
            writer.write("$line\r\n".toByteArray(Charsets.UTF_8))
            writer.flush()
            return read()
        }

        fun command(line: String, vararg expected: Int): Reply = check(send(line), expected)

        fun reply(vararg expected: Int): Reply = check(read(), expected)

        private fun check(reply: Reply, expected: IntArray): Reply {
            if (reply.code !in expected) throw SendException(R.string.send_error_ftp, "${reply.code} ${reply.text}")
            return reply
        }

        private fun read(): Reply {
            val first = readLine()
            val code = first.take(3).toIntOrNull() ?: throw IOException("bad reply: $first")
            // "123-..." abre uma resposta de várias linhas, que termina em "123 ...".
            if (first.getOrNull(3) == '-') {
                while (!readLine().startsWith("$code ")) Unit
            }
            return Reply(code, first.drop(4))
        }

        private fun readLine(): String = reader.readLine() ?: throw IOException("connection closed")
    }

    private companion object {
        const val PORT = 990
        const val USER = "bblp"
        const val CA_ASSET = "printer.cer"
        const val CONNECT_TIMEOUT_MS = 10_000
        /** O ESP da impressora grava devagar; a resposta final pode demorar. */
        const val READ_TIMEOUT_MS = 120_000
        const val BUFFER = 64 * 1024

        /** "227 Entering Passive Mode (h1,h2,h3,h4,p1,p2)": só a porta; o IP é o do controle. */
        fun passivePort(reply: Reply): Int {
            val numbers = Regex("""(\d+),(\d+),(\d+),(\d+),(\d+),(\d+)""").find(reply.text)?.groupValues
                ?: throw SendException(R.string.send_error_ftp, "227 ${reply.text}")
            return numbers[5].toInt() * 256 + numbers[6].toInt()
        }

        /** Como o Orca (fold_utf8_to_ascii): sem acentos, barras nem quebras de linha. */
        fun ftpName(name: String): String =
            java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}"), "")
                .replace(Regex("[^\\x20-\\x7E]|[/\\\\]"), "_")
    }
}
