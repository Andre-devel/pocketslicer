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

/** Imagem RGBA de [width] × [height] com as linhas de baixo para cima (a ordem do glReadPixels). */
class Thumbnail(val width: Int, val height: Int, val rgba: ByteArray)

/** Área de impressão em mm: contorno com x, y intercalados e altura máxima. */
class Bed(val shape: FloatArray, val maxHeight: Float)

/**
 * Malha de um objeto em coordenadas locais: x, y, z por vértice; 3 índices por triângulo e o
 * filamento (a partir de 0, índice em [SlicerEngine.filamentColors]) de cada triângulo, que vêm
 * agrupados por filamento em ordem crescente. As partes pintadas em várias cores vêm divididas
 * nos triângulos da pintura.
 */
class ObjectMesh(val positions: FloatArray, val indices: IntArray, val filaments: ByteArray)

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

/** Placas de um projeto 3MF: quantos objetos cada uma tem e a [selected] (a partir de 0), a da mesa. */
data class ProjectPlates(val objects: List<Int>, val selected: Int)

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

    /**
     * Idioma dos textos do motor: rótulos e dicas das opções, progresso e erros. [language]
     * como os catálogos do Orca ("pt_BR", "en"); sem catálogo, ficam em inglês. Vale para
     * todas as instâncias; as [options] lidas antes continuam no idioma anterior.
     */
    suspend fun setLanguage(language: String) = call { nativeSetLanguage(handle, language) }

    /** Texto do libslic3r (ex.: um de [roleNames]) no idioma de [setLanguage]. */
    suspend fun translate(text: String): String = call { nativeTranslate(text) }

    suspend fun vendors(): List<String> = call { nativeVendors(handle).asList() }

    /** Carrega os perfis do fabricante e seleciona a primeira impressora dele. */
    suspend fun loadVendor(vendor: String) = call { nativeLoadVendor(handle, vendor) }

    /** Impressoras do fabricante; filamentos e processos compatíveis com a seleção atual. */
    suspend fun presets(kind: PresetKind): List<String> = call { nativePresets(handle, kind.ordinal).asList() }

    suspend fun selected(kind: PresetKind): String = call { nativeSelected(handle, kind.ordinal) }

    /** Trocar a impressora seleciona o processo e o filamento padrão dela. */
    suspend fun select(kind: PresetKind, name: String) = call { nativeSelect(handle, kind.ordinal, name) }

    /** Opções editáveis do tipo de preset; só os textos mudam, com [setLanguage]. */
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

    /**
     * Projeto 3MF do Orca/Bambu Studio: objetos nas posições salvas e perfis com as alterações.
     * Carrega o fabricante da impressora do projeto e devolve o nome dele. Um 3MF sem
     * configuração abre como [loadModel]; de várias placas, só a primeira com objetos.
     */
    suspend fun openProject(file: File): String = call { nativeOpenProject(handle, file.absolutePath) }

    /**
     * Placas do projeto aberto por [openProject], ou null se ele tem só uma ou se, depois
     * dele, vieram [loadModel] ou [addModel].
     */
    suspend fun projectPlates(): ProjectPlates? = call {
        val objects = nativeProjectPlates(handle)
        if (objects.isEmpty()) null else ProjectPlates(objects.asList(), nativeProjectPlate(handle))
    }

    /**
     * Troca os objetos da mesa pelos da placa (a partir de 0) do projeto, nas posições do
     * arquivo; as alterações nos da placa anterior se perdem.
     */
    suspend fun selectPlate(plate: Int) = call { nativeSelectPlate(handle, plate) }

    /** Grava objetos, configuração e miniatura num 3MF que o Orca abre. */
    suspend fun saveProject(file: File, thumbnail: Thumbnail?) = call {
        val images = listOfNotNull(thumbnail)
        nativeSaveProject(handle, file.absolutePath, images.sizes(), images.pixels())
    }

    /** Se a impressora selecionada recebe o G-code dentro de um .gcode.3mf em vez do .gcode puro. */
    suspend fun gcode3mf(): Boolean = call { nativeGcode3mf(handle) }

    /**
     * .gcode.3mf com o G-code do último [slice] (gravado em [gcode]) e a miniatura da mesa
     * (512x512), como "Exportar arquivo fatiado da placa" do Orca.
     */
    suspend fun exportGcode3mf(gcode: File, file: File, thumbnail: Thumbnail?) = call {
        val images = listOfNotNull(thumbnail)
        nativeExportGcode3mf(handle, gcode.absolutePath, file.absolutePath, images.sizes(), images.pixels())
    }

    /**
     * Tamanhos (largura, altura) das miniaturas que o G-code da impressora selecionada leva,
     * a passar para [slice]. Vazio nas impressoras Bambu, cujo G-code não tem miniatura.
     */
    suspend fun gcodeThumbnailSizes(): List<Pair<Int, Int>> = call {
        nativeGcodeThumbnailSizes(handle).asList().chunked(2) { it[0] to it[1] }
    }

    /** G-code pronto, para preview: substitui os [toolpaths] do último [slice]. */
    suspend fun loadGcode(file: File): SliceStats = call { nativeLoadGcode(handle, file.absolutePath).toStats() }

    /** Cópia do objeto, ao fim da lista; rearranja todos como em [addModel]. */
    suspend fun duplicate(objectIndex: Int) = call { nativeDuplicate(handle, objectIndex) }

    suspend fun remove(objectIndex: Int) = call { nativeRemove(handle, objectIndex) }

    /** Arranjo automático de todos os objetos na mesa. */
    suspend fun arrange() = call { nativeArrange(handle) }

    /** Malhas dos objetos, na mesma ordem de [placements]. */
    suspend fun objects(): List<ObjectMesh> = call { nativeObjects(handle).asList() }

    /** Cor (`#RRGGBB`) de cada filamento do projeto, pelo índice de [ObjectMesh.filaments]. */
    suspend fun filamentColors(): List<String> = call { nativeFilamentColors(handle).asList() }

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

    /**
     * Fatia os objetos na posição atual (todos dentro do volume de impressão) e grava o G-code em [gcode].
     * [thumbnails]: nos tamanhos de [gcodeThumbnailSizes]. [listener] roda na thread do motor.
     */
    suspend fun slice(gcode: File, thumbnails: List<Thumbnail> = emptyList(), listener: SliceProgressListener): SliceStats = coroutineScope {
        val job = async(dispatcher) { nativeSlice(handle, gcode.absolutePath, thumbnails.sizes(), thumbnails.pixels(), listener) }
        val result = try {
            job.await()
        } catch (e: CancellationException) {
            nativeCancel(handle) // o fatiamento nativo não é interrompível de outra forma
            throw e
        }
        result.toStats()
    }

    /** Caminhos do último [slice] ou [loadGcode] bem-sucedido. */
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

