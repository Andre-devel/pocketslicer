package io.github.andredevel.slicer.send

import android.content.res.AssetManager
import androidx.annotation.StringRes
import io.github.andredevel.slicer.R
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
 * cadastrada, para vir escolhida quando esse perfil estiver em uso.
 */
data class PrinterHost(
    val id: String,
    val name: String,
    val kind: Kind,
    val address: String,
    val secret: String? = null,
    val preset: String? = null,
)

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

    /** Estado atual da impressora; falha com [SendException]. */
    suspend fun status(host: PrinterHost): PrinterStatus
}

/**
 * Estado ao vivo de uma impressora ([Uploader.status]). [progress] de 0 a 1 e [file] quando há
 * impressão; [raw]: o estado como a impressora o informa ("printing", "RUNNING"...).
 */
data class PrinterStatus(val state: State, val progress: Float? = null, val file: String? = null, val raw: String? = null) {
    enum class State { IDLE, PRINTING, PAUSED, ERROR, OFFLINE }

    /** Imprimindo ou pausada: dá para enviar, não para iniciar outra impressão. */
    val busy get() = state == State.PRINTING || state == State.PAUSED
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
