package io.github.andredevel.slicer.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** Tipos de preset do OrcaSlicer. A ordem espelha `slicer::PresetKind` (native/src/engine.hpp). */
enum class PresetKind { PRINTER, FILAMENT, PROCESS }

data class SliceStats(val printTimeSeconds: Double, val filamentMm3: Double)

fun interface SliceProgressListener {
    fun onProgress(percent: Int, text: String)
}

/** Erro reportado pelo libslic3r (perfil inválido, modelo ilegível, falha ao fatiar...). */
class SlicerException(message: String) : RuntimeException(message)

/**
 * Motor de fatiamento: libslic3r do OrcaSlicer via JNI (native/src/engine.hpp).
 *
 * O motor nativo não é thread-safe, então toda chamada roda numa thread própria;
 * as funções podem ser chamadas de qualquer coroutine. Cancelar a coroutine de
 * [slice] interrompe o fatiamento.
 */
class SlicerEngine private constructor(
    private val handle: Long,
    private val dispatcher: ExecutorCoroutineDispatcher,
) : AutoCloseable {

    suspend fun vendors(): List<String> = call { nativeVendors(handle).asList() }

    /** Carrega os perfis do fabricante e seleciona a primeira impressora dele. */
    suspend fun loadVendor(vendor: String) = call { nativeLoadVendor(handle, vendor) }

    /** Impressoras do fabricante; filamentos e processos compatíveis com a seleção atual. */
    suspend fun presets(kind: PresetKind): List<String> = call { nativePresets(handle, kind.ordinal).asList() }

    suspend fun selected(kind: PresetKind): String = call { nativeSelected(handle, kind.ordinal) }

    /** Trocar a impressora seleciona o processo e o filamento padrão dela. */
    suspend fun select(kind: PresetKind, name: String) = call { nativeSelect(handle, kind.ordinal, name) }

    /** STL, OBJ ou 3MF; o formato vem da extensão do arquivo. */
    suspend fun loadModel(file: File) = call { nativeLoadModel(handle, file.absolutePath) }

    /** Fatia o modelo carregado e grava o G-code em [gcode]. [listener] roda na thread do motor. */
    suspend fun slice(gcode: File, listener: SliceProgressListener): SliceStats = coroutineScope {
        val job = async(dispatcher) { nativeSlice(handle, gcode.absolutePath, listener) }
        val result = try {
            job.await()
        } catch (e: CancellationException) {
            nativeCancel(handle) // o fatiamento nativo não é interrompível de outra forma
            throw e
        }
        SliceStats(printTimeSeconds = result[0], filamentMm3 = result[1])
    }

    override fun close() {
        dispatcher.executor.execute { nativeDestroy(handle) }
        dispatcher.close()
    }

    private suspend fun <T> call(block: () -> T): T = withContext(dispatcher) { block() }

    companion object {
        init {
            System.loadLibrary("slicer_jni")
        }

        /**
         * @param resourcesDir cópia de `resources/` do OrcaSlicer (ver módulo :profiles).
         * @param dataDir diretório gravável para os presets carregados. Contém symlinks para
         *   [resourcesDir]: não apague com File.deleteRecursively(), que os segue.
         */
        suspend fun create(resourcesDir: File, dataDir: File): SlicerEngine {
            val dispatcher = Executors.newSingleThreadExecutor { runnable ->
                // libslic3r usa recursão profunda em algumas etapas.
                Thread(null, runnable, "slicer-engine", 64L shl 20)
            }.asCoroutineDispatcher()
            return try {
                val handle = withContext(dispatcher) { nativeCreate(resourcesDir.absolutePath, dataDir.absolutePath) }
                SlicerEngine(handle, dispatcher)
            } catch (e: Throwable) {
                dispatcher.close()
                throw e
            }
        }
    }
}

// Implementadas em native/src/jni_bridge.cpp (classe JVM SlicerEngineKt).
private external fun nativeCreate(resourcesDir: String, dataDir: String): Long
private external fun nativeDestroy(handle: Long)
private external fun nativeVendors(handle: Long): Array<String>
private external fun nativeLoadVendor(handle: Long, vendor: String)
private external fun nativePresets(handle: Long, kind: Int): Array<String>
private external fun nativeSelected(handle: Long, kind: Int): String
private external fun nativeSelect(handle: Long, kind: Int, name: String)
private external fun nativeLoadModel(handle: Long, path: String)
private external fun nativeSlice(handle: Long, gcodePath: String, listener: SliceProgressListener): DoubleArray
private external fun nativeCancel(handle: Long)
