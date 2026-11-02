package io.github.andredevel.slicer.send

import android.content.res.AssetManager
import androidx.annotation.StringRes
import io.github.andredevel.slicer.R
import kotlinx.coroutines.flow.Flow
import java.io.File

/** Protocolo de envio do G-code (docs/plano-envio.md). */
enum class Kind(@StringRes val label: Int, @StringRes val shortLabel: Int, @StringRes val description: Int) {
    /** Klipper com Moonraker: Qidi, Snapmaker U1, Voron... */
    MOONRAKER(R.string.kind_moonraker, R.string.kind_moonraker_short, R.string.kind_moonraker_description),
    /** Bambu Lab pela rede local (FTPS, código de acesso). */
    BAMBU_LAN(R.string.kind_bambu, R.string.kind_bambu_short, R.string.kind_bambu_description),
}

/**
 * Impressora salva pelo usuário. [address]: IP ou nome, com porta e `http://` opcionais;
 * [secret]: API key do Moonraker, quando exigida, ou código de acesso da Bambu; [preset]: perfil de impressora com que foi
 * cadastrada, para vir escolhida quando esse perfil estiver em uso. [identity]: o que identifica a
 * impressora sem o endereço ([Uploader.identity]), para reencontrá-la na rede quando o IP muda;
 * vazio até a primeira conexão.
 */
data class PrinterHost(
    val id: String,
    val name: String,
    val kind: Kind,
    val address: String,
    val secret: String? = null,
    val preset: String? = null,
    val identity: Set<String> = emptySet(),
) {
    /** IP ou nome de [address], sem esquema e porta; null se o endereço não vale. */
    val hostName: String? get() = runCatching { Http.baseUrl(address).host }.getOrNull()

    /** Se [found] é esta impressora: mesmo protocolo e alguma identidade em comum. */
    fun matches(found: FoundPrinter) = found.kind == kind && identity.any { it in found.identity }

    /** Este endereço com o IP (ou nome) trocado por [newHost], mantendo esquema e porta. */
    fun movedTo(newHost: String): PrinterHost {
        val text = address.trim()
        val scheme = text.substringBefore("://", "").let { if (it.isEmpty()) "" else "$it://" }
        val rest = text.removePrefix(scheme)
        val authority = rest.substringBefore('/')
        val port = authority.substringAfterLast(']').substringAfter(':', "").let { if (it.isEmpty()) "" else ":$it" }
        return copy(address = scheme + newHost + port + rest.substring(authority.length))
    }
}

interface Uploader {
    /** Se [upload] pode iniciar a impressão (`start`). */
    val canStart: Boolean

    /** Confere que a impressora responde pelo protocolo; falha com [SendException]. */
    suspend fun test(host: PrinterHost): Connection

    /**
     * Envia [file] com o nome [name]; com [start], confere antes que a impressora está livre e
     * depois inicia a impressão dele. [progress] de 0 a 1 durante o envio; [starting] quando o
     * arquivo chegou e o pedido de iniciar vai sair. [mapping]: posição de [slots] para cada
     * filamento do projeto (pelo índice; null nos que não são usados), só com [start] e numa
     * impressora que mapeia.
     */
    suspend fun upload(
        host: PrinterHost, file: File, name: String, start: Boolean, progress: (Float) -> Unit,
        starting: () -> Unit = {}, mapping: List<Slot?>? = null,
    )

    /**
     * Filamento em cada posição da impressora (cabeça, slot), para escolher onde vai cada
     * filamento do projeto ao iniciar; vazia se a impressora não aceita esse mapeamento.
     */
    suspend fun slots(host: PrinterHost): List<Slot> = emptyList()

    /**
     * Identidade que não muda com o IP: os MACs das interfaces de rede do Moonraker (ou, sem eles,
     * o hostname do Klipper) e o serial da Bambu; a mesma que o [PrinterScanner] lê de cada
     * impressora achada. Falha com [SendException].
     */
    suspend fun identity(host: PrinterHost): Set<String>

