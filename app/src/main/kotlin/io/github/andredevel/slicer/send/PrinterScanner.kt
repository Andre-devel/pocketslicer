package io.github.andredevel.slicer.send

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.util.Log
import io.github.andredevel.slicer.send.Http.resolve
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import kotlin.random.Random

/**
 * Impressora achada na rede. [name]: o modelo, como a própria impressora informa; [kind]: protocolo
 * de envio, ou null quando ela não tem um que o app suporte.
 */
data class FoundPrinter(val address: String, val name: String, val kind: Kind?)

/**
 * Procura impressoras na sub-rede do Wi-Fi: Moonraker na porta 7125, a interface da Creality na
 * 9999 e o anúncio SSDP das Bambu na porta UDP 2021. O modelo vem só do que a impressora responde,
 * nunca de palpite (tamanho da mesa, nome de arquivo...).
 */
object PrinterScanner {
    private const val TAG = "PrinterScanner"
    private const val MOONRAKER_PORT = 7125
    private const val CREALITY_PORT = 9999
    private const val BAMBU_SSDP_PORT = 2021
    private const val CONNECT_TIMEOUT_MS = 500
    private const val PARALLEL = 64
    /** As Bambu anunciam a cada poucos segundos; a varredura das portas cabe nesse tempo. */
    private const val SSDP_LISTEN_MS = 6_000
    private const val CREALITY_READ_MS = 3_000

    /** `printerIntName` do `machineList.json` do Creality Print; F039 é o que a K3 informa. */
    private val CREALITY_MODELS = mapOf(
        "F008" to "Creality K2 Plus",
        "F012" to "Creality K2 Pro",
        "F016" to "Creality K2 SE",
        "F021" to "Creality K2",
        "F039" to "Creality K3",
    )

    /** `model_id` dos perfis BBL do Orca, que as Bambu anunciam em `DevModel.bambu.com`. */
    private val BAMBU_MODELS = mapOf(
        "N2S" to "Bambu Lab A1",
        "N1" to "Bambu Lab A1 mini",
        "C11" to "Bambu Lab P1P",
        "C12" to "Bambu Lab P1S",
        "N7" to "Bambu Lab P2S",
        "BL-P001" to "Bambu Lab X1 Carbon",
        "BL-P002" to "Bambu Lab X1",
        "C13" to "Bambu Lab X1E",
        "O1D" to "Bambu Lab H2D",
        "O1E" to "Bambu Lab H2D Pro",
        "O1S" to "Bambu Lab H2S",
        "N6" to "Bambu Lab X2D",
    )

    /** Vazio sem Wi-Fi (ou rede sem IPv4). */
    suspend fun scan(context: Context): List<FoundPrinter> = coroutineScope {
        val hosts = subnetHosts(context)
        if (hosts.isEmpty()) return@coroutineScope emptyList()
        val bambu = async { listenBambu(context) }
        val gate = Semaphore(PARALLEL)
        val probed = hosts.map { ip ->
            async(Dispatchers.IO) {
                val moonraker = gate.withPermit { isOpen(ip, MOONRAKER_PORT) }
                val creality = gate.withPermit { isOpen(ip, CREALITY_PORT) }
                identify(ip, moonraker, creality)
            }
        }.awaitAll().filterNotNull()
        val found = probed.associateBy { it.address }.toMutableMap()
        for (printer in bambu.await()) found.putIfAbsent(printer.address, printer)
        found.values.sortedBy { address -> address.address.substringAfterLast('.').toIntOrNull() ?: 0 }
    }

    /**
     * Os IPs da sub-rede do Wi-Fi, sem o do próprio aparelho; redes maiores que /24 ficam no /24
     * em volta dele, para não varrer milhares de endereços.
     */
    private fun subnetHosts(context: Context): List<String> {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val link = connectivity.getLinkProperties(connectivity.activeNetwork) ?: return emptyList()
        val own = link.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return emptyList()
        val bytes = own.address.address
        val ip = bytes.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
        val prefix = own.prefixLength.coerceIn(24, 30)
        val size = 1L shl (32 - prefix)
        val network = ip and (size - 1).inv()
        return (1 until size - 1).map { network + it }.filter { it != ip }.map { n ->
            "${n shr 24 and 0xff}.${n shr 16 and 0xff}.${n shr 8 and 0xff}.${n and 0xff}"
        }
    }

