package io.github.andredevel.slicer.send

import io.github.andredevel.slicer.R
import org.json.JSONException
import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

/**
 * Cliente MQTT 3.1.1 mínimo (QoS 0) para o broker das impressoras Bambu Lab na rede local: TLS na
 * porta 8883, usuário `bblp`, código de acesso como senha. Comandos vão para
 * `device/<série>/request` e o estado volta em `device/<série>/report`; o número de série é o CN
 * do certificado da impressora. Bloqueante: chamar fora da thread principal.
 */
internal class BambuMqtt(private val socket: SSLSocket, accessCode: String) : AutoCloseable {
    private val input = DataInputStream(socket.inputStream.buffered())
    private val output: OutputStream = socket.outputStream
    val serial: String = serialOf(socket.session.peerCertificates.first() as X509Certificate)

    init {
        // CONNECT: sessão limpa, usuário e senha, keep-alive de 60 s.
        val header = string("MQTT") + byteArrayOf(4, 0xC2.toByte(), 0, 60)
        send(0x10, header + string("pocketslicer-${System.nanoTime()}") + string(USER) + string(accessCode))
        val (type, body) = readPacket()
        if (type != 0x20 || body.size < 2) throw IOException("no CONNACK")
        // 4: usuário ou senha errados; 5: não autorizado.
        when (body[1].toInt()) {
            0 -> {}
            4, 5 -> throw SendException(R.string.send_error_access_code)
            else -> throw SendException(R.string.send_error_ftp, "MQTT CONNACK ${body[1]}")
        }
        // SUBSCRIBE (id 1) em report, QoS 0.
        send(0x82, byteArrayOf(0, 1) + string("device/$serial/report") + byteArrayOf(0))
    }

    fun publish(json: JSONObject) = send(0x30, string("device/$serial/request") + json.toString().toByteArray())

    /**
     * Próxima mensagem de report que satisfaz [accept], ou null se não vier em [timeoutMs].
     * As demais (inclusive SUBACK) são descartadas.
     */
    fun awaitReport(timeoutMs: Long, accept: (JSONObject) -> Boolean): JSONObject? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            socket.soTimeout = left.toInt()
            val (type, body) = try {
                readPacket()
            } catch (e: SocketTimeoutException) {
                return null
            }
            if (type and 0xF0 != 0x30) continue
            val topicLength = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
            val payload = String(body, 2 + topicLength, body.size - 2 - topicLength)
            val json = try {
                JSONObject(payload)
            } catch (e: JSONException) {
                continue
            }
            if (accept(json)) return json
        }
    }

    override fun close() {
        runCatching { send(0xE0, ByteArray(0)) } // DISCONNECT
        socket.close()
    }

    private fun send(type: Int, body: ByteArray) {
        var length = body.size
        val header = ArrayList<Byte>().apply { add(type.toByte()) }
        do {
            var digit = length % 128
            length /= 128
            if (length > 0) digit = digit or 0x80
            header.add(digit.toByte())
        } while (length > 0)
        output.write(header.toByteArray() + body)
        output.flush()
    }

    private fun readPacket(): Pair<Int, ByteArray> {
        val type = input.readUnsignedByte()
        var length = 0
        var multiplier = 1
        while (true) {
            val digit = input.readUnsignedByte()
            length += (digit and 0x7F) * multiplier
            if (digit and 0x80 == 0) break
            multiplier *= 128
        }
        val body = ByteArray(length)
        input.readFully(body)
        return type to body
    }

    private companion object {
        const val USER = "bblp"

        fun string(text: String): ByteArray {
            val bytes = text.toByteArray()
            return byteArrayOf((bytes.size shr 8).toByte(), bytes.size.toByte()) + bytes
        }

        fun serialOf(certificate: X509Certificate): String =
            Regex("""(?:^|,)\s*CN=([^,]+)""").find(certificate.subjectX500Principal.name)?.groupValues?.get(1)
                ?: throw IOException("no CN in ${certificate.subjectX500Principal.name}")
    }
}
