package io.github.andredevel.slicer.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Tipos de preset do OrcaSlicer. A ordem espelha `slicer::PresetKind` (native/src/engine.hpp). */
enum class PresetKind { PRINTER, FILAMENT, PROCESS }

data class SliceStats(
    val printTimeSeconds: Double,
    val filamentMm3: Double,
    val filamentMeters: Double,
    val filamentGrams: Double,
    /** Na moeda do preset de filamento (filament_cost por kg). */
    val cost: Double,
)

/**
 * Caminhos do último fatiamento, na ordem do G-code. [extrusions]: 9 floats por segmento
 * (x0 y0 z0 x1 y1 z1 largura altura velocidade em mm/s; z no topo do filete); [roles]: tipo
 * de extrusão (índice em [SlicerEngine.roleNames]); [layers]: camada de cada segmento.
 * [travels]: 6 floats por movimento sem extrusão. [layerZ]: topo de cada camada.
 */
class Toolpaths(
    val extrusions: FloatArray,
    val roles: ByteArray,
    val layers: IntArray,
    val travels: FloatArray,
    val travelLayers: IntArray,
    val layerZ: FloatArray,
) {
    val segmentCount get() = roles.size
    val layerCount get() = layerZ.size
}

/** Área de impressão em mm: contorno com x, y intercalados e altura máxima. */
class Bed(val shape: FloatArray, val maxHeight: Float)

/** Malha de um objeto em coordenadas locais: x, y, z por vértice; 3 índices por triângulo. */
class ObjectMesh(val positions: FloatArray, val indices: IntArray)

/**
 * Posição de um objeto na mesa: [transform] é a matriz 4x4 em ordem de coluna (OpenGL),
 * decomposta em [offset] (mm), [rotation] (graus, Euler XYZ como no Orca) e [scale] (1 = 100%).
 * [outside]: fora, total ou parcialmente, do volume de impressão (impede o fatiamento).
 */
class Placement(
    val transform: FloatArray,
    val offset: FloatArray,
    val rotation: FloatArray,
    val scale: FloatArray,
    val outside: Boolean,
)

/** Tipo de valor de uma opção do Orca (ConfigOptionType sem a distinção escalar/vetor). */
enum class OptionType { BOOL, INT, FLOAT, PERCENT, FLOAT_OR_PERCENT, STRING, ENUM, POINT, POINTS, OTHER }

/**
 * Definição de uma opção editável, vinda do `PrintConfigDef` do Orca. [simple]: aparece no
 * modo simples da GUI do Orca (painel básico); as demais são do modo avançado. [vector]:
 * um valor por extrusor/filamento, separados por vírgula na forma serializada.
 */
data class OptionDef(
    val key: String,
    val label: String,
    val category: String,
    val tooltip: String,
    val sidetext: String,
    val type: OptionType,
    val vector: Boolean,
    val simple: Boolean,
    val multiline: Boolean,
    val enumValues: List<String>,
    val enumLabels: List<String>,
    val min: Double?,
    val max: Double?,
)

/** Valores (serializados como no Orca) do preset em edição e as chaves alteradas desde o salvo. */
data class PresetValues(val values: Map<String, String>, val dirty: Set<String>)

fun interface SliceProgressListener {
    fun onProgress(percent: Int, text: String)
}

/** Erro reportado pelo libslic3r (perfil inválido, modelo ilegível, falha ao fatiar...). */
class SlicerException(message: String) : RuntimeException(message)

/**
 * Motor de fatiamento: libslic3r do OrcaSlicer via JNI (native/src/engine.hpp).
 *
 * O motor nativo não é thread-safe e o libslic3r tem estado global (diretórios de dados),
 * então toda chamada, de qualquer instância, roda numa única thread do processo; as funções
 * podem ser chamadas de qualquer coroutine. Cancelar a coroutine de [slice] interrompe o
 * fatiamento.
 */
class SlicerEngine private constructor(private val handle: Long) : AutoCloseable {

    suspend fun vendors(): List<String> = call { nativeVendors(handle).asList() }

    /** Carrega os perfis do fabricante e seleciona a primeira impressora dele. */
    suspend fun loadVendor(vendor: String) = call { nativeLoadVendor(handle, vendor) }

    /** Impressoras do fabricante; filamentos e processos compatíveis com a seleção atual. */
    suspend fun presets(kind: PresetKind): List<String> = call { nativePresets(handle, kind.ordinal).asList() }

