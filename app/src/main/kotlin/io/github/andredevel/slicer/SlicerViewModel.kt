package io.github.andredevel.slicer

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.andredevel.slicer.core.BedTypes
import io.github.andredevel.slicer.core.Brush
import io.github.andredevel.slicer.core.Calibration
import io.github.andredevel.slicer.core.ClipPlane
import io.github.andredevel.slicer.core.HeightRange
import io.github.andredevel.slicer.core.LayerChange
import io.github.andredevel.slicer.core.ObjectMesh
import io.github.andredevel.slicer.core.OptionDef
import io.github.andredevel.slicer.core.PaintKind
import io.github.andredevel.slicer.core.PaintState
import io.github.andredevel.slicer.core.PaintMesh
import io.github.andredevel.slicer.core.Part
import io.github.andredevel.slicer.core.PresetValues
import io.github.andredevel.slicer.core.PresetKind
import io.github.andredevel.slicer.core.Plates
import io.github.andredevel.slicer.core.SettingsTarget
import io.github.andredevel.slicer.core.SliceStats
import io.github.andredevel.slicer.core.SlicerEngine
import io.github.andredevel.slicer.core.SlicerException
import io.github.andredevel.slicer.core.Thumbnail
import io.github.andredevel.slicer.profiles.OrcaResources
import io.github.andredevel.slicer.render.Preview
import io.github.andredevel.slicer.render.Scene
import io.github.andredevel.slicer.render.Tool
import io.github.andredevel.slicer.send.Connection
import io.github.andredevel.slicer.send.Kind
import io.github.andredevel.slicer.send.PrinterHost
import io.github.andredevel.slicer.send.PrinterStatus
import io.github.andredevel.slicer.send.PrinterStore
import io.github.andredevel.slicer.send.SendException
import io.github.andredevel.slicer.send.Slot
import io.github.andredevel.slicer.send.uploader
import io.github.andredevel.slicer.update.AppUpdater
import io.github.andredevel.slicer.update.InstallResult
import io.github.andredevel.slicer.update.UpdateReceiver
import io.github.andredevel.slicer.update.Release
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

/**
 * Presets de um tipo: [options] para escolher, o [selected], quais são de usuário ([user]) e os
 * valores do preset em edição, com as alterações não salvas.
 */
data class PresetChoice(
    val options: List<String> = emptyList(),
    val selected: String? = null,
    val user: Set<String> = emptySet(),
    val values: PresetValues = PresetValues(emptyMap(), emptySet()),
) {
    val dirty get() = values.dirty.isNotEmpty()
    val selectedIsUser get() = selected in user
}

/** Diálogo de envio: operação em andamento ou o resultado da última. */
sealed interface SendState {
    data object Idle : SendState
    /** [progress] de 0 a 1, ou null enquanto só conecta; [steps] no envio do G-code. */
    data class Working(@StringRes val text: Int, val progress: Float? = null, val steps: SendSteps? = null) : SendState
    /** [connection]: o que respondeu ao teste de conexão; [steps]: as etapas do envio. */
    data class Done(@StringRes val text: Int, val printer: String, val connection: Connection? = null, val steps: SendSteps? = null) : SendState
    data class Failed(val message: String) : SendState
}

/**
 * Mensagem sobre o fim de uma operação: erro (fica até ser fechada) ou sucesso. [action]: o que
 * o botão da mensagem faz; [details]: texto técnico de [MessageAction.DETAILS].
 */
data class UserMessage(val text: String, val success: Boolean = false, val action: MessageAction? = null, val details: String? = null)

enum class MessageAction { PICK_OTHER, RETRY, DETAILS, UNDO }

/** Atualização do app pelo GitHub Releases (Configurações e aviso ao abrir o app). */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    /** APK baixado e conferido, pronto para [SlicerViewModel.installUpdate]. */
    data class Ready(val release: Release, val apk: File) : UpdateState
    /** [ready]: volta a ele se o usuário recusar a confirmação do sistema. */
    data class Installing(val ready: Ready) : UpdateState
    data class Failed(val error: String) : UpdateState
}

enum class SendStep { CONNECT, UPLOAD, START }

/**
 * Andamento de um envio: [current] (null quando todas terminaram), bytes enviados de [total] e
 * quanto durou cada etapa concluída.
 */
data class SendSteps(
    val all: List<SendStep>,
    val current: SendStep?,
    val sent: Long,
    val total: Long,
    val millis: Map<SendStep, Long> = emptyMap(),
)

sealed interface SliceState {
    data object Idle : SliceState
    /** [layer]: camada em que o motor está, quando ele informa (na geração do G-code). */
    data class Running(val percent: Int, val stage: SliceStage, val layer: Int? = null) : SliceState
    /**
     * [preview]: caminhos do G-code gerado.
     * [gcode3mf]: a impressora recebe o G-code num .gcode.3mf (só no fatiamento pelo app).
     * [slicedAt]: fim do fatiamento (System.currentTimeMillis); null num G-code aberto.
     * [stale]: cena ou perfil mudaram depois de fatiar; salvar e enviar esperam fatiar de novo.
     */
    data class Done(
        val stats: SliceStats,
        val preview: Preview,
        val gcode3mf: Boolean = false,
        val slicedAt: Long? = null,
        val stale: StaleReason? = null,
    ) : SliceState
}

/** O que mudou depois do fatiamento: uma opção ou um perfil ([name]), ou a mesa (null). */
data class StaleReason(val name: String?)

/** Etapas do fatiamento, pelas faixas de progresso do Print do Orca (Print::process). */
enum class SliceStage(@StringRes val label: Int, private val fromPercent: Int) {
    PREPARE(R.string.slice_stage_prepare, 0),
    LAYERS(R.string.slice_stage_layers, 5), // "Slicing mesh"
    WALLS(R.string.slice_stage_walls, 15), // "Generating walls", infill
    GCODE(R.string.slice_stage_gcode, 50); // suportes, saia, "Generating G-code: layer N"

    companion object {
        fun of(percent: Int) = entries.last { percent >= it.fromPercent }
    }
}

/**
 * Opções próprias de um objeto ([values]), das faixas de altura dele e das peças ([parts], na
 * ordem de [Scene.parts]; vazias nas que não têm configuração).
 */
data class ObjectSettings(
    val values: Map<String, String> = emptyMap(),
    val ranges: List<RangeSettings> = emptyList(),
    val parts: List<Map<String, String>> = emptyList(),
)

data class RangeSettings(val range: HeightRange, val values: Map<String, String>)

/**
 * Pintura aberta (uma ferramenta de pintura com o objeto [objectIndex] selecionado): o que está
 * pintado dele e o ângulo das saliências destacadas (0 = nenhuma), como no gizmo do Orca.
 * [gaps]: com "Preencher lacunas" escolhido, os trechos que ele vai mudar (no estado novo).
 * [clipRatio]: "Vista em corte", de 0 (nada escondido) a 1; [clipDirection] muda a cada
 * "Redefinir direção", que põe o corte de frente para a câmera.
 */
data class Painting(
    val objectIndex: Int,
    val kind: PaintKind,
    val mesh: PaintMesh,
    val highlightAngle: Float,
    val gaps: PaintMesh? = null,
    val clipRatio: Float = 0f,
    val clipDirection: Int = 0,
)

