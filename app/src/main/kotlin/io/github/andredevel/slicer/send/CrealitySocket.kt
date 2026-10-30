package io.github.andredevel.slicer.send

import org.json.JSONException
import org.json.JSONObject
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import kotlin.random.Random

/**
 * WebSocket da interface da Creality na porta [PORT] (K2, K3): mensagens JSON em texto, como
 * `{"method": "get", "params": {"boxsInfo": 1}}`, e o estado da impressora em JSON vindo dela a
 * qualquer momento. Bloqueante; quem chama fica no Dispatchers.IO.
 */
internal class CrealitySocket private constructor(private val socket: Socket, private val input: DataInputStream) : Closeable {

    /** Manda [message] num quadro de texto mascarado, como o cliente deve. */
    fun send(message: JSONObject) {
        val payload = message.toString().toByteArray()
        val mask = Random.nextBytes(4)
        val head = when {
            payload.size < 126 -> byteArrayOf(0x81.toByte(), (0x80 or payload.size).toByte())
            payload.size < 65_536 -> byteArrayOf(0x81.toByte(), (0x80 or 126).toByte(), (payload.size shr 8).toByte(), payload.size.toByte())
            else -> throw IOException("message too large: ${payload.size}")
        }
        for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        socket.getOutputStream().apply { write(head + mask + payload); flush() }
    }

    /** `get` de [key]; devolve a primeira mensagem que traz [key] em até [timeoutMs], ou null. */
    fun get(key: String, timeoutMs: Int): JSONObject? {
        send(JSONObject().put("method", "get").put("params", JSONObject().put(key, 1)))
        return awaitMessage(timeoutMs) { it.has(key) }
    }

    /** Primeira mensagem JSON em até [timeoutMs] que atende [match], ou null. */
    fun awaitMessage(timeoutMs: Int, match: (JSONObject) -> Boolean): JSONObject? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            socket.soTimeout = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
            val text = try {
                readTextFrame()
            } catch (e: SocketTimeoutException) {
                return null
            } ?: continue
            val message = try {
                JSONObject(text)
            } catch (e: JSONException) {
                continue
            }
            if (match(message)) return message
        }
        return null
    }

    override fun close() = socket.close()

    /** Um quadro do servidor; null quando não é texto. */
    private fun readTextFrame(): String? {
        val opcode = input.readUnsignedByte() and 0x0f
        val second = input.readUnsignedByte()
        val length = when (val small = second and 0x7f) {
            126 -> input.readUnsignedShort().toLong()
            127 -> input.readLong()
            else -> small.toLong()
        }
        if (length > 1 shl 20) throw IOException("frame too large: $length")
        val mask = if (second and 0x80 != 0) ByteArray(4).also(input::readFully) else null
        val payload = ByteArray(length.toInt()).also(input::readFully)
        if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        return if (opcode == 1) String(payload) else null
    }

    companion object {
        const val PORT = 9999

        /** Abre a conexão com [host]; falha com IOException se não houver a interface da Creality. */
        fun open(host: String, connectTimeoutMs: Int): CrealitySocket {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, PORT), connectTimeoutMs)
                socket.soTimeout = connectTimeoutMs
                val key = Base64.getEncoder().encodeToString(Random.nextBytes(16))
                socket.getOutputStream().write(
                    ("GET / HTTP/1.1\r\nHost: $host:$PORT\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray()
                )
                val input = DataInputStream(socket.getInputStream().buffered())
                val headers = StringBuilder()
                while (!headers.endsWith("\r\n\r\n")) headers.append(input.readUnsignedByte().toChar())
                if (!headers.startsWith("HTTP/1.1 101")) throw IOException("no WebSocket upgrade: ${headers.lineSequence().first()}")
                return CrealitySocket(socket, input)
            } catch (e: IOException) {
                socket.close()
                throw e
            }
        }
    }
}