    suspend fun selected(kind: PresetKind): String = call { nativeSelected(handle, kind.ordinal) }

    /** Trocar a impressora seleciona o processo e o filamento padrão dela. */
    suspend fun select(kind: PresetKind, name: String) = call { nativeSelect(handle, kind.ordinal, name) }

    /** Opções editáveis do tipo de preset; não mudam durante a execução. */
    suspend fun options(kind: PresetKind): List<OptionDef> = call { parseOptions(nativeOptionsJson(handle, kind.ordinal)) }

    suspend fun values(kind: PresetKind): PresetValues = call {
        val json = JSONObject(nativeValuesJson(handle, kind.ordinal))
        val values = json.getJSONObject("values")
        PresetValues(
            values = values.keys().asSequence().associateWith(values::getString),
            dirty = json.getJSONArray("dirty").strings().toSet(),
        )
    }

    /** Altera o preset em edição (usado no fatiamento). [value] no formato do Orca, ex.: "0.2", "15%". */
    suspend fun setOption(kind: PresetKind, key: String, value: String) = call { nativeSetOption(handle, kind.ordinal, key, value) }

    suspend fun discardChanges(kind: PresetKind) = call { nativeDiscardChanges(handle, kind.ordinal) }

    /** Presets de usuário entre os de [presets]; os demais são de sistema (somente leitura). */
    suspend fun userPresets(kind: PresetKind): List<String> = call { nativeUserPresets(handle, kind.ordinal).asList() }

    /** Salva o preset em edição como preset de usuário [name] e o seleciona. */
    suspend fun savePreset(kind: PresetKind, name: String) = call { nativeSavePreset(handle, kind.ordinal, name) }

    suspend fun deletePreset(kind: PresetKind, name: String) = call { nativeDeletePreset(handle, kind.ordinal, name) }

    /** Importa um .json de preset do Orca; devolve os nomes importados. */
    suspend fun importPreset(file: File): List<String> = call { nativeImportPreset(handle, file.absolutePath).asList() }

    /** Exporta o preset em edição (configuração completa) como .json do Orca. */
    suspend fun exportPreset(kind: PresetKind, file: File) = call { nativeExportPreset(handle, kind.ordinal, file.absolutePath) }

    /** Mesa da impressora selecionada. */
    suspend fun bed(): Bed = call { nativeBed(handle) }

    /**
     * STL, OBJ ou 3MF; o formato vem da extensão do arquivo. O modelo é centralizado na
     * mesa, e trocar a impressora o recentraliza.
     */
    suspend fun loadModel(file: File) = call { nativeLoadModel(handle, file.absolutePath) }

    /** Acrescenta os objetos do arquivo; se a mesa já tinha objetos, todos são rearranjados. */
    suspend fun addModel(file: File) = call { nativeAddModel(handle, file.absolutePath) }

    /** Cópia do objeto, ao fim da lista; rearranja todos como em [addModel]. */
    suspend fun duplicate(objectIndex: Int) = call { nativeDuplicate(handle, objectIndex) }

    suspend fun remove(objectIndex: Int) = call { nativeRemove(handle, objectIndex) }

    /** Arranjo automático de todos os objetos na mesa. */
    suspend fun arrange() = call { nativeArrange(handle) }

    /** Malhas dos objetos, na mesma ordem de [placements]. */
    suspend fun objects(): List<ObjectMesh> = call { nativeObjects(handle).asList() }

    suspend fun placements(): List<Placement> = call {
        val v = nativePlacements(handle)
        List(v.size / 26) { i ->
            fun part(from: Int, size: Int) = FloatArray(size) { v[i * 26 + from + it].toFloat() }
            Placement(part(0, 16), part(16, 3), part(19, 3), part(22, 3), outside = v[i * 26 + 25] != 0.0)
        }
    }

    /** As alterações de posição sempre apoiam o objeto na mesa. */
    suspend fun setTransform(objectIndex: Int, transform: FloatArray) =
        call { nativeSetTransform(handle, objectIndex, DoubleArray(16) { transform[it].toDouble() }) }

    suspend fun setPlacement(objectIndex: Int, offset: FloatArray, rotation: FloatArray, scale: FloatArray) = call {
        nativeSetPlacement(handle, objectIndex, (offset + rotation + scale).map(Float::toDouble).toDoubleArray())
    }