    /** Estado atual da impressora; falha com [SendException]. */
    suspend fun status(host: PrinterHost): PrinterStatus

    /**
     * Estado ao vivo, a cada mudança, enquanto coletado; null se o protocolo não tem (fica o
     * [status] periódico). O fluxo falha com [SendException] quando a conexão cai e termina
     * quando o firmware reinicia; quem coleta volta ao [status] e tenta de novo depois.
     */
    fun liveStatus(host: PrinterHost): Flow<PrinterStatus>? = null

    /** Câmera da impressora, null se ela não tem (ou o app não sabe mostrar); falha com [SendException]. */
    suspend fun camera(host: PrinterHost): Camera? = null

    /** Se [Uploader.command] sabe mandar [command] para esta impressora. */
    fun supports(command: PrinterCommand): Boolean = false

    /**
     * Manda [command] e volta quando a impressora o aceitou (não quando terminou); falha com
     * [SendException]. Só os que [supports] aceita.
     */
    suspend fun command(host: PrinterHost, command: PrinterCommand): Unit = throw UnsupportedOperationException()
}

/** O que o app manda a impressora fazer ([Uploader.command]); cada protocolo traduz. */
sealed interface PrinterCommand {
    /** Pausa a impressão em andamento. */
    data object Pause : PrinterCommand

    /** Continua a impressão pausada. */
    data object Resume : PrinterCommand

    /** Para a impressão em andamento; não tem volta. */
    data object Stop : PrinterCommand

    /** Corta motores e aquecimento na hora; o Klipper fica parado ([PrinterStatus.halted]). */
    data object EmergencyStop : PrinterCommand

    /** Reinicia o firmware do Klipper, para sair de [PrinterStatus.halted]. */
    data object FirmwareRestart : PrinterCommand
}

/**
 * Estado ao vivo de uma impressora ([Uploader.status]). [progress] de 0 a 1 e [file] quando há
 * impressão; [raw]: o estado como a impressora o informa ("printing", "RUNNING"...). Quando a
 * impressora informa: [temperatures], [layer] de [layers], [elapsed] e [remaining] em segundos
 * (só durante a impressão) e as caixas de filamento ([boxes]). [alert]: erro que a impressora
 * mostra na tela dela, como o objeto estranho na mesa da detecção por IA da Creality. [halted]: o
 * Klipper parou (parada de emergência, erro do firmware) e só volta com
 * [PrinterCommand.FirmwareRestart]; [raw] traz a mensagem dele.
 */
data class PrinterStatus(
    val state: State,
    val progress: Float? = null,
    val file: String? = null,
    val raw: String? = null,
    val temperatures: List<Temperature> = emptyList(),
    val layer: Int? = null,
    val layers: Int? = null,
    val elapsed: Long? = null,
    val remaining: Long? = null,
    val boxes: List<FilamentBox> = emptyList(),
    val alert: PrinterAlert? = null,
    val halted: Boolean = false,
) {
    enum class State { IDLE, PRINTING, PAUSED, ERROR, OFFLINE }

    /** Imprimindo ou pausada: dá para enviar, não para iniciar outra impressão. */
    val busy get() = state == State.PRINTING || state == State.PAUSED
}

/**
 * Erro na tela da impressora: [code] como o app da Creality o mostra ("AC0104"; só o número,
 * "0104", quando o app não conhece o prefixo do módulo) e [number], o número dele, para o texto.
 */
data class PrinterAlert(val code: String, val number: Int)

/** Temperatura em °C de um aquecedor ou sensor; [index] separa os bicos (0 = o primeiro); [target] 0 = desligado. */
data class Temperature(val source: Source, val current: Float, val target: Float? = null, val index: Int = 0) {
    enum class Source { NOZZLE, BED, CHAMBER }
}

/**
 * Caixa de filamento (CFS, AMS) com sensor: [humidity] em %, ou só o nível [humidityLevel] do AMS
 * da Bambu (1 úmido a 5 seco, como o Bambu Studio o mostra); [temperature] em °C.
 */
