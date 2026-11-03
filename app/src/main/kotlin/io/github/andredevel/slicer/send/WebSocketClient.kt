package io.github.andredevel.slicer.send

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

/**
 * Cliente WebSocket mínimo para as impressoras da rede local: mensagens JSON em quadros de texto.
 * Serve a interface da Creality na porta [CREALITY_PORT] (K2, K3), que manda o estado em JSON a
 * qualquer momento (`{"method": "get", "params": {"boxsInfo": 1}}` pede uma parte), e o
 * `/websocket` do Moonraker (JSON-RPC). Responde ao ping do servidor; o fechamento pelo servidor
 * vira IOException. Bloqueante; quem chama fica no Dispatchers.IO.
 */
internal class WebSocketClient private constructor(private val socket: Socket, private val input: DataInputStream) : Closeable {

    /** Manda [message] num quadro de texto mascarado, como o cliente deve. */
    fun send(message: JSONObject) = sendFrame(OPCODE_TEXT, message.toString().toByteArray())

    /** `get` de [key] na interface da Creality; devolve a primeira mensagem que traz [key] em até [timeoutMs], ou null. */
    fun get(key: String, timeoutMs: Int): JSONObject? {
        send(JSONObject().put("method", "get").put("params", JSONObject().put(key, 1)))
        return awaitMessage(timeoutMs) { it.has(key) }
    }