private fun List<Thumbnail>.sizes() = flatMap { listOf(it.width, it.height) }.toIntArray()
private fun List<Thumbnail>.pixels() = map { it.rgba }.toTypedArray()

private fun DoubleArray.toStats() = SliceStats(this[0], this[1], this[2], this[3], this[4])

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
private external fun nativeSetLanguage(handle: Long, language: String)
private external fun nativeTranslate(text: String): String
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
private external fun nativeOpenProject(handle: Long, path: String): String
private external fun nativeProjectPlates(handle: Long): IntArray
private external fun nativeProjectPlate(handle: Long): Int
private external fun nativeSelectPlate(handle: Long, plate: Int)
private external fun nativeSaveProject(handle: Long, path: String, thumbnailSize: IntArray, thumbnail: Array<ByteArray>)
private external fun nativeGcode3mf(handle: Long): Boolean
private external fun nativeExportGcode3mf(handle: Long, gcodePath: String, path: String, thumbnailSize: IntArray, thumbnail: Array<ByteArray>)
private external fun nativeGcodeThumbnailSizes(handle: Long): IntArray
private external fun nativeLoadGcode(handle: Long, path: String): DoubleArray
private external fun nativeDuplicate(handle: Long, objectIndex: Int)
private external fun nativeRemove(handle: Long, objectIndex: Int)
private external fun nativeArrange(handle: Long)
private external fun nativeObjects(handle: Long): Array<ObjectMesh>
private external fun nativeFilamentColors(handle: Long): Array<String>
private external fun nativePlacements(handle: Long): DoubleArray
private external fun nativeSetTransform(handle: Long, objectIndex: Int, transform: DoubleArray)
private external fun nativeSetPlacement(handle: Long, objectIndex: Int, values: DoubleArray)
private external fun nativeLayOnFace(handle: Long, objectIndex: Int, triangle: Int)
private external fun nativeSlice(
    handle: Long, gcodePath: String, thumbnailSizes: IntArray, thumbnails: Array<ByteArray>, listener: SliceProgressListener,
): DoubleArray
private external fun nativeCancel(handle: Long)
private external fun nativeToolpaths(handle: Long): Toolpaths
private external fun nativeRoleNames(): Array<String>