data class FilamentBox(val name: String, val humidity: Int? = null, val humidityLevel: Int? = null, val temperature: Float? = null) {
    /** Úmida a ponto de avisar: [HUMID_PERCENT] ou mais, ou nível 1 e 2 (60% ou mais no Bambu Studio). */
    val humid get() = humidity?.let { it >= HUMID_PERCENT } ?: humidityLevel?.let { it in 1..2 } ?: false
}

/** Umidade da caixa de filamento a partir da qual o app avisa. */
const val HUMID_PERCENT = 60

/** Como mostrar a câmera de uma impressora ([Uploader.camera]). */
sealed interface Camera {
    /**
     * Imagem pela URL: [url] é um fluxo MJPEG, ou com [snapshot] uma foto a recarregar [fps] vezes
     * por segundo (o `target_fps` do Moonraker); [rotation] em graus e os espelhamentos como o
     * Moonraker informa. [monitorSocket]: WebSocket do Moonraker da Snapmaker U1, onde
     * `camera.start_monitor` mantém a foto atualizada.
     */
    data class Image(
        val url: String, val snapshot: Boolean = false, val rotation: Int = 0,
        val flipHorizontal: Boolean = false, val flipVertical: Boolean = false,
        val monitorSocket: String? = null, val fps: Int = SNAPSHOT_FPS,
    ) : Camera {
        companion object {
            /** Sem `target_fps`: a foto da U1 muda a cada ~2 s. */
            const val SNAPSHOT_FPS = 1
        }
    }

    /** WebRTC da Creality (K2): oferta em base64 por POST em [signalingUrl] (`:8000/call/webrtc_local`). */
    data class CrealityWebRtc(val signalingUrl: String) : Camera
}

/**
 * Posição da impressora para filamento ([Uploader.slots]), [index] na lista dela: cabeça da
 * Snapmaker U1, ou a posição [cfsSlot] (0 = A) da caixa [cfsBox] do CFS da Creality. [loaded]
 * quando há filamento detectado; [type] como a impressora informa ("PLA") e [color] `#RRGGBB`,
 * quando ela sabe. [nozzle]: na impressora de vários bicos com CFS (K3), o bico (a partir de 1)
 * que essa posição alimenta; só o filamento desse bico (T de mesmo número menos 1) pode usá-la.
 * [qidiSlot]: na Box da QIDI, a posição como o firmware a numera (caixa * 4 + posição, 16 = o
 * carretel externo, [QIDI_EXTERNAL]). [bambuAms] e [bambuSlot]: na Bambu, a unidade (0 a 3 no AMS,
 * 128+ no AMS HT, [BAMBU_EXTERNAL] ou 255 no carretel externo) e a posição nela, como no
 * `ams_mapping2` do Orca.
 */
data class Slot(
    val index: Int,
    val loaded: Boolean,
    val type: String? = null,
    val color: String? = null,
    val cfsBox: Int? = null,
    val cfsSlot: Int = 0,
    val nozzle: Int? = null,
    val qidiSlot: Int? = null,
    val bambuAms: Int? = null,
    val bambuSlot: Int = 0,
)

/** [Slot.bambuAms] do carretel externo da Bambu de um bico (vt_tray); 255 é o principal no H2D. */
const val BAMBU_EXTERNAL = 254

/** [Slot.qidiSlot] do carretel externo da QIDI (fora da Box). */
const val QIDI_EXTERNAL = 16

/** Resultado de [Uploader.test]: serviço que respondeu (com a versão, quando há) e tempo da resposta. */
data class Connection(val service: String, val millis: Long)

/** [assets]: do app, com as CAs das impressoras Bambu (BambuUploader). */
fun uploader(kind: Kind, assets: AssetManager): Uploader = when (kind) {
    Kind.MOONRAKER -> MoonrakerUploader
    Kind.BAMBU_LAN -> BambuUploader(assets)
}

/** Falha do envio com mensagem para o usuário ([text] com [args]); [cause] vai para o log. */
class SendException(@StringRes val text: Int, vararg val args: Any, cause: Throwable? = null) : Exception(cause)
