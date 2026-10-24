package io.github.andredevel.slicer.profiles

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/** Perfis e dados do OrcaSlicer empacotados no APK (ver build.gradle.kts deste módulo). */
object OrcaResources {
    private const val ASSET = "orca-resources.zip"

    /**
     * Extrai os recursos para o armazenamento interno, se ainda não estiverem lá para
     * esta instalação do app, e devolve o diretório (o `resources_dir` do libslic3r).
     * Bloqueante: a primeira execução depois de instalar/atualizar leva alguns segundos.
     */
    fun install(context: Context): File {
        val dir = File(context.filesDir, "orca-resources")
        val stamp = File(dir, ".installed")
        // Muda a cada instalação/atualização do APK, que é quando o asset pode mudar.
        @Suppress("DEPRECATION")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (stamp.isFile && stamp.readText() == version) return dir

        dir.deleteRecursively()
        val root = dir.canonicalPath + File.separator
        ZipInputStream(context.assets.open(ASSET).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(dir, entry.name)
                if (!target.canonicalPath.startsWith(root)) throw IOException("invalid zip entry: ${entry.name}")
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile!!.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        stamp.writeText(version)
        return dir
    }
}