    private fun isOpen(ip: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS) }
        true
    } catch (e: IOException) {
        false
    }

    private suspend fun identify(ip: String, moonraker: Boolean, creality: Boolean): FoundPrinter? {
        if (!moonraker && !creality) return null
        val kind = if (moonraker) Kind.MOONRAKER else null
        val name = (if (creality) crealityModel(ip) else null)
            ?: (if (moonraker) moonrakerModel(ip) else null)
            ?: if (creality) "Creality" else "Klipper"
        return FoundPrinter(ip, name, kind)
    }

    /** `product_info.machine_type` (Snapmaker U1...) ou, sem ele, o hostname do Klipper. */
    private suspend fun moonrakerModel(ip: String): String? {
        val base = Http.baseUrl("$ip:$MOONRAKER_PORT")
        return try {
            val info = Http.get(base.resolve("/machine/system_info"), emptyMap())
            JSONObject(info.body).optJSONObject("result")?.optJSONObject("system_info")
                ?.optJSONObject("product_info")?.optString("machine_type")?.takeIf { it.isNotBlank() }
                ?: JSONObject(Http.get(base.resolve("/printer/info"), emptyMap()).body)
                    .optJSONObject("result")?.optString("hostname")?.takeIf { it.isNotBlank() }
        } catch (e: SendException) {
            null
        } catch (e: JSONException) {
            null
        }
    }

    /**
     * A interface da Creality (WebSocket na 9999) manda o estado em JSON logo ao conectar, com o
     * código do modelo em `model`. Código desconhecido aparece como está.
     */
    private fun crealityModel(ip: String): String? = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, CREALITY_PORT), CONNECT_TIMEOUT_MS)
            socket.soTimeout = CREALITY_READ_MS
            val key = Base64.getEncoder().encodeToString(Random.nextBytes(16))
            socket.getOutputStream().write(
                ("GET / HTTP/1.1\r\nHost: $ip:$CREALITY_PORT\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray()
            )
            val input = DataInputStream(socket.getInputStream().buffered())
            if (!readHeaders(input).startsWith("HTTP/1.1 101")) return null
            val deadline = System.currentTimeMillis() + CREALITY_READ_MS
            while (System.currentTimeMillis() < deadline) {
                val text = readTextFrame(input) ?: continue
                val code = try {
                    JSONObject(text).optString("model")
                } catch (e: JSONException) {
                    ""
                }
                if (code.isNotEmpty()) return CREALITY_MODELS[code] ?: "Creality ($code)"
            }
            null
        }
    } catch (e: IOException) {
        null
    }

    private fun readHeaders(input: DataInputStream): String {
        val text = StringBuilder()
        while (!text.endsWith("\r\n\r\n")) text.append(input.readUnsignedByte().toChar())
        return text.toString()
    }

    /** Um quadro do servidor (sem máscara); null quando não é texto. */
    private fun readTextFrame(input: DataInputStream): String? {
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

    /**
     * Anúncios SSDP das Bambu (`DevModel.bambu.com`); o multicast lock faz o Wi-Fi entregar os
     * broadcasts, que o Android costuma filtrar.
     */
    private suspend fun listenBambu(context: Context): List<FoundPrinter> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi.createMulticastLock(TAG).apply { setReferenceCounted(false); acquire() }
        val found = mutableMapOf<String, FoundPrinter>()
        try {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.broadcast = true
                socket.bind(InetSocketAddress(BAMBU_SSDP_PORT))
                val deadline = System.currentTimeMillis() + SSDP_LISTEN_MS
                val buffer = ByteArray(4096)
                while (true) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) break
                    socket.soTimeout = left.toInt()
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        break
                    }
                    val headers = String(packet.data, 0, packet.length).lines()
                        .mapNotNull { line -> line.split(':', limit = 2).takeIf { it.size == 2 } }
                        .associate { (key, value) -> key.trim().lowercase() to value.trim() }
                    val model = headers["devmodel.bambu.com"] ?: continue
                    val ip = packet.address.hostAddress ?: continue
                    found[ip] = FoundPrinter(ip, BAMBU_MODELS[model] ?: "Bambu Lab ($model)", Kind.BAMBU_LAN)
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "SSDP listen failed", e)
        } finally {
            lock.release()
        }
        found.values.toList()
    }
}