data class SlicerUiState(
    /** Operação em andamento que bloqueia os controles (carregar perfis, modelo...). */
    @StringRes val busy: Int? = R.string.busy_installing_profiles,
    val message: UserMessage? = null,
    val vendors: PresetChoice = PresetChoice(),
    val presets: Map<PresetKind, PresetChoice> = emptyMap(),
    /** Preset de cada filamento do projeto (cores em [Scene.filamentColors], na mesma ordem). */
    val filaments: List<String> = emptyList(),
    /** Material de cada filamento do projeto ("PLA", "PETG"...), na ordem de [filaments]. */
    val filamentTypes: List<String> = emptyList(),
    /** Quantos filamentos o projeto pode ter com a impressora selecionada. */
    val filamentLimits: IntRange = 1..1,
    /** Purga (mm³) da troca entre filamentos: matriz n x n por linha, uma por extrusor; ver [flushMultipliers]. */
    val flushVolumes: DoubleArray = DoubleArray(0),
    val flushMultipliers: DoubleArray = DoubleArray(0),
    /** Tipo de mesa do projeto; sem escolha quando a impressora só tem um ([BedTypes.values] vazio). */
    val bedTypes: BedTypes? = null,
    /** Pausas, trocas de filamento e G-code por altura da placa da mesa, por z. */
    val layerChanges: List<LayerChange> = emptyList(),
    /** Troca de filamento por altura possível (vários filamentos, objetos todos num só). */
    val filamentChangeAvailable: Boolean = false,
    /** Definições das opções editáveis (PrintConfigDef do Orca); textos no idioma do app. */
    val optionDefs: Map<PresetKind, List<OptionDef>> = emptyMap(),
    /** Opções que cada tipo de alvo de configuração própria aceita; ver [SlicerEngine.settingsKeys]. */
    val settingsKeys: Map<SettingsTarget.Kind, Set<String>> = emptyMap(),
    /** Configuração própria de cada objeto da placa, na ordem de [Scene.placements]. */
    val objectSettings: List<ObjectSettings> = emptyList(),
    /** Nomes dos tipos de extrusão (índice em SlicerEngine.roleNames) no idioma do app. */
    val roleLabels: List<String> = SlicerEngine.roleNames,
    /** Nome do primeiro modelo adicionado; dá nome ao G-code. */
    val modelName: String? = null,
    val scene: Scene? = null,
    /** Placas da mesa; Fatiar e Arranjar agem na escolhida. */
    val plates: Plates = Plates(listOf(0), 0),
    val tool: Tool = Tool.MOVE,
    val painting: Painting? = null,
    /** Pincel das ferramentas de pintura; o ângulo das saliências vem de [Painting.highlightAngle]. */
    val brush: Brush = Brush(),
    /** Pintar só nas saliências destacadas (suporte). */
    val paintOnOverhangsOnly: Boolean = false,
    /** "Detecção de bordas" do balde da pintura de cor: limita o balde a [Brush.smartFillAngle] entre faces vizinhas. */
    val edgeDetection: Boolean = true,
    /** Com "Preencher lacunas" (suporte) escolhido no lugar de [brush]: a área máxima (mm²) dos trechos. */
    val gapArea: Float? = null,
    /** Viewport na pré-visualização do G-code (só com fatiamento concluído). */
    val showPreview: Boolean = false,
    val slice: SliceState = SliceState.Idle,
    /** Impressoras salvas para o envio do G-code. */
    val printers: List<PrinterHost> = emptyList(),
    val send: SendState = SendState.Idle,
    /** Estado ao vivo por [PrinterHost.id], enquanto alguma tela o acompanha (watchPrinterStatus). */
    val printerStatus: Map<String, PrinterStatus> = emptyMap(),
    /** Arquivos abertos recentemente, o mais novo primeiro. */
    val recents: List<RecentFile> = emptyList(),
    /** Cena ou perfis mudaram desde que o projeto foi aberto ou salvo. */
    val dirty: Boolean = false,
    /** Moeda do custo (ISO 4217) escolhida em Configurações; null segue o idioma. */
    val currency: String? = null,
    val autoUpdate: Boolean = true,
    val update: UpdateState = UpdateState.Idle,
    /** Pergunta se instala a versão baixada (ao terminar o download ou por Instalar em Configurações). */
    val updatePrompt: Boolean = false,
) {
    /** Objetos da placa escolhida. */
    val objectCount get() = scene?.placements?.size ?: 0
    /** Objetos de todas as placas: o que o projeto salva. */
    val projectObjectCount get() = plates.objects.sum()
    val anyOutside get() = scene?.placements?.any { it.outside } == true
    /** Instalar agora fecha o app: perderia o projeto não salvo, o fatiamento ou o envio em andamento. */
    val updateLosesWork get() = dirty || slice is SliceState.Running || send is SendState.Working
    val canSlice get() = busy == null && objectCount > 0 && !anyOutside && slice !is SliceState.Running
    val preview get() = (slice as? SliceState.Done)?.preview
    /** G-code em dia com a cena e os perfis: pode ser salvo e enviado. */
    val gcodeReady get() = (slice as? SliceState.Done)?.let { it.stale == null } == true
    val previewVisible get() = showPreview && preview != null
    /** Placa que Fatiar usa, a partir de 1. */
    val plateNumber get() = plates.selected + 1
    val selectedPlacement get() = scene?.selected?.let { scene.placements.getOrNull(it) }
    /** Há o que salvar: projeto sem objetos não conta como alteração. */
    val unsaved get() = dirty && projectObjectCount > 0
}

class SlicerViewModel(application: Application) : AndroidViewModel(application) {
    private val app get() = getApplication<Application>()
    private val _state = MutableStateFlow(SlicerUiState())
    val state: StateFlow<SlicerUiState> = _state.asStateFlow()

    private val gcodeFile get() = File(app.cacheDir, "output.gcode")

    /** Idioma dos textos do motor, o do app ([setLocale]). */
    private var language = engineLanguage(app.resources.configuration.locales[0])

    /**
     * Renderiza a miniatura dos objetos (largura, altura) para o projeto e o G-code; null sem
     * objetos. O viewport registra enquanto existe (ViewportView.thumbnail).
     */
    var thumbnails: (suspend (width: Int, height: Int) -> Thumbnail?)? = null
    private var sliceJob: Job? = null
    private var retryAction: (() -> Unit)? = null

    /** Mesa de antes do último "Abrir com", para [undoOpen]. */
    private class OpenUndo(val project: File?, val modelName: String?, val dirty: Boolean)
    private var openUndo: OpenUndo? = null
    private var sendJob: Job? = null
    private val printerStore = PrinterStore(File(app.filesDir, "printers.json"))
    private val recentStore = RecentFilesStore(File(app.filesDir, "recent.json"))
    private val settings = SettingsStore(app.getSharedPreferences("settings", Context.MODE_PRIVATE))
    // Lido já na criação: a primeira tela sai com o tema certo, sem piscar.
    private val _theme = MutableStateFlow(settings.theme)
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()
    private val updater = AppUpdater(app)
    val installedVersion get() = updater.installedVersion
    private var updateJob: Job? = null

    private val engine = viewModelScope.async {
        val resources = withContext(Dispatchers.IO) { OrcaResources.install(app) }
        SlicerEngine.create(resources, File(app.filesDir, "orca-data")).also(::addCloseable)
    }

    // Perfis iniciais; arquivos abertos por outro app ("Abrir com") esperam por ela.
    private val setup = launchEngine(R.string.busy_installing_profiles) { engine ->
            applyLanguage(engine)
            val vendors = engine.vendors()
            val vendor = if (DEFAULT_VENDOR in vendors) DEFAULT_VENDOR else vendors.first()
            _state.update { it.copy(vendors = PresetChoice(vendors)) }
            loadVendor(engine, vendor)
        }

    init {
        viewModelScope.launch {
            val printers = withContext(Dispatchers.IO) { printerStore.load() }
            val recents = withContext(Dispatchers.IO) { recentStore.load() }
            val currency = withContext(Dispatchers.IO) { settings.currency }
            val autoUpdate = withContext(Dispatchers.IO) { settings.autoUpdate }
            _state.update { it.copy(printers = printers, recents = recents, currency = currency, autoUpdate = autoUpdate) }
            val due = System.currentTimeMillis() - withContext(Dispatchers.IO) { settings.lastUpdateCheck } >= UPDATE_CHECK_INTERVAL_MS
            if (autoUpdate && due) checkUpdate(automatic = true)
        }
        // Resultado da instalação (UpdateReceiver): recusada na confirmação do sistema volta a
        // oferecer Instalar; falha vira mensagem. Sucesso não chega, o Android fecha o app.
        viewModelScope.launch {
            UpdateReceiver.results.collect { result ->
                _state.update { state ->
                    val ready = state.update as? UpdateState.Ready ?: (state.update as? UpdateState.Installing)?.ready
                    when (result) {
                        InstallResult.Aborted -> state.copy(update = ready ?: UpdateState.Idle)
                        is InstallResult.Failed -> {
                            val text = app.getString(R.string.update_install_failed, result.message)
                            state.copy(update = UpdateState.Failed(result.message), message = UserMessage(text))
                        }
                    }
                }
            }
        }
    }

