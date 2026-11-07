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

    /**
     * O que a aba Controle pode mexer nesta impressora, pelo que ela informa ter; null se o
     * protocolo não tem controle. Falha com [SendException].
     */
    suspend fun controls(host: PrinterHost): PrinterControls? = null

    /**
     * Carrega ([load]) ou descarrega [slot] e volta só quando a impressora terminou; falha com
     * [SendException] se ela recusar, mostrar erro ou não estiver livre. Só com
     * [FilamentControls.loadUnload].
     */
    suspend fun changeFilament(host: PrinterHost, slot: Slot, load: Boolean): Unit = throw UnsupportedOperationException()

    /**
     * Roda [task] e volta só quando a impressora terminou (também depois do reinício do firmware
     * que algumas fazem para salvar o resultado); falha com [SendException] se ela recusar, der
     * erro ou não estiver livre. Só as de [PrinterControls.maintenance].
     */
    suspend fun runMaintenance(host: PrinterHost, task: Maintenance): Unit = throw UnsupportedOperationException()

    /** Se a impressora lista os arquivos guardados nela ([files], [fileInfo], [deleteFile], [startFile], [history]). */
    val hasFiles: Boolean get() = false

    /** Arquivos guardados na impressora, o mais novo primeiro; falha com [SendException]. */
    suspend fun files(host: PrinterHost): List<PrinterFile> = throw UnsupportedOperationException()

    /** Miniatura, tempo e filamentos de [file], lidos do próprio arquivo; falha com [SendException]. */
    suspend fun fileInfo(host: PrinterHost, file: PrinterFile): GcodeInfo = throw UnsupportedOperationException()

    /** Apaga [file] da impressora; falha com [SendException]. Só com [hasFiles]. */
    suspend fun deleteFile(host: PrinterHost, file: PrinterFile): Unit = throw UnsupportedOperationException()

    /**
     * Inicia [path], já na impressora, como o `start` de [upload] (com o mesmo [mapping]):
     * confere antes que ela está livre. Só com [hasFiles].
     */
    suspend fun startFile(host: PrinterHost, path: String, mapping: List<Slot?>?): Unit = throw UnsupportedOperationException()

    /** Últimas impressões e totais; null se o protocolo não guarda. Falha com [SendException]. */
    suspend fun history(host: PrinterHost): PrintHistory? = null

    /** Se a impressora tem console de G-code ([console], [sendGcode], [gcodeCommands]). */
    val hasConsole: Boolean get() = false

    /** As últimas [count] linhas do console (comandos e respostas), a mais antiga primeiro; falha com [SendException]. */
    suspend fun console(host: PrinterHost, count: Int): List<ConsoleLine> = throw UnsupportedOperationException()

    /**
     * Manda [script] (G-code livre, uma ou mais linhas) e volta quando a impressora o executou;
     * falha com [SendException] com a resposta dela. Só com [hasConsole].
     */
    suspend fun sendGcode(host: PrinterHost, script: String): Unit = throw UnsupportedOperationException()

    /** Os comandos que a impressora informa ter, com a descrição de cada um; falha com [SendException]. */
    suspend fun gcodeCommands(host: PrinterHost): Map<String, String> = throw UnsupportedOperationException()
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

    /** Home dos eixos de [axes] ("X", "XY"...); vazio = todos. */
    data class Home(val axes: String = "") : PrinterCommand

    /** Move o eixo [axis] ('X', 'Y' ou 'Z') [distance] mm a partir de onde está. */
    data class Jog(val axis: Char, val distance: Float) : PrinterCommand

    /** Desliga os motores; os eixos perdem o home. */
    data object MotorsOff : PrinterCommand

    /** Alvo de [heater] em °C; 0 desliga. */
    data class SetTemperature(val heater: Heater, val target: Float) : PrinterCommand

    /** Velocidade de [fan] ([Fan.id]) de 0 a 1. */
    data class SetFan(val fan: String, val speed: Float) : PrinterCommand

    /** Liga ou desliga [light] ([Light.id]). */
    data class SetLight(val light: String, val on: Boolean) : PrinterCommand

    /** Velocidade da impressão em % da do G-code. */
    data class SetSpeed(val percent: Int) : PrinterCommand

    /** Fluxo (extrusão) em % do G-code. */
    data class SetFlow(val percent: Int) : PrinterCommand

    /** Modo de impressão da Creality ([Adjustments.printModes]). */
    data class SetPrintMode(val mode: String) : PrinterCommand

    /** Soma [delta] mm ao deslocamento Z (babystep), com o bico indo junto. */
    data class AdjustZOffset(val delta: Float) : PrinterCommand

    /** Pausa quando a impressão chegar na camada [layer] (a partir de 1); null desfaz. */
    data class PauseAtLayer(val layer: Int?) : PrinterCommand

    /** Lê de novo a etiqueta RFID do filamento em [slot] ([FilamentControls.refresh]). */
    data class RefreshSlot(val slot: Slot) : PrinterCommand

    /** Cancela o objeto [name] ([PrintObject.name]) da impressão em andamento; não tem volta. */
    data class ExcludeObject(val name: String) : PrinterCommand
}