    /** Gira o objeto para apoiar na mesa o triângulo [triangle] da malha de [objects]. */
    suspend fun layOnFace(objectIndex: Int, triangle: Int) = call { nativeLayOnFace(handle, objectIndex, triangle) }

    /** Fatia os objetos na posição atual (todos dentro do volume de impressão) e grava o G-code em [gcode]. [listener] roda na thread do motor. */
    suspend fun slice(gcode: File, listener: SliceProgressListener): SliceStats = coroutineScope {
        val job = async(dispatcher) { nativeSlice(handle, gcode.absolutePath, listener) }
        val result = try {
            job.await()
        } catch (e: CancellationException) {
            nativeCancel(handle) // o fatiamento nativo não é interrompível de outra forma
            throw e
        }
        SliceStats(result[0], result[1], result[2], result[3], result[4])
    }

    /** Caminhos do último [slice] bem-sucedido. */
    suspend fun toolpaths(): Toolpaths = call { nativeToolpaths(handle) }

    override fun close() {
        dispatcher.executor.execute { nativeDestroy(handle) }
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
        /** Nome (em inglês, como no Orca) de cada tipo de extrusão de [Toolpaths.roles]. */
        val roleNames: List<String> by lazy { nativeRoleNames().asList() }

        suspend fun create(resourcesDir: File, dataDir: File): SlicerEngine =
            SlicerEngine(withContext(dispatcher) { nativeCreate(resourcesDir.absolutePath, dataDir.absolutePath) })

        // Uma thread para o processo todo. Com uma thread nova por motor, motores criados
        // depois do primeiro falhavam ao fatiar com "Coordinate outside allowed range"
        // (visto nos testes instrumentados; não reproduz no slice_cli).
        private val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
            // libslic3r usa recursão profunda em algumas etapas.
            Thread(null, runnable, "slicer-engine", 64L shl 20).apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }
}

private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }

private fun parseOptions(json: String): List<OptionDef> {
    val array = JSONArray(json)
    return List(array.length()) { i ->
        val o = array.getJSONObject(i)
        OptionDef(
            key = o.getString("key"),
            label = o.getString("label"),
            category = o.getString("category"),
            tooltip = o.getString("tooltip"),
            sidetext = o.getString("sidetext"),
            type = OptionType.valueOf(o.getString("type").uppercase()),
            vector = o.getBoolean("vector"),
            simple = o.getInt("mode") == 0,
            multiline = o.getBoolean("multiline"),
            enumValues = o.getJSONArray("enum_values").strings(),
            enumLabels = o.getJSONArray("enum_labels").strings(),
            min = o.optDouble("min").takeUnless { it.isNaN() },
            max = o.optDouble("max").takeUnless { it.isNaN() },
        )
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
private external fun nativeOptionsJson(handle: Long, kind: Int): String
private external fun nativeValuesJson(handle: Long, kind: Int): String
private external fun nativeSetOption(handle: Long, kind: Int, key: String, value: String)
private external fun nativeDiscardChanges(handle: Long, kind: Int)
private external fun nativeUserPresets(handle: Long, kind: Int): Array<String>
private external fun nativeSavePreset(handle: Long, kind: Int, name: String)
private external fun nativeDeletePreset(handle: Long, kind: Int, name: String)
private external fun nativeImportPreset(handle: Long, path: String): Array<String>
private external fun nativeExportPreset(handle: Long, kind: Int, path: String)
private external fun nativeBed(handle: Long): Bed
private external fun nativeLoadModel(handle: Long, path: String)
private external fun nativeAddModel(handle: Long, path: String)
private external fun nativeDuplicate(handle: Long, objectIndex: Int)
private external fun nativeRemove(handle: Long, objectIndex: Int)
private external fun nativeArrange(handle: Long)
private external fun nativeObjects(handle: Long): Array<ObjectMesh>
private external fun nativePlacements(handle: Long): DoubleArray
private external fun nativeSetTransform(handle: Long, objectIndex: Int, transform: DoubleArray)
private external fun nativeSetPlacement(handle: Long, objectIndex: Int, values: DoubleArray)
private external fun nativeLayOnFace(handle: Long, objectIndex: Int, triangle: Int)
private external fun nativeSlice(handle: Long, gcodePath: String, listener: SliceProgressListener): DoubleArray
private external fun nativeCancel(handle: Long)
private external fun nativeToolpaths(handle: Long): Toolpaths
private external fun nativeRoleNames(): Array<String>