    fun setCurrency(currency: String?) {
        settings.currency = currency
        _state.update { it.copy(currency = currency) }
    }

    fun setTheme(mode: ThemeMode) {
        settings.theme = mode
        _theme.value = mode
    }

    fun setAutoUpdate(enabled: Boolean) {
        settings.autoUpdate = enabled
        _state.update { it.copy(autoUpdate = enabled) }
    }

    /**
     * Procura versão nova no GitHub e já baixa o APK; pronto, pergunta se instala. [automatic]
     * (ao abrir o app) fica em silêncio quando não há versão nova ou falta rede.
     */
    fun checkUpdate(automatic: Boolean = false) {
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch {
            _state.update { it.copy(update = UpdateState.Checking) }
            try {
                val release = updater.check()
                settings.lastUpdateCheck = System.currentTimeMillis()
                if (release == null) {
                    withContext(Dispatchers.IO) { updater.clear() }
                    _state.update { it.copy(update = UpdateState.UpToDate) }
                    return@launch
                }
                _state.update { it.copy(update = UpdateState.Downloading(release, 0f)) }
                val apk = updater.download(release) { progress ->
                    _state.update { it.copy(update = UpdateState.Downloading(release, progress)) }
                }
                _state.update { it.copy(update = UpdateState.Ready(release, apk), updatePrompt = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "update check failed", e)
                _state.update { it.copy(update = if (automatic) UpdateState.Idle else UpdateState.Failed(e.message ?: e.toString())) }
            }
        }
    }

    /** Abre a pergunta de instalar a versão baixada; recusada, volta a aparecer na próxima abertura do app. */
    fun promptUpdate() = _state.update { if (it.update is UpdateState.Ready) it.copy(updatePrompt = true) else it }

    fun dismissUpdatePrompt() = _state.update { it.copy(updatePrompt = false) }

    /**
     * Instala o APK baixado; o Android fecha o app ao concluir. Sem a permissão de instalar apps
     * (Android 8+, por app), abre a tela do sistema para concedê-la; tocar em Instalar de novo segue.
     */
    fun installUpdate() {
        val ready = _state.value.update as? UpdateState.Ready ?: return
        dismissUpdatePrompt()
        if (!app.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
            app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(update = UpdateState.Installing(ready)) }
            try {
                updater.install(ready.apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "update install failed", e)
                _state.update { it.copy(update = UpdateState.Failed(e.message ?: e.toString())) }
            }
        }
    }

    /**
     * Idioma do app (a Activity chama ao ser criada, inclusive depois de o usuário trocá-lo):
     * os textos do motor passam a vir nele.
     */
    fun setLocale(locale: Locale) {
        val language = engineLanguage(locale)
        if (language == this.language) return
        this.language = language
        launchEngine(null) { engine ->
            setup.join()
            applyLanguage(engine)
        }
    }

    private suspend fun applyLanguage(engine: SlicerEngine) {
        engine.setLanguage(language)
        val defs = PresetKind.entries.associateWith { engine.options(it) }
        val roles = SlicerEngine.roleNames.map { engine.translate(it) }
        val keys = SettingsTarget.Kind.entries.associateWith { engine.settingsKeys(it).toSet() }
        _state.update { it.copy(optionDefs = defs, roleLabels = roles, settingsKeys = keys) }
    }

    fun selectVendor(vendor: String) = launchEngine(R.string.busy_loading_vendor) {
        loadVendor(it, vendor)
        markChanged(StaleReason(vendor))
    }

    fun selectPreset(kind: PresetKind, name: String) = launchEngine(null) { engine ->
        engine.select(kind, name)
        markChanged(StaleReason(name))
        refreshPresets(engine)
        // A mesa muda com a impressora, e o modelo é recentralizado nela; a torre, com o processo.
        refreshScene(engine)
    }

    /** [value] no formato do Orca; erros de valor aparecem como mensagem. */
    fun setOption(kind: PresetKind, key: String, value: String) = launchEngine(null) { engine ->
        try {
            engine.setOption(kind, key, value)
            markChanged(StaleReason(optionLabel(kind, key)))
        } finally {
            // Também no erro: o campo volta a mostrar o valor em vigor.
            refreshPresets(engine)
        }
        // Mesa e volume vêm da impressora; a torre de purga, do processo.
        refreshScene(engine)
    }

    fun discardChanges(kind: PresetKind) = launchEngine(null) { engine ->
        engine.discardChanges(kind)
        markChanged(StaleReason(engine.selected(kind)))
        refreshPresets(engine)
        if (kind == PresetKind.PRINTER) refreshScene(engine)
    }

    fun savePreset(kind: PresetKind, name: String) = launchEngine(null) { engine ->
        engine.savePreset(kind, name.trim())
        refreshPresets(engine)
    }

    fun deletePreset(kind: PresetKind, name: String) = launchEngine(null) { engine ->
        engine.deletePreset(kind, name)
        // Excluir o preset em uso seleciona outro.
        engine.selected(kind)?.takeIf { it != _state.value.presets[kind]?.selected }?.let { markChanged(StaleReason(it)) }
        refreshPresets(engine)
        if (kind == PresetKind.PRINTER) refreshScene(engine)
    }

    fun importPreset(uri: Uri) = launchEngine(null) { engine ->
        val file = File(File(app.cacheDir, "import").apply { deleteRecursively(); mkdirs() }, displayName(uri))
        copy(uri, file)
        engine.importPreset(file)
        refreshPresets(engine)
    }

    fun exportPreset(kind: PresetKind, uri: Uri) = launchEngine(R.string.busy_saving) { engine ->
        val file = File(app.cacheDir, "export.json")
        engine.exportPreset(kind, file)
        write(file, uri)
    }

    /** Nome sugerido ao exportar: o do preset em edição. */
    fun presetFileName(kind: PresetKind): String = (_state.value.presets[kind]?.selected ?: "preset") + ".json"

    fun openModel(uri: Uri) = launchEngine(R.string.busy_loading_model) { engine -> addModel(engine, uri, displayName(uri)) }

    private suspend fun addModel(engine: SlicerEngine, uri: Uri, name: String) {
        val dir = File(app.cacheDir, "model").apply { deleteRecursively(); mkdirs() }
        // libslic3r detecta o formato pela extensão, então o nome original é mantido.
        val file = File(dir, name)
        copy(uri, file)
        engine.addModel(file)
        _state.update { it.copy(modelName = it.modelName ?: name) }
        markChanged(StaleReason(null))
        refreshScene(engine, engine.objects())
    }

    /**
     * Troca a mesa por um teste do menu Calibração ([SlicerEngine.calibrate]); o projeto fica com
     * o nome [name] e por salvar, e os perfis com os ajustes do teste.
     */
    fun calibrate(calibration: Calibration, name: String) = launchEngine(R.string.busy_opening) { engine ->
        setup.join()
        engine.calibrate(calibration)
        _state.update { it.copy(modelName = name, slice = SliceState.Idle, showPreview = false, dirty = true) }
        refreshPresets(engine)
        refreshScene(engine, engine.objects())
    }

    /**
     * Abre pelo tipo do arquivo: projeto 3MF (substitui objetos e perfis), G-code (direto
     * na pré-visualização) ou modelo STL/OBJ (acrescentado como em [openModel]).
     */
    fun open(uri: Uri) = launchEngine(R.string.busy_opening) { engine -> openFile(engine, uri, displayName(uri)) }

