package io.github.andredevel.slicer.send

import androidx.annotation.StringRes
import io.github.andredevel.slicer.R
import java.io.File

/** Protocolo de envio do G-code (docs/plano-envio.md). */
enum class Kind(@StringRes val label: Int) {
    /** Klipper com Moonraker: Qidi, Snapmaker U1, Voron... */
    MOONRAKER(R.string.kind_moonraker),
}

/**
 * Impressora salva pelo usuário. [address]: IP ou nome, com porta e `http://` opcionais;
 * [secret]: API key do Moonraker, quando exigida; [preset]: perfil de impressora com que foi
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
    /** Confere que a impressora responde pelo protocolo; falha com [SendException]. */
    suspend fun test(host: PrinterHost)

    /**
     * Envia [file] com o nome [name]; com [start], confere antes que a impressora está livre e
     * depois inicia a impressão dele. [progress] de 0 a 1 durante o envio.
     */
    suspend fun upload(host: PrinterHost, file: File, name: String, start: Boolean, progress: (Float) -> Unit)
}

fun uploader(kind: Kind): Uploader = when (kind) {
    Kind.MOONRAKER -> MoonrakerUploader
}

/** Falha do envio com mensagem para o usuário ([text] com [args]); [cause] vai para o log. */
class SendException(@StringRes val text: Int, vararg val args: Any, cause: Throwable? = null) : Exception(cause)
