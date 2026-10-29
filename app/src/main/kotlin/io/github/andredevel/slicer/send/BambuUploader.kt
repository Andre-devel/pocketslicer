package io.github.andredevel.slicer.send

import android.content.res.AssetManager
import android.util.Log
import io.github.andredevel.slicer.R
import org.json.JSONArray
import org.json.JSONObject
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
 * "modo somente LAN". Para imprimir, o comando `project_file` vai pelo MQTT ([BambuMqtt]), com o
 * filamento que está no bico (`ams.tray_now`: um slot do AMS ou o carretel externo).
 *
 * O certificado da impressora (CN = número de série, sem IP) é conferido contra as CAs da Bambu
 * em `printer.cer` (resources/cert do Orca); o nome do host não, porque o certificado não o tem.
 */
class BambuUploader(private val assets: AssetManager) : Uploader {
    override val canStart = true

    override suspend fun test(host: PrinterHost): Connection {
        val start = System.nanoTime()
        session(host) { }
        return Connection("Bambu FTPS", (System.nanoTime() - start) / 1_000_000)
    }

    override suspend fun upload(host: PrinterHost, file: File, name: String, start: Boolean, progress: (Float) -> Unit) {
        val tray = if (start) idleTray(host) else null
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
        if (tray != null) startPrint(host, ftpName(name), tray)
    }

    /**
     * Confere pelo estado da impressora (pushall) que ela está livre e devolve o `tray_now`:
     * 0 a 103 é um slot do AMS; 254/255, o carretel externo ou nenhum.
     */
    private suspend fun idleTray(host: PrinterHost): Int = mqtt(host) { mqtt ->
        mqtt.publish(JSONObject().put("pushing", JSONObject().put("sequence_id", "0").put("command", "pushall")))
        val print = mqtt.awaitReport(REPORT_TIMEOUT_MS) { it.optJSONObject("print")?.has("gcode_state") == true }
            ?.getJSONObject("print") ?: throw SendException(R.string.send_error_ftp, "MQTT: no status")
        val state = print.optString("gcode_state")
        if (state in BUSY_STATES) throw SendException(R.string.send_error_busy, host.name, state)
        print.optJSONObject("ams")?.optString("tray_now")?.toIntOrNull() ?: EXTERNAL_TRAY
    }

    /** `project_file` como o Bambu Studio na rede local (A1/P1: arquivo em file:///sdcard/). */
    private suspend fun startPrint(host: PrinterHost, name: String, tray: Int) = mqtt(host) { mqtt ->
        val ams = tray in 0..103
        val command = JSONObject()
            .put("sequence_id", "0")
            .put("command", "project_file")
            .put("param", "Metadata/plate_1.gcode")
            .put("url", "file:///sdcard/$name")
            .put("subtask_name", name.removeSuffix(".gcode.3mf").removeSuffix(".3mf"))
            .put("project_id", "0").put("profile_id", "0").put("task_id", "0").put("subtask_id", "0")
            .put("md5", "")
            .put("bed_type", "auto")
            .put("bed_leveling", true)
            .put("flow_cali", false)
            .put("vibration_cali", true)
            .put("layer_inspect", false)
            .put("timelapse", false)
            .put("use_ams", ams)
            .put("ams_mapping", JSONArray().put(if (ams) tray else -1))
            .put("ams_mapping2", JSONArray().put(
                if (ams) JSONObject().put("ams_id", tray / 4).put("slot_id", tray % 4)
                else JSONObject().put("ams_id", 255).put("slot_id", 255),
            ))
        mqtt.publish(JSONObject().put("print", command))
        // Resposta ao comando, ou a impressora já preparando.
        val reply = mqtt.awaitReport(REPORT_TIMEOUT_MS) { report ->
            val print = report.optJSONObject("print") ?: return@awaitReport false
            print.optString("command") == "project_file" || print.optString("gcode_state") in START_STATES
        }?.getJSONObject("print")
        if (reply == null) throw SendException(R.string.send_error_start_unconfirmed, host.name)
        // A recusa vem como err_code (84033543: comando sem assinatura, firmware de 2025+ fora do
        // modo desenvolvedor) ou result "fail".
        val errorCode = reply.optLong("err_code")
        val result = reply.optString("result")
        if (errorCode != 0L || result.equals("fail", true) || result.equals("failed", true)) {
            Log.w(TAG, "project_file refused: $reply")
            val reason = reply.optString("reason").ifEmpty { if (errorCode != 0L) errorCode.toString() else result }
            throw SendException(R.string.send_error_start, host.name, reason)
        }
    }

    private suspend fun <T> mqtt(host: PrinterHost, block: (BambuMqtt) -> T): T =
        connect(host, MQTT_PORT) { socket, code -> BambuMqtt(socket, code).use(block) }

    /** FTPS: conecta, entra com o código de acesso, roda [block] e sai. */
    private suspend fun session(host: PrinterHost, block: suspend (Control) -> Unit) {
        val address = Http.baseUrl(host.address)
        val port = address.port.takeIf { it != -1 } ?: PORT
        connect(host, port) { socket, code ->
            val control = Control(socket, address.host, port, this)
            control.reply(220)
            control.command("USER $USER", 331)
            if (control.send("PASS $code").code != 230) throw SendException(R.string.send_error_access_code)
            block(control)
            runCatching { control.send("QUIT") }
        }
    }

    /**
     * Conexão TLS conferida contra as CAs da Bambu na [port] do host; [block] recebe o socket e o
     * código de acesso. Erros de rede viram [SendException].
     */
    private suspend fun <T> connect(host: PrinterHost, port: Int, block: suspend SSLSocketFactory.(SSLSocket, String) -> T): T = withContext(Dispatchers.IO) {
        val code = host.secret?.trim().orEmpty()
        if (code.isEmpty()) throw SendException(R.string.send_error_access_code)
        val address = Http.baseUrl(host.address)
        val hostName = address.host
        val factory = sslContext().socketFactory
        try {
            val raw = Socket().apply {
                soTimeout = READ_TIMEOUT_MS
                connect(InetSocketAddress(hostName, port), CONNECT_TIMEOUT_MS)
            }
            (factory.createSocket(raw, hostName, port, true) as SSLSocket).use { socket ->
                socket.startHandshake()
                factory.block(socket, code)
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
        const val MQTT_PORT = 8883
        const val TAG = "BambuUploader"
        /** Espera pelo estado (pushall) e pela resposta ao project_file. */
        const val REPORT_TIMEOUT_MS = 15_000L
        /** tray_now do carretel externo (sem AMS). */
        const val EXTERNAL_TRAY = 254
        val BUSY_STATES = setOf("RUNNING", "PAUSE", "PREPARE", "SLICING")
        val START_STATES = setOf("PREPARE", "RUNNING", "SLICING")
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