    /**
     * Arquivo de outro app ("Abrir com", compartilhar): como [open], guardando antes a mesa atual
     * num 3MF temporário para o Desfazer da mensagem ([undoOpen]).
     */
    fun openExternal(uri: Uri) = launchEngine(R.string.busy_opening) { engine ->
        setup.join()
        val name = displayName(uri)
        val before = _state.value
        val project = if (before.projectObjectCount > 0) {
            _state.update { it.copy(busy = R.string.busy_opening) }
            File(app.cacheDir, "undo.3mf").also { engine.saveProject(it, null) }
        } else null
        openFile(engine, uri, name)
        openUndo = OpenUndo(project, before.modelName, before.dirty)
        val text = app.getString(R.string.opened_external, name)
        _state.update { it.copy(message = UserMessage(text, success = true, action = MessageAction.UNDO)) }
    }

    /** Volta à mesa de antes do último [openExternal]. */
    fun undoOpen() {
        val undo = openUndo ?: return
        openUndo = null
        launchEngine(R.string.busy_opening) { engine ->
            if (undo.project != null) {
                val vendor = engine.openProject(undo.project)
                _state.update { it.copy(vendors = it.vendors.copy(selected = vendor)) }
                refreshPresets(engine)
                refreshScene(engine, engine.objects())
            } else {
                // A mesa estava vazia: tira o que entrou.
                repeat(_state.value.objectCount) { engine.remove(0) }
                refreshScene(engine, engine.objects())
            }
            _state.update { it.copy(modelName = undo.modelName, dirty = undo.dirty, slice = SliceState.Idle, showPreview = false) }
        }
    }

    /** Como [open]; o arquivo sai da lista se não puder mais ser lido. */
    fun openRecent(recent: RecentFile) = launchEngine(R.string.busy_opening) { engine ->
        try {
            openFile(engine, recent.uri, recent.name)
        } catch (e: UserException) {
            if (e.text == R.string.error_read_file) editRecents { files -> files.filter { it.uri != recent.uri } }
            throw e
        }
    }

    private suspend fun openFile(engine: SlicerEngine, uri: Uri, name: String) {
        setup.join()
        _state.update { it.copy(busy = R.string.busy_opening) }
        when (name.substringAfterLast('.', "").lowercase()) {
            "3mf" -> {
                val file = File(File(app.cacheDir, "model").apply { deleteRecursively(); mkdirs() }, name)
                copy(uri, file)
                val vendor = engine.openProject(file)
                _state.update { it.copy(vendors = it.vendors.copy(selected = vendor), modelName = name, slice = SliceState.Idle, showPreview = false, dirty = false) }
                refreshPresets(engine)
                refreshScene(engine, engine.objects())
            }
            "gcode" -> {
                // Fica como o G-code atual: "Salvar G-code" grava uma cópia dele.
                copy(uri, gcodeFile)
                val stats = engine.loadGcode(gcodeFile)
                val preview = withContext(Dispatchers.Default) { Preview(engine.toolpaths()) }
                _state.update { it.copy(modelName = name, slice = SliceState.Done(stats, preview), showPreview = true) }
            }
            else -> addModel(engine, uri, name)
        }
        rememberRecent(uri, name)
    }

    private fun rememberRecent(uri: Uri, name: String) {
        try {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // "Abrir com": a permissão vale só enquanto o app está aberto.
            return
        }
        editRecents { files -> listOf(RecentFile(uri, name)) + files.filter { it.uri != uri } }
    }

    /** Altera e grava a lista; quem sai dela devolve a permissão persistente. */
    private fun editRecents(edit: (List<RecentFile>) -> List<RecentFile>) {
        val old = _state.value.recents
        val recents = edit(old).take(RecentFilesStore.MAX)
        _state.update { it.copy(recents = recents) }
        for (dropped in old.filter { file -> recents.none { it.uri == file.uri } }) {
            try {
                app.contentResolver.releasePersistableUriPermission(dropped.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) { // já revogada
                Log.w(TAG, e)
            }
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { recentStore.save(recents) }
            } catch (e: IOException) {
                Log.w(TAG, e)
            }
        }
    }

    fun saveProject(uri: Uri) = launchEngine(R.string.busy_saving_project) { engine ->
        val thumbnail = thumbnails?.invoke(PROJECT_THUMBNAIL_SIZE, PROJECT_THUMBNAIL_SIZE)
        val file = File(app.cacheDir, "project.3mf")
        engine.saveProject(file, thumbnail)
        write(file, uri)
        val text = app.getString(if (thumbnail != null) R.string.project_saved_thumbnail else R.string.project_saved)
        _state.update { it.copy(dirty = false, message = UserMessage(text, success = true)) }
    }

    /** Nome sugerido para o projeto: o do modelo com a extensão trocada. */
    fun projectName(): String = (_state.value.modelName?.substringBeforeLast('.') ?: "model") + ".3mf"

    /** Põe na mesa os objetos de outra placa, no lugar dos atuais (que ficam na deles). */
    fun selectPlate(plate: Int) = editScene(modifies = false, samePlate = false) { engine ->
        engine.selectPlate(plate)
        engine.objects()
    }

    /** Placa nova e vazia, já na mesa. */
    fun addPlate() = editScene(samePlate = false) { engine ->
        engine.selectPlate(engine.addPlate())
        engine.objects()
    }

    /** Exclui a placa com os objetos dela, ou os passa para [moveTo]. */
    fun deletePlate(plate: Int, moveTo: Int?) = editScene(samePlate = false) { engine ->
        engine.deletePlate(plate, moveTo)
        engine.objects()
    }

    fun addFilament() = editFilaments { it.addFilament() }

    /** Os objetos e a pintura do filamento passam para o primeiro. */
    fun removeFilament(filament: Int) = editFilaments { it.removeFilament(filament) }

    fun setFilamentPreset(filament: Int, name: String) = editFilaments(StaleReason(name)) { it.setFilamentPreset(filament, name) }

    fun setFilamentColor(filament: Int, color: String) = editFilaments { it.setFilamentColor(filament, color) }

    /** Troca as cores dos filamentos ([colors], filamento -> "#RRGGBB") e fatia de novo. */
    fun recolorAndSlice(colors: Map<Int, String>) {
        viewModelScope.launch {
            editFilaments { engine -> colors.forEach { (filament, color) -> engine.setFilamentColor(filament, color) } }.join()
            slice()
        }
    }

    fun setObjectFilament(objectIndex: Int, filament: Int) = editFilaments { it.setObjectFilament(objectIndex, filament) }

    /** Purga da troca de [from] para [to] no extrusor [nozzle]. */
    fun setFlushVolume(nozzle: Int, from: Int, to: Int, volume: Double) = editFlush { engine ->
        val n = _state.value.filaments.size
        engine.setFlushVolumes(engine.flushVolumes().also { it[nozzle * n * n + from * n + to] = volume })
    }

    fun setFlushMultiplier(nozzle: Int, multiplier: Double) = editFlush { it.setFlushMultiplier(nozzle, multiplier) }

    fun calcFlushVolumes() = editFlush { it.calcFlushVolumes() }

    /** Muda a temperatura da mesa de cada filamento (a do tipo escolhido) e o G-code inicial. */
    fun setBedType(value: String) = launchEngine(null) { engine ->
        try {
            engine.setBedType(value)
            markChanged(StaleReason(app.getString(R.string.bed_type)))
        } finally {
            refreshPresets(engine)
        }
    }

    private fun editFlush(edit: suspend (SlicerEngine) -> Unit) = launchEngine(null) { engine ->
        try {
            edit(engine)
            markChanged(StaleReason(app.getString(R.string.flush_volumes)))
        } finally {
            refreshPresets(engine)
        }
    }

    /** Filamentos do projeto: mudam presets, cores e o filamento de cada triângulo. */
    private fun editFilaments(reason: StaleReason = StaleReason(null), edit: suspend (SlicerEngine) -> Unit) = launchEngine(null) { engine ->
        try {
            edit(engine)
            markChanged(reason)
        } finally {
            refreshPresets(engine)
            // Os mesmos objetos, com os filamentos novos: a seleção fica.
            refreshScene(engine, engine.objects(), keepSelection = true)
        }
    }