/**
 * O que a tela da impressora pode mexer ([Uploader.controls]). Aba Controle: [motion] = home,
 * mover os eixos e desligar os motores; os aquecedores, ventoinhas e luzes que dá para mudar.
 * [adjustments]: os ajustes ao vivo da impressão, na Visão geral; [filament]: o que dá para
 * fazer com as posições de filamento; [excludeObject]: cancelar um objeto da impressão
 * ([PrinterStatus.objects]); [maintenance]: as calibrações e a limpeza da aba Manutenção.
 */
data class PrinterControls(
    val motion: Boolean = false,
    val heaters: List<Heater> = emptyList(),
    val fans: List<Fan> = emptyList(),
    val lights: List<Light> = emptyList(),
    val adjustments: Adjustments = Adjustments(),
    val filament: FilamentControls = FilamentControls(),
    val excludeObject: Boolean = false,
    val maintenance: List<Maintenance> = emptyList(),
) {
    /** Se a aba Controle tem o que mostrar. */
    val hasControlTab get() = motion || heaters.isNotEmpty() || fans.isNotEmpty() || lights.isNotEmpty()
}

/**
 * Ajustes durante a impressão: velocidade e fluxo em %, os modos de impressão da Creality
 * ([printModes], como `SET_PRINT_MODE` os nomeia: "standard", "silent"...), babystep Z e pausar
 * numa camada.
 */
data class Adjustments(
    val speed: Boolean = false,
    val flow: Boolean = false,
    val printModes: List<String> = emptyList(),
    val zOffset: Boolean = false,
    val pauseAtLayer: Boolean = false,
) {
    val isEmpty get() = !speed && !flow && printModes.isEmpty() && !zOffset && !pauseAtLayer
}

/**
 * Posições de filamento: [loadUnload] = carregar e descarregar cada uma ([Uploader.changeFilament]);
 * [refresh] = ler de novo a etiqueta RFID ([PrinterCommand.RefreshSlot]).
 */
data class FilamentControls(val loadUnload: Boolean = false, val refresh: Boolean = false) {
    val isEmpty get() = !loadUnload && !refresh
}

/**
 * Calibração ou manutenção que a impressora faz sozinha ([Uploader.runMaintenance]), pela macro
 * do fabricante. [bed]: o bico desce na mesa, que precisa estar livre; [saves]: grava o
 * resultado na configuração da impressora.
 */
enum class Maintenance(val bed: Boolean, val saves: Boolean) {
    BED_LEVELING(bed = true, saves = true),
    Z_AXIS(bed = true, saves = false),
    INPUT_SHAPING(bed = false, saves = true),
    NOZZLE_PID(bed = false, saves = true),
    BED_PID(bed = false, saves = true),
    NOZZLE_CLEAN(bed = true, saves = false),
}

/** Linha do console ([Uploader.console]): [command] = o que foi mandado, senão a resposta; [time] em ms do relógio. */
data class ConsoleLine(val message: String, val time: Long, val command: Boolean)

/** Aquecedor que dá para ligar, como em [Temperature] ([source], [index]); [max] em °C, quando a impressora informa. */
data class Heater(val source: Temperature.Source, val index: Int = 0, val max: Float? = null) {
    /** Se [temperature] é a deste aquecedor. */
    fun matches(temperature: Temperature) = temperature.source == source && temperature.index == index
}