    /** Primeira mensagem JSON em até [timeoutMs] que atende [match], ou null. */
    fun awaitMessage(timeoutMs: Int, match: (JSONObject) -> Boolean): JSONObject? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val message = receive((deadline - System.currentTimeMillis()).toInt()) ?: return null
            if (match(message)) return message
        }
        return null
    }

    /**
     * A próxima mensagem JSON em até [timeoutMs], ou null se nada chegou nesse tempo. Quadros
     * que não são JSON são pulados. O prazo só vale para o começo de um quadro: depois do
     * primeiro byte, o resto da mensagem tem [FRAME_TIMEOUT_MS], e estourar esse prazo no meio
     * dela é falha da conexão (IOException), porque o fluxo ficaria no meio do quadro.
     */
    fun receive(timeoutMs: Int): JSONObject? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            socket.soTimeout = left.toInt()
            val first = try {
                input.readUnsignedByte()
            } catch (e: SocketTimeoutException) {
                return null
            }
            socket.soTimeout = FRAME_TIMEOUT_MS
            val text = try {
                readMessage(first)
            } catch (e: SocketTimeoutException) {
                throw IOException("timed out in the middle of a frame", e)
            } ?: continue
            try {
                return JSONObject(text)
            } catch (e: JSONException) {
                continue
            }
        }
    }

    /** Ping do cliente; a resposta (pong) não é mensagem, mas atualiza [lastFrameAt]. */
    fun ping() = sendFrame(OPCODE_PING, ByteArray(0))

    /** Quando chegou o último quadro (System.currentTimeMillis), de qualquer tipo. */
    @Volatile
    var lastFrameAt = System.currentTimeMillis()
        private set

    override fun close() = socket.close()

    private fun sendFrame(opcode: Int, data: ByteArray) {
        val payload = data.copyOf()
        val mask = Random.nextBytes(4)
        val first = (0x80 or opcode).toByte()
        val head = when {
            payload.size < 126 -> byteArrayOf(first, (0x80 or payload.size).toByte())
            payload.size < 65_536 -> byteArrayOf(first, (0x80 or 126).toByte(), (payload.size shr 8).toByte(), payload.size.toByte())
            else -> throw IOException("message too large: ${payload.size}")
        }
        for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        synchronized(socket) { socket.getOutputStream().apply { write(head + mask + payload); flush() } }
    }

    /**
     * Uma mensagem a partir do quadro cujo primeiro byte é [firstByte], juntando as continuações;
     * null se não for texto ou se o quadro for de controle fora de uma mensagem. Quadros de
     * controle no meio da mensagem são tratados aqui.
     */
    private fun readMessage(firstByte: Int): String? {
        val message = ByteArrayOutputStream()
        var text = false
        var started = false
        var first = firstByte
        while (true) {
            val opcode = first and 0x0f
            val second = input.readUnsignedByte()
            val length = when (val small = second and 0x7f) {
                126 -> input.readUnsignedShort().toLong()
                127 -> input.readLong()
                else -> small.toLong()
            }
            if (length < 0 || length > MAX_MESSAGE || message.size() + length > MAX_MESSAGE) throw IOException("bad frame length: $length")
            val mask = if (second and 0x80 != 0) ByteArray(4).also(input::readFully) else null
            val payload = ByteArray(length.toInt()).also(input::readFully)
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            lastFrameAt = System.currentTimeMillis()
            when (opcode) {
                OPCODE_CLOSE -> throw IOException("closed by the server")
                OPCODE_PING, OPCODE_PONG -> {
                    if (opcode == OPCODE_PING) sendFrame(OPCODE_PONG, payload)
                    if (!started) return null
                    first = input.readUnsignedByte()
                    continue
                }
                OPCODE_TEXT -> text = true
                OPCODE_CONTINUATION -> {}
                else -> text = false
            }
            started = true
            message.write(payload)
            if (first and 0x80 != 0) return if (text) message.toString(Charsets.UTF_8.name()) else null
            first = input.readUnsignedByte()
        }
    }

    companion object {
        /** A interface da Creality (K2, K3). */
        const val CREALITY_PORT = 9999
        private const val MAX_MESSAGE = 4L shl 20
        /** Prazo para o resto de uma mensagem depois que o primeiro byte chegou. */
        private const val FRAME_TIMEOUT_MS = 10_000
        private const val OPCODE_CONTINUATION = 0
        private const val OPCODE_TEXT = 1
        private const val OPCODE_CLOSE = 8
        private const val OPCODE_PING = 9
        private const val OPCODE_PONG = 10

        /** A interface da Creality em [host]; falha com IOException se ela não existir. */
        fun creality(host: String, connectTimeoutMs: Int) = open(host, CREALITY_PORT, "/", emptyMap(), connectTimeoutMs, tls = false)

        /**
         * Abre `ws://[host]:[port][path]` (`wss://` com [tls]) com os cabeçalhos [headers];
         * falha com IOException se não houver WebSocket ali.
         */
        fun open(host: String, port: Int, path: String, headers: Map<String, String>, connectTimeoutMs: Int, tls: Boolean): WebSocketClient {
            var socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                if (tls) socket = secure(socket, host, port)
                socket.soTimeout = connectTimeoutMs
                val key = Base64.getEncoder().encodeToString(Random.nextBytes(16))
                val extra = headers.entries.joinToString("") { (name, value) -> "$name: $value\r\n" }
                socket.getOutputStream().write(
                    ("GET $path HTTP/1.1\r\nHost: $host:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n$extra\r\n").toByteArray()
                )
                val input = DataInputStream(socket.getInputStream().buffered())
                val response = StringBuilder()
                while (!response.endsWith("\r\n\r\n")) response.append(input.readUnsignedByte().toChar())
                if (!response.startsWith("HTTP/1.1 101")) throw WebSocketRefused(response.lineSequence().first())
                return WebSocketClient(socket, input)
            } catch (e: IOException) {
                socket.close()
                throw e
            }
        }

        /**
         * [plain] embrulhado em TLS, com o nome do certificado conferido contra [host]: o SSLSocket
         * cru só valida a cadeia, e sem isso qualquer certificado válido de outro site passaria.
         */
        private fun secure(plain: Socket, host: String, port: Int): Socket {
            val socket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, port, true) as SSLSocket
            try {
                socket.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, socket.session)) {
                    throw SSLPeerUnverifiedException("certificate does not match $host")
                }
            } catch (e: IOException) {
                socket.close()
                throw e
            }
            return socket
        }
    }

    /** O servidor respondeu sem trocar para WebSocket; [status] é a primeira linha da resposta. */
    class WebSocketRefused(val status: String) : IOException("no WebSocket upgrade: $status")
}