    fun moveToPlate(objectIndex: Int, plate: Int) = editScene { engine ->
        engine.moveToPlate(objectIndex, plate)
        _state.value.scene?.meshes?.filterIndexed { i, _ -> i != objectIndex }
    }

    fun duplicate(objectIndex: Int) = editScene { engine ->
        engine.duplicate(objectIndex)
        // A cópia vai para o fim, com a mesma malha.
        _state.value.scene?.meshes?.let { it + it[objectIndex] }
    }

    fun remove(objectIndex: Int) = editScene { engine ->
        engine.remove(objectIndex)
        val meshes = _state.value.scene?.meshes?.filterIndexed { i, _ -> i != objectIndex }
        if (meshes.isNullOrEmpty()) _state.update { it.copy(modelName = null) }
        meshes
    }

    fun arrange() = editScene { engine ->
        engine.arrange()
        null
    }

    /** [part]: uma peça do objeto, ou o objeto inteiro (null). */
    fun select(objectIndex: Int?, part: Int? = null) {
        _state.update { state ->
            state.copy(scene = state.scene?.copy(selected = objectIndex, selectedPart = part?.takeIf { objectIndex != null }))
        }
        if (_state.value.tool.paintKind != null) launchEngine(null) { refreshPainting(it) }
    }

    fun selectTool(tool: Tool) {
        _state.update { it.copy(tool = tool) }
        launchEngine(null) { refreshPainting(it) }
    }

    fun setBrush(brush: Brush) = _state.update { it.copy(brush = brush) }

    fun setPaintOnOverhangsOnly(only: Boolean) = _state.update { it.copy(paintOnOverhangsOnly = only) }

    fun setEdgeDetection(on: Boolean) = _state.update { it.copy(edgeDetection = on) }

    fun setHighlightAngle(angle: Float) = _state.update { state -> state.copy(painting = state.painting?.copy(highlightAngle = angle)) }

    /** "Preencher lacunas" com a área (mm²), ou de volta ao pincel (null). */
    fun setGapArea(area: Float?) {
        _state.update { it.copy(gapArea = area) }
        launchEngine(null) { refreshPainting(it) }
    }

    fun fillGaps() {
        val painting = _state.value.painting ?: return
        val area = _state.value.gapArea ?: return
        launchEngine(null) { engine ->
            if (engine.fillGaps(painting.objectIndex, painting.kind, area.toDouble()) > 0) markChanged(StaleReason(app.getString(paintLabel(painting.kind))))
            refreshPainting(engine)
        }
    }

    fun setClipRatio(ratio: Float) = _state.update { state -> state.copy(painting = state.painting?.copy(clipRatio = ratio)) }

    fun resetClipDirection() = _state.update { state -> state.copy(painting = state.painting?.let { it.copy(clipDirection = it.clipDirection + 1) }) }

    /** Raio pelo dedo sobre o objeto pintado (ver ViewportListener.onPaint); vai para a fila de [paintStrokes]. */
    fun paint(objectIndex: Int, origin: FloatArray, direction: FloatArray, strokeStart: Boolean, clip: ClipPlane?) {
        val state = _state.value
        // "Preencher lacunas" não pinta com o toque, como no Orca.
        val painting = state.painting?.takeIf { it.objectIndex == objectIndex && state.editable && (state.gapArea == null || !it.kind.hasGapFill) } ?: return
        // Cada pintura com as ferramentas e estados dela; as saliências só valem no suporte.
        val tool = state.brush.tool.takeIf { it in painting.kind.tools } ?: Brush.Tool.CIRCLE
        val paintState = state.brush.state.takeIf { it != PaintState.BLOCK || painting.kind.hasBlock } ?: PaintState.ENFORCE
        val overhang = if (painting.kind == PaintKind.SUPPORT && state.paintOnOverhangsOnly) painting.highlightAngle.toDouble() else 0.0
        val color = state.brush.color.coerceIn(1, state.scene?.filamentColors?.size?.coerceAtLeast(1) ?: 1)
        val angle = if (tool == Brush.Tool.BUCKET_FILL && !state.edgeDetection) -1.0 else state.brush.smartFillAngle
        val brush = state.brush.copy(tool = tool, state = paintState, overhangAngle = overhang, color = color, smartFillAngle = angle)
        paintStrokes.trySend(PaintRay(objectIndex, painting.kind, origin, direction, brush, strokeStart, clip))
    }

    fun clearPaint() {
        val painting = _state.value.painting ?: return
        launchEngine(null) { engine ->
            engine.clearPaint(painting.objectIndex, painting.kind)
            markChanged(StaleReason(app.getString(paintLabel(painting.kind))))
            refreshPainting(engine)
        }
    }

    private class PaintRay(
        val objectIndex: Int, val kind: PaintKind, val origin: FloatArray, val direction: FloatArray, val brush: Brush, val strokeStart: Boolean,
        val clip: ClipPlane?,
    )

    /** Toques da pintura, aplicados em ordem na thread do motor sem esperar o desenho de cada um. */
    private val paintStrokes = Channel<PaintRay>(Channel.UNLIMITED)
    private val paintJob = viewModelScope.launch { applyPaintStrokes(engine.await()) }

    private suspend fun applyPaintStrokes(engine: SlicerEngine) {
        while (true) {
            var ray = paintStrokes.receive()
            // Pontos seguidos de um arraste viram o último: o motor liga cada ponto ao anterior.
            var hit = false
            while (true) {
                val next = paintStrokes.tryReceive().getOrNull() ?: break
                if (next.strokeStart || next.objectIndex != ray.objectIndex || next.kind != ray.kind || next.brush != ray.brush) {
                    hit = paintRay(engine, ray) || hit
                }
                ray = next
            }
            hit = paintRay(engine, ray) || hit
            val painting = _state.value.painting
            if (!hit || painting?.objectIndex != ray.objectIndex || painting.kind != ray.kind) continue
            val mesh = runCatching { engine.painted(ray.objectIndex, ray.kind) }.getOrElse { continue }
            markChanged(StaleReason(app.getString(paintLabel(ray.kind))))
            _state.update { state -> state.copy(painting = state.painting?.takeIf { it.objectIndex == ray.objectIndex && it.kind == ray.kind }?.copy(mesh = mesh)) }
        }
    }

    private suspend fun paintRay(engine: SlicerEngine, ray: PaintRay): Boolean = try {
        engine.paint(ray.objectIndex, ray.kind, ray.origin, ray.direction, ray.brush, ray.strokeStart, ray.clip)
    } catch (e: SlicerException) {
        // Ex.: o objeto saiu da mesa entre o toque e a aplicação.
        Log.w(TAG, e)
        false
    }

    @StringRes
    private fun paintLabel(kind: PaintKind) = when (kind) {
        PaintKind.SUPPORT -> R.string.tool_paint_support
        PaintKind.SEAM -> R.string.tool_paint_seam
        PaintKind.FUZZY_SKIN -> R.string.tool_paint_fuzzy_skin
        PaintKind.COLOR -> R.string.tool_paint_color
    }