/** Ventoinha que dá para ajustar: [id] para [PrinterCommand.SetFan] e [PrinterStatus.fans]; [name] quando é [Kind.OTHER]. */
data class Fan(val id: String, val kind: Kind, val name: String = id) {
    enum class Kind { PART, AUXILIARY, CHAMBER, OTHER }
}

/** Luz que dá para ligar: [id] para [PrinterCommand.SetLight] e [PrinterStatus.lights]. */
data class Light(val id: String, val name: String)

/**
 * Estado ao vivo de uma impressora ([Uploader.status]). [progress] de 0 a 1 e [file] quando há
 * impressão; [raw]: o estado como a impressora o informa ("printing", "RUNNING"...). Quando a
 * impressora informa: [temperatures], [layer] de [layers], [elapsed] e [remaining] em segundos
 * (só durante a impressão) e as caixas de filamento ([boxes]). [alert]: erro que a impressora
 * mostra na tela dela, como o objeto estranho na mesa da detecção por IA da Creality. [halted]: o
 * Klipper parou (parada de emergência, erro do firmware) e só volta com
 * [PrinterCommand.FirmwareRestart]; [raw] traz a mensagem dele. Só no estado ao vivo
 * ([Uploader.liveStatus]), para a aba Controle: [homedAxes] ("xyz", vazio sem home),
 * [position] XYZ em mm, a velocidade de cada ventoinha ([Fan.id], 0 a 1) e se cada luz está
 * acesa ([Light.id]); para os [Adjustments], [speed] e [flow] em %, [printMode], [zOffset] em mm
 * e a camada de [pauseAtLayer] (null sem pausa marcada); os [objects] da impressão em andamento
 * (vazia se o G-code não os define) e a área da mesa [bed], para desenhá-los.
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
    val homedAxes: String? = null,
    val position: List<Float>? = null,
    val fans: Map<String, Float> = emptyMap(),
    val lights: Map<String, Boolean> = emptyMap(),
    val speed: Int? = null,
    val flow: Int? = null,
    val printMode: String? = null,
    val zOffset: Float? = null,
    val pauseAtLayer: Int? = null,
    val objects: List<PrintObject> = emptyList(),
    val bed: BedArea? = null,
) {
    enum class State { IDLE, PRINTING, PAUSED, ERROR, OFFLINE }

    /** Imprimindo ou pausada: dá para enviar, não para iniciar outra impressão. */
    val busy get() = state == State.PRINTING || state == State.PAUSED
}

/**
 * Objeto da impressão em andamento (`EXCLUDE_OBJECT_DEFINE` do G-code): [polygon] e [center] XY
 * em mm na mesa, quando o G-code os traz; [excluded] = cancelado; [current] = o que está sendo
 * impresso agora.
 */
data class PrintObject(
    val name: String,
    val polygon: List<Pair<Float, Float>> = emptyList(),
    val center: Pair<Float, Float>? = null,
    val excluded: Boolean = false,
    val current: Boolean = false,
)

/** Área XY em mm onde o bico alcança, para desenhar a mesa. */
data class BedArea(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

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
data class FilamentBox(
    val name: String, val humidity: Int? = null, val humidityLevel: Int? = null, val temperature: Float? = null, val unit: Int? = null,
) {
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
 * Snapmaker U1, ou a posição [cfsSlot] (0 = A) da caixa [cfsBox] do CFS da Creality (ou o suporte
 * de carretel externo dela, [CREALITY_EXTERNAL]). [loaded]
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
) {
    /** A caixa de filamento ([FilamentBox.unit]) em que a posição fica; null fora de uma (carretel externo, cabeça). */
    val unit: Int? get() = cfsBox?.takeIf { it != CREALITY_EXTERNAL } ?: qidiSlot?.takeIf { it != QIDI_EXTERNAL }?.div(4) ?: bambuAms?.takeIf { it < BAMBU_EXTERNAL }
}

/** [Slot.cfsBox] do suporte de carretel externo da Creality (caixa 0, de tipo 1, no `boxsInfo`). */
const val CREALITY_EXTERNAL = 0

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