    /**
     * Relê a pintura do objeto selecionado com uma ferramenta de pintura (ou a fecha). Ao abrir
     * num objeto ou tipo novo, as saliências destacadas são as do gizmo do Orca. Ao fechar a
     * pintura de cor, relê as malhas, que passam a ter as cores pintadas.
     */
    private suspend fun refreshPainting(engine: SlicerEngine) {
        val state = _state.value
        val kind = state.tool.paintKind
        val selected = state.scene?.selected
        val closedColor = state.painting?.takeIf { it.kind == PaintKind.COLOR && (it.kind != kind || it.objectIndex != selected) } != null
        if (kind == null || selected == null) {
            _state.update { it.copy(painting = null, tool = if (selected == null && kind != null) Tool.MOVE else it.tool) }
            if (closedColor) refreshScene(engine, engine.objects(), keepSelection = true)
            return
        }
        val mesh = engine.painted(selected, kind)
        val gaps = state.gapArea?.takeIf { kind.hasGapFill }?.let { engine.gaps(selected, kind, it.toDouble()) }
        val previous = state.painting?.takeIf { it.objectIndex == selected && it.kind == kind }
        val angle = previous?.highlightAngle ?: if (kind == PaintKind.SUPPORT) engine.overhangHighlightAngle(selected).toFloat() else 0f
        _state.update {
            // O que mudou na tela enquanto o motor lia (corte, ângulo) fica.
            val current = it.painting?.takeIf { p -> p.objectIndex == selected && p.kind == kind }
            it.copy(painting = current?.copy(mesh = mesh, gaps = gaps) ?: Painting(selected, kind, mesh, angle, gaps))
        }
        if (closedColor) refreshScene(engine, engine.objects(), keepSelection = true)
    }

    fun showPreview(show: Boolean) = _state.update { it.copy(showPreview = show) }

    fun setTransform(objectIndex: Int, transform: FloatArray) = editScene { it.setTransform(objectIndex, transform); null }

    fun layOnFace(objectIndex: Int, triangle: Int) = editScene { it.layOnFace(objectIndex, triangle); null }

    /** Acrescenta (ou substitui, na mesma altura) uma ação por camada. */
    fun addLayerChange(change: LayerChange) = editLayerChanges { list -> list.filter { abs(it.z - change.z) > Z_EPSILON } + change }

    fun removeLayerChange(change: LayerChange) = editLayerChanges { list -> list - change }

    private fun editLayerChanges(edit: (List<LayerChange>) -> List<LayerChange>) = launchEngine(null) { engine ->
        try {
            engine.setLayerChanges(edit(engine.layerChanges()))
            markChanged(StaleReason(app.getString(R.string.layer_changes)))
        } finally {
            refreshScene(engine)
        }
    }

    /** Opção própria do objeto, da peça ou da faixa; [value] no formato do Orca. */
    fun setSetting(target: SettingsTarget, key: String, value: String) =
        editSettings(optionLabel(PresetKind.PROCESS, key)) { it.setSetting(target, key, value) }

    /** Volta a opção do alvo ao valor do processo. */
    fun eraseSetting(target: SettingsTarget, key: String) =
        editSettings(optionLabel(PresetKind.PROCESS, key)) { it.eraseSetting(target, key) }

    fun addHeightRange(objectIndex: Int, range: HeightRange) =
        editSettings(app.getString(R.string.height_ranges)) { it.addHeightRange(objectIndex, range) }

    fun setHeightRange(objectIndex: Int, index: Int, range: HeightRange) =
        editSettings(app.getString(R.string.height_ranges)) { it.setHeightRange(objectIndex, index, range) }

    fun removeHeightRange(objectIndex: Int, index: Int) =
        editSettings(app.getString(R.string.height_ranges)) { it.removeHeightRange(objectIndex, index) }

    private fun editSettings(reason: String, edit: suspend (SlicerEngine) -> Unit) = launchEngine(null) { engine ->
        try {
            edit(engine)
            markChanged(StaleReason(reason))
        } finally {
            refreshScene(engine)
        }
    }

    /** Peça nova com a forma (ver [SlicerEngine.addPart]), que fica selecionada. */
    fun addPart(objectIndex: Int, type: Part.Type, shape: Part.Shape) = editParts(objectIndex, type) { engine ->
        engine.addPart(objectIndex, type, shape)
    }

    /** Peça com a malha do arquivo (STL, OBJ, 3MF), na posição relativa que tinha nele; fica selecionada. */
    fun addPartFromFile(objectIndex: Int, type: Part.Type, uri: Uri) = editParts(objectIndex, type) { engine ->
        val dir = File(app.cacheDir, "part").apply { deleteRecursively(); mkdirs() }
        // libslic3r detecta o formato pela extensão, então o nome original é mantido.
        val file = File(dir, displayName(uri))
        copy(uri, file)
        engine.addPartFromFile(objectIndex, type, file)
    }

    /** A peça continua selecionada, no índice novo (a ordem segue o tipo). */
    fun setPartType(objectIndex: Int, part: Int, type: Part.Type) {
        // A malha do objeto muda se a peça entra nele ou sai dele.
        val changesObject = type == Part.Type.MODEL || partType(objectIndex, part) == Part.Type.MODEL
        editParts(objectIndex, if (changesObject) Part.Type.MODEL else type) { engine -> engine.setPartType(objectIndex, part, type) }
    }

    fun setPartTransform(objectIndex: Int, part: Int, transform: FloatArray) = editParts(objectIndex, partType(objectIndex, part)) { engine ->
        engine.setPartTransform(objectIndex, part, transform)
        part
    }

    fun setPartPlacement(objectIndex: Int, part: Int, offset: FloatArray, rotation: FloatArray, scale: FloatArray) =
        editParts(objectIndex, partType(objectIndex, part)) { engine ->
            engine.setPartPlacement(objectIndex, part, offset, rotation, scale)
            part
        }

    fun removePart(objectIndex: Int, part: Int) = editParts(objectIndex, partType(objectIndex, part)) { engine ->
        engine.removePart(objectIndex, part)
        null
    }

    private fun partType(objectIndex: Int, part: Int) = _state.value.scene?.parts?.getOrNull(objectIndex)?.getOrNull(part)?.type

    /**
     * Altera peças do objeto; [edit] devolve a peça a selecionar depois (ou null, o objeto). Só as
     * de [Part.Type.MODEL] mudam a malha do objeto.
     */
    private fun editParts(objectIndex: Int, type: Part.Type?, edit: suspend (SlicerEngine) -> Int?) = launchEngine(null) { engine ->
        var selectPart: Int? = _state.value.scene?.selectedPart
        try {
            selectPart = edit(engine)
            markChanged(StaleReason(app.getString(R.string.parts)))
        } finally {
            refreshScene(engine, if (type == Part.Type.MODEL) engine.objects() else null, keepSelection = true)
            select(objectIndex, selectPart)
        }
    }

    fun setWipeTowerPosition(x: Float, y: Float) = editScene { it.setWipeTowerPosition(x, y); null }

    fun setPlacement(objectIndex: Int, offset: FloatArray, rotation: FloatArray, scale: FloatArray) =
        editScene { it.setPlacement(objectIndex, offset, rotation, scale); null }

    fun slice() {
        sliceJob = launchEngine(null, retry = ::slice) { engine ->
            val plate = _state.value.plateNumber
            // Cancelado ou com erro, o G-code desatualizado continua à vista.
            val previous = _state.value.slice
            val running = { slice: SliceState.Running ->
                _state.update { it.copy(slice = slice) }
                SliceService.progress.value = SliceService.Progress(plate, slice)
            }
            running(SliceState.Running(0, SliceStage.PREPARE))
            SliceService.start(app)
            try {
                val thumbnails = engine.gcodeThumbnailSizes().mapNotNull { (width, height) -> this.thumbnails?.invoke(width, height) }
                val stats = engine.slice(gcodeFile, thumbnails) { percent, text ->
                    // Avisos chegam com -1 (PrintBase::SlicingStatus); o andamento não muda.
                    if (percent >= 0) {
                        val stage = SliceStage.of(percent)
                        val layer = if (stage == SliceStage.GCODE) LAST_NUMBER.find(text)?.value?.toIntOrNull() else null
                        running(SliceState.Running(percent, stage, layer))
                    }
                }
                val toolpaths = engine.toolpaths()
                val preview = withContext(Dispatchers.Default) { Preview(toolpaths) }
                val gcode3mf = engine.gcode3mf()
                _state.update { it.copy(slice = SliceState.Done(stats, preview, gcode3mf, System.currentTimeMillis()), showPreview = true) }
                SliceService.notifyDone(app, plate, stats)
            } finally {
                SliceService.progress.value = null
                _state.update { if (it.slice is SliceState.Running) it.copy(slice = previous) else it }
            }
        }
    }

    fun cancelSlice() {
        sliceJob?.cancel()
    }

    fun saveGcode(uri: Uri) = launchEngine(R.string.busy_saving) { engine ->
        write(printFile(engine), uri)
        _state.update { it.copy(message = UserMessage(app.getString(R.string.gcode_saved), success = true)) }
    }

    /**
     * Arquivo do último fatiamento no formato da impressora: o G-code, ou o .gcode.3mf com ele
     * e a miniatura da mesa.
     */
    private suspend fun printFile(engine: SlicerEngine): File {
        if ((_state.value.slice as? SliceState.Done)?.gcode3mf != true) return gcodeFile
        val file = File(app.cacheDir, "plate.gcode.3mf")
        engine.exportGcode3mf(gcodeFile, file, thumbnails?.invoke(PROJECT_THUMBNAIL_SIZE, PROJECT_THUMBNAIL_SIZE))
        return file
    }

    /** Acrescenta [host] ou substitui o de mesmo id. */
    fun savePrinter(host: PrinterHost) = editPrinters { printers ->
        if (printers.any { it.id == host.id }) printers.map { if (it.id == host.id) host else it } else printers + host
    }

    fun deletePrinter(id: String) = editPrinters { printers -> printers.filter { it.id != id } }

    private fun editPrinters(edit: (List<PrinterHost>) -> List<PrinterHost>) {
        val printers = edit(_state.value.printers)
        _state.update { it.copy(printers = printers) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { printerStore.save(printers) }
            } catch (e: IOException) {
                Log.w(TAG, e)
                _state.update { it.copy(message = UserMessage(app.getString(R.string.error_write_file))) }
            }
        }
    }

    /** Novo id para [PrinterHost]. */
    fun newPrinterId(): String = UUID.randomUUID().toString()

    /** Perfil de impressora em uso, que o diálogo de envio usa para sugerir a impressora. */
    fun printerPreset(): String? = _state.value.presets[PresetKind.PRINTER]?.selected

    /** Tipo de envio sugerido para uma impressora nova, pelo fabricante do perfil em uso. */
    fun printerKind(): Kind = if (_state.value.vendors.selected?.substringAfterLast('/') == BAMBU_VENDOR) Kind.BAMBU_LAN else Kind.MOONRAKER

    /** Se "Enviar e imprimir" vale para [host]. */
    fun canStart(host: PrinterHost): Boolean = uploader(host.kind, app.assets).canStart

    /**
     * Consulta o estado de cada uma de [printers] a cada [STATUS_INTERVAL_MS], em paralelo, até
     * ser cancelada (a tela de Impressoras ou o diálogo de envio saem).
     */
    suspend fun watchPrinterStatus(printers: List<PrinterHost>) = coroutineScope {
        for (host in printers) {
            launch {
                val uploader = uploader(host.kind, app.assets)
                while (true) {
                    val status = try {
                        uploader.status(host)
                    } catch (e: SendException) {
                        if (e.text == R.string.send_error_unreachable) PrinterStatus(PrinterStatus.State.OFFLINE)
                        else PrinterStatus(PrinterStatus.State.ERROR, raw = app.getString(e.text, *e.args))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "status of ${host.name}", e)
                        PrinterStatus(PrinterStatus.State.ERROR, raw = e.toString())
                    }
                    _state.update { it.copy(printerStatus = it.printerStatus + (host.id to status)) }
                    delay(STATUS_INTERVAL_MS)
                }
            }
        }
    }

    fun testPrinter(host: PrinterHost) = runSend(R.string.send_connecting) {
        val connection = uploader(host.kind, app.assets).test(host)
        SendState.Done(R.string.send_test_ok, host.name, connection)
    }

    /**
     * Filamento em cada posição de [host] para o mapeamento ao iniciar (vazia se ela não mapeia);
     * falha com [SendException].
     */
    suspend fun printerSlots(host: PrinterHost): List<Slot> = uploader(host.kind, app.assets).slots(host)

    /**
     * Envia o G-code fatiado para [host]; com [start], também inicia a impressão, com a posição
     * da impressora de cada filamento do projeto em [mapping] (Uploader.upload).
     */
    fun sendGcode(host: PrinterHost, start: Boolean = false, mapping: List<Slot?>? = null) = runSend(R.string.send_connecting) {
        val file = printFile(engine.await())
        val all = if (start) listOf(SendStep.CONNECT, SendStep.UPLOAD, SendStep.START) else listOf(SendStep.CONNECT, SendStep.UPLOAD)
        var steps = SendSteps(all, SendStep.CONNECT, 0, file.length())
        var stepStart = System.nanoTime()
        // Fecha a etapa atual com o tempo dela e passa para [next].
        val advance = { next: SendStep? ->
            val now = System.nanoTime()
            steps = steps.copy(current = next, millis = steps.millis + (steps.current!! to (now - stepStart) / 1_000_000))
            stepStart = now
        }
        _state.update { it.copy(send = SendState.Working(R.string.send_connecting, steps = steps)) }
        // O primeiro progresso chega com a conexão aberta.
        uploader(host.kind, app.assets).upload(
            host, file, gcodeName(), start,
            progress = { progress ->
                if (steps.current == SendStep.CONNECT) advance(SendStep.UPLOAD)
                steps = steps.copy(sent = (progress * steps.total).toLong())
                _state.update { it.copy(send = SendState.Working(R.string.send_uploading, progress, steps)) }
            },
            starting = {
                if (steps.current == SendStep.CONNECT) advance(SendStep.UPLOAD)
                advance(SendStep.START)
                _state.update { it.copy(send = SendState.Working(R.string.send_starting, steps = steps)) }
            },
            mapping = mapping,
        )
        if (steps.current == SendStep.CONNECT) advance(SendStep.UPLOAD) // arquivo vazio: sem progresso
        while (steps.current != null) advance(all.getOrNull(all.indexOf(steps.current) + 1))
        SendState.Done(if (start) R.string.send_started else R.string.send_done, host.name, steps = steps)
    }

    fun cancelSend() {
        sendJob?.cancel()
    }

    fun clearSendState() = _state.update { it.copy(send = SendState.Idle) }

    private fun runSend(@StringRes text: Int, block: suspend () -> SendState) {
        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            _state.update { it.copy(send = SendState.Working(text)) }
            val result = try {
                block()
            } catch (e: SendException) {
                Log.w(TAG, "send failed", e.cause)
                SendState.Failed(app.getString(e.text, *e.args))
            } catch (e: SlicerException) { // .gcode.3mf não gerado; já traduzida pelo motor
                SendState.Failed(e.message.orEmpty())
            } catch (e: CancellationException) {
                _state.update { it.copy(send = SendState.Idle) }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "unexpected send error", e)
                SendState.Failed(app.getString(R.string.error_unexpected, e.toString()))
            }
            _state.update { it.copy(send = result) }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    /** Repete a operação que falhou com [MessageAction.RETRY]. */
    fun retry() {
        retryAction?.invoke()
    }

    /** Nome sugerido para o G-code: o do modelo com a extensão trocada (.gcode ou .gcode.3mf). */
    fun gcodeName(): String {
        val extension = if ((_state.value.slice as? SliceState.Done)?.gcode3mf == true) ".gcode.3mf" else ".gcode"
        return (_state.value.modelName?.substringBeforeLast('.') ?: "model") + extension
    }

    private suspend fun loadVendor(engine: SlicerEngine, vendor: String) {
        engine.loadVendor(vendor)
        _state.update { it.copy(vendors = it.vendors.copy(selected = vendor)) }
        refreshPresets(engine)
        refreshScene(engine)
    }

    private suspend fun copy(uri: Uri, file: File) = withContext(Dispatchers.IO) {
        try {
            val input = app.contentResolver.openInputStream(uri) ?: throw IOException("no stream for $uri")
            input.use { file.outputStream().use(it::copyTo) }
        } catch (e: IOException) {
            throw UserException(R.string.error_read_file, e)
        } catch (e: SecurityException) { // permissão do provedor revogada
            throw UserException(R.string.error_read_file, e)
        }
    }

    private suspend fun write(file: File, uri: Uri) = withContext(Dispatchers.IO) {
        try {
            val output = app.contentResolver.openOutputStream(uri) ?: throw IOException("no stream for $uri")
            output.use { file.inputStream().use { input -> input.copyTo(it) } }
        } catch (e: IOException) {
            throw UserException(R.string.error_write_file, e)
        } catch (e: SecurityException) { // permissão do provedor revogada
            throw UserException(R.string.error_write_file, e)
        }
    }

    private suspend fun refreshPresets(engine: SlicerEngine) {
        val presets = PresetKind.entries.associateWith {
            PresetChoice(engine.presets(it), engine.selected(it), engine.userPresets(it).toSet(), engine.values(it))
        }
        val filaments = engine.filamentPresets()
        val filamentTypes = engine.filamentTypes()
        val limits = engine.filamentLimits()
        val flush = engine.flushVolumes()
        val multipliers = engine.flushMultipliers()
        val bedTypes = engine.bedTypes()
        _state.update {
            it.copy(
                presets = presets, filaments = filaments, filamentTypes = filamentTypes, filamentLimits = limits, flushVolumes = flush, flushMultipliers = multipliers,
                bedTypes = bedTypes,
            )
        }
    }

    /**
     * Relê mesa e posições do motor. [meshes]: nova lista de malhas quando os objetos
     * mudaram (a seleção é limpa, salvo com [keepSelection]); null mantém as atuais.
     */
    private suspend fun refreshScene(engine: SlicerEngine, meshes: List<ObjectMesh>? = null, keepSelection: Boolean = false) {
        val bed = engine.bed()
        val placements = engine.placements()
        val plates = engine.plates()
        val colors = engine.filamentColors().map(::rgba)
        val tower = engine.wipeTower()
        val layerChanges = engine.layerChanges()
        val filamentChange = engine.filamentChangeAvailable()
        val parts = placements.indices.map { engine.parts(it) }
        val settings = placements.indices.map { index ->
            val ranges = engine.heightRanges(index).mapIndexed { i, range ->
                RangeSettings(range, engine.settings(SettingsTarget(index, SettingsTarget.Kind.RANGE, i)))
            }
            val partSettings = parts[index].mapIndexed { i, part ->
                if (part.hasSettings) engine.settings(SettingsTarget(index, SettingsTarget.Kind.VOLUME, i)) else emptyMap()
            }
            ObjectSettings(engine.settings(SettingsTarget(index)), ranges, partSettings)
        }
        _state.update { state ->
            val selected = state.scene?.selected?.takeIf { (meshes == null || keepSelection) && it < placements.size }
            val selectedPart = state.scene?.selectedPart?.takeIf { selected != null && it < parts[selected].size }
            state.copy(
                scene = Scene(bed, meshes ?: state.scene?.meshes.orEmpty(), placements, selected, colors, tower, parts, selectedPart),
                plates = plates,
                layerChanges = layerChanges, filamentChangeAvailable = filamentChange, objectSettings = settings,
            )
        }
        refreshPainting(engine)
    }

    /**
     * Cena ou perfil mudaram: há o que salvar no projeto, e o G-code fatiado fica desatualizado
     * ([reason]). Um G-code aberto de arquivo não veio desta mesa e é descartado.
     */
    private fun markChanged(reason: StaleReason) = _state.update { state ->
        val slice = when (val done = state.slice) {
            is SliceState.Done -> if (done.slicedAt != null) done.copy(stale = reason) else SliceState.Idle
            else -> done
        }
        state.copy(dirty = true, slice = slice)
    }

    private fun optionLabel(kind: PresetKind, key: String) =
        _state.value.optionDefs[kind]?.firstOrNull { it.key == key }?.label ?: key

    /**
     * Altera a cena no motor; [edit] devolve as malhas novas, ou null se só posições mudaram.
     * [modifies]: há o que salvar no projeto; ![samePlate]: outra placa na mesa, e o G-code,
     * que era da anterior, é descartado.
     */
    private fun editScene(modifies: Boolean = true, samePlate: Boolean = true, edit: suspend (SlicerEngine) -> List<ObjectMesh>?) = launchEngine(null) { engine ->
        var meshes: List<ObjectMesh>? = null
        try {
            meshes = edit(engine)
            if (samePlate) markChanged(StaleReason(null))
            else _state.update { it.copy(slice = SliceState.Idle, dirty = it.dirty || modifies) }
        } finally {
            // Também em caso de erro: descarta a pré-visualização do viewport.
            refreshScene(engine, meshes)
        }
    }

    /** `#RRGGBB` ou `#RRGGBBAA` do Orca em RGBA de 0 a 1; cinza se não for uma cor. */
    private fun rgba(color: String): FloatArray {
        val hex = color.removePrefix("#")
        val value = hex.toLongOrNull(16)?.takeIf { hex.length == 6 || hex.length == 8 } ?: return floatArrayOf(0.6f, 0.6f, 0.6f, 1f)
        val rgb = if (hex.length == 8) value shr 8 else value
        return floatArrayOf(((rgb shr 16) and 0xff) / 255f, ((rgb shr 8) and 0xff) / 255f, (rgb and 0xff) / 255f, 1f)
    }

    private fun displayName(uri: Uri): String =
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "model.stl"

    /** [retry]: operação que a mensagem de erro oferece repetir. */
    private fun launchEngine(@StringRes busy: Int?, retry: (() -> Unit)? = null, block: suspend (SlicerEngine) -> Unit): Job = viewModelScope.launch {
        _state.update { it.copy(busy = busy, message = null) }
        val fail = { text: String, action: MessageAction?, details: String? ->
            retryAction = retry
            _state.update { it.copy(message = UserMessage(text, action = action ?: MessageAction.RETRY.takeIf { retry != null }, details = details)) }
        }
        try {
            block(engine.await())
        } catch (e: SlicerException) {
            // Já traduzida pelo motor.
            fail(e.message.orEmpty(), null, null)
        } catch (e: UserException) {
            Log.w(TAG, e.cause)
            fail(app.getString(e.text), MessageAction.PICK_OTHER.takeIf { e.text == R.string.error_read_file }, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "out of memory", e)
            fail(app.getString(R.string.error_out_of_memory), null, null)
        } catch (e: Exception) {
            // Falha do app, não do arquivo: avisa em vez de fechar e perder a mesa.
            Log.e(TAG, "unexpected error", e)
            fail(app.getString(R.string.error_unexpected_short), MessageAction.DETAILS, Log.getStackTraceString(e))
        } finally {
            _state.update { it.copy(busy = null) }
        }
    }

    /** Erro com mensagem do app para o usuário; [cause] vai só para o log. */
    private class UserException(@StringRes val text: Int, cause: Throwable) : Exception(cause)

    private companion object {
        const val TAG = "SlicerViewModel"
        /** Duas ações a menos que isto (mm) são da mesma camada. */
        const val Z_EPSILON = 0.001f
        const val DEFAULT_VENDOR = "BambuStudio/BBL"
        const val BAMBU_VENDOR = "BBL"
        /** Lado da miniatura do projeto, como THUMBNAIL_SIZE_3MF do Orca. */
        const val PROJECT_THUMBNAIL_SIZE = 512
        /** Intervalo da consulta do estado das impressoras. */
        const val STATUS_INTERVAL_MS = 5_000L
        /** Procura automática por versão nova no máximo a cada 12 h (a API do GitHub limita a 60/h). */
        const val UPDATE_CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
        /** Número da camada em "Generating G-code: layer N", em qualquer idioma. */
        val LAST_NUMBER = Regex("""\d+(?=\D*$)""")

        /** Catálogo de tradução do Orca para a localidade: os idiomas do app (res/values*). */
        fun engineLanguage(locale: Locale) = if (locale.language == "pt") "pt_BR" else "en"
    }
}
