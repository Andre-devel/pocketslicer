package io.github.andredevel.slicer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.andredevel.slicer.core.PresetKind
import io.github.andredevel.slicer.core.Thumbnail
import io.github.andredevel.slicer.render.PreviewSettings
import io.github.andredevel.slicer.render.ViewportListener
import io.github.andredevel.slicer.render.CameraView
import io.github.andredevel.slicer.render.ViewportView
import io.github.andredevel.slicer.ui.components.AppMark
import io.github.andredevel.slicer.ui.components.AppNavigationBar
import io.github.andredevel.slicer.ui.components.AppNavigationRail
import io.github.andredevel.slicer.ui.components.MenuPill
import io.github.andredevel.slicer.ui.components.NavItem
import io.github.andredevel.slicer.ui.components.PillTabs
import io.github.andredevel.slicer.ui.components.blockTouches
import kotlin.math.roundToInt

/** Destinos da navegação principal. */
enum class Destination(@DrawableRes val icon: Int, @StringRes val label: Int) {
    PREPARE(R.drawable.ic_deployed_code, R.string.prepare),
    PREVIEW(R.drawable.ic_layers, R.string.preview),
    PARAMETERS(R.drawable.ic_tune, R.string.parameters),
    PRINTERS(R.drawable.ic_print, R.string.printers),
}

/** Arquivos escolhidos pelo seletor do sistema; uma instância por tela. */
class FilePickers(
    val openFile: () -> Unit,
    val addModel: () -> Unit,
    val saveProject: () -> Unit,
    val saveGcode: () -> Unit,
)

/**
 * Tela principal (RF-50/52): navegação (lateral no tablet, inferior no celular) entre Preparar,
 * Visualizar, Parâmetros e Impressoras. Preparar e Visualizar dividem o mesmo viewport 3D, que
 * fica sempre composto: Parâmetros e Impressoras são desenhados por cima dele.
 */
@Composable
fun SlicerScreen(viewModel: SlicerViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // STL/3MF chegam com MIME variado (model/stl, application/octet-stream...).
    val openModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::openModel) }
    val saveGcode = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let(viewModel::saveGcode)
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::open) }
    val saveProject = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("model/3mf")) { uri ->
        uri?.let(viewModel::saveProject)
    }
    val pickers = FilePickers(
        openFile = { openFile.launch(arrayOf("*/*")) },
        addModel = { openModel.launch(arrayOf("*/*")) },
        saveProject = { saveProject.launch(viewModel.projectName()) },
        saveGcode = { saveGcode.launch(viewModel.gcodeName()) },
    )

    LaunchedEffect(state.error) {
        state.error?.let {
            // Até o usuário fechar: a mensagem explica por que algo não aconteceu.
            snackbar.showSnackbar(it, withDismissAction = true, duration = SnackbarDuration.Indefinite)
            viewModel.dismissError()
        }
    }

    // Parâmetros ou Impressoras por cima da área de trabalho; null mostra Preparar/Visualizar,
    // conforme a pré-visualização do ViewModel.
    var page by rememberSaveable { mutableStateOf<Destination?>(null) }
    var filter by rememberSaveable(stateSaver = ParameterFilterSaver) { mutableStateOf(ParameterFilter()) }
    val current = page ?: if (state.previewVisible) Destination.PREVIEW else Destination.PREPARE
    val navigate = { destination: Destination ->
        when (destination) {
            Destination.PREPARE, Destination.PREVIEW -> {
                page = null
                viewModel.showPreview(destination == Destination.PREVIEW)
            }
            else -> page = destination
        }
    }
    val openParameters = { kind: PresetKind ->
        filter = filter.copy(kind = kind)
        page = Destination.PARAMETERS
    }
    val items = Destination.entries.map {
        NavItem(it.icon, stringResource(it.label), enabled = it != Destination.PREVIEW || state.preview != null)
    }

    val configuration = LocalConfiguration.current
    val rail = configuration.screenWidthDp >= RAIL_MIN_WIDTH_DP
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!rail) AppNavigationBar(items, current.ordinal, { navigate(Destination.entries[it]) })
        },
    ) { padding ->
        Row(Modifier.padding(padding).fillMaxSize()) {
            if (rail) {
                AppNavigationRail(
                    items, current.ordinal, { navigate(Destination.entries[it]) },
                    header = { AppMark(stringResource(R.string.app_mark)) },
                    footer = { OnDeviceBadge() },
                )
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Workspace(state, viewModel, pickers, openParameters, onAddPrinter = { page = Destination.PRINTERS })
                val overlay = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).blockTouches()
                when (page) {
                    Destination.PARAMETERS -> ParametersScreen(state, viewModel, filter, { filter = it }, overlay)
                    Destination.PRINTERS -> PrintersScreen(state, viewModel, overlay)
                    else -> {}
                }
            }
        }
    }
}

/** Rodapé da navegação lateral: lembra que tudo roda no aparelho. */
@Composable
private fun OnDeviceBadge() {
    Icon(painterResource(R.drawable.ic_cloud_off), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.outline)
    Text(
        stringResource(R.string.on_device),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        textAlign = TextAlign.Center,
    )
}

/**
 * Preparar ou Visualizar (com a pré-visualização visível): barra superior, viewport com os
 * controles por cima e o painel (à direita na tela larga, bottom sheet na estreita).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Workspace(
    state: SlicerUiState,
    viewModel: SlicerViewModel,
    pickers: FilePickers,
    openParameters: (PresetKind) -> Unit,
    onAddPrinter: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val wide = configuration.screenWidthDp >= PANEL_MIN_WIDTH_DP
    val previewing = state.previewVisible
    // Barras e legenda da pré-visualização; recomeçam com cada fatiamento.
    var previewSettings by remember(state.preview) { mutableStateOf(PreviewSettings()) }
    var sending by rememberSaveable { mutableStateOf(false) }
    val send = { sending = true }

    val header = @Composable {
        if (previewing) PreviewHeader(state, viewModel, pickers, compact = !wide)
        else PrepareHeader(state, viewModel, pickers, compact = !wide)
    }
    var viewport by remember { mutableStateOf<ViewportView?>(null) }
    var cameraView by remember { mutableStateOf<CameraView?>(null) }
    val viewPicker = @Composable { modifier: Modifier ->
        CameraViewPicker(cameraView, { viewport?.setView(it) }, compact = !wide, modifier)
    }

    val viewportArea = @Composable { modifier: Modifier ->
        Box(modifier) {
            Viewport(state, viewModel, previewSettings, { viewport = it }, { cameraView = it }, Modifier.fillMaxSize())
            val preview = state.preview
            val done = state.slice as? SliceState.Done
            if (previewing && preview != null && done != null) {
                PreviewOverlay(preview, state.roleLabels, previewSettings, { previewSettings = it }, compact = !wide, viewPicker)
            } else {
                PrepareOverlay(state, viewModel, pickers, compact = !wide, viewPicker)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        header()
        if (wide) {
            Row(Modifier.weight(1f)) {
                viewportArea(Modifier.weight(1f).fillMaxHeight())
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Surface(Modifier.width(PANEL_WIDTH).fillMaxHeight(), color = MaterialTheme.colorScheme.surface) {
                    if (previewing) PreviewPanel(state, pickers, send)
                    else PreparePanel(state, viewModel, openParameters)
                }
            }
        } else {
            val sheetMaxHeight = configuration.screenHeightDp.dp * 0.7f
            BottomSheetScaffold(
                modifier = Modifier.weight(1f),
                sheetPeekHeight = SHEET_PEEK_HEIGHT,
                sheetContainerColor = MaterialTheme.colorScheme.surface,
                sheetContent = {
                    Box(Modifier.fillMaxWidth().heightIn(max = sheetMaxHeight)) {
                        if (previewing) PreviewSheet(state, pickers, send)
                        else PrepareSheet(state, viewModel, pickers, openParameters)
                    }
                },
            ) {
                // O viewport ocupa a área toda; o sheet recolhido cobre só a faixa de baixo.
                viewportArea(Modifier.fillMaxSize())
            }
        }
    }
    if (sending) SendDialog(state, viewModel, onAddPrinter = { sending = false; onAddPrinter() }) { sending = false }
}

@Composable
private fun Viewport(
    state: SlicerUiState,
    viewModel: SlicerViewModel,
    previewSettings: PreviewSettings,
    onCreated: (ViewportView) -> Unit,
    onCameraView: (CameraView?) -> Unit,
    modifier: Modifier,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var view by remember { mutableStateOf<ViewportView?>(null) }
    // Menu do toque longo: objeto e posição do toque (px).
    var menu by remember { mutableStateOf<Pair<Int, Offset>?>(null) }
    // GLSurfaceView precisa acompanhar onPause/onResume da tela; o viewport também fornece as
    // miniaturas do projeto e do G-code enquanto existe.
    DisposableEffect(lifecycle, view) {
        val target = view ?: return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> target.onResume()
                Lifecycle.Event.ON_PAUSE -> target.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        val thumbnails: suspend (Int, Int) -> Thumbnail? = target::thumbnail
        viewModel.thumbnails = thumbnails
        onDispose {
            lifecycle.removeObserver(observer)
            if (viewModel.thumbnails === thumbnails) viewModel.thumbnails = null
        }
    }
    Box(modifier) {
        AndroidView(
            factory = { context ->
                ViewportView(context).also { created ->
                    created.listener = object : ViewportListener {
                        override fun onSelect(objectIndex: Int?) = viewModel.select(objectIndex)
                        override fun onTransform(objectIndex: Int, transform: FloatArray) { viewModel.setTransform(objectIndex, transform) }
                        override fun onLayOnFace(objectIndex: Int, triangle: Int) { viewModel.layOnFace(objectIndex, triangle) }
                        override fun onLongPress(objectIndex: Int, x: Float, y: Float) {
                            viewModel.select(objectIndex)
                            menu = objectIndex to Offset(x, y)
                        }
                        override fun onCameraView(view: CameraView?) = onCameraView(view)
                    }
                    view = created
                    onCreated(created)
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = {
                it.setScene(state.scene)
                it.tool = state.tool
                it.setPreview(state.preview, state.previewVisible, previewSettings)
            },
        )
        menu?.let { (objectIndex, position) ->
            Box(Modifier.offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }) {
                DropdownMenu(expanded = true, onDismissRequest = { menu = null }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.duplicate)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_content_copy), null) },
                        onClick = {
                            menu = null
                            viewModel.duplicate(objectIndex)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_delete), null) },
                        onClick = {
                            menu = null
                            viewModel.remove(objectIndex)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Vistas prontas da câmera ([current] marcada; null depois de um gesto): abas no tablet, menu
 * no celular.
 */
@Composable
fun CameraViewPicker(current: CameraView?, onView: (CameraView) -> Unit, compact: Boolean, modifier: Modifier = Modifier) {
    val labels = CAMERA_VIEWS.map { stringResource(it.second) }
    val selected = CAMERA_VIEWS.indexOfFirst { it.first == current }
    val select = { index: Int -> onView(CAMERA_VIEWS[index].first) }
    if (compact) MenuPill(stringResource(R.string.camera_view), labels, selected, select, modifier, icon = R.drawable.ic_visibility)
    else PillTabs(labels, selected, select, modifier)
}

private val CAMERA_VIEWS = listOf(
    CameraView.HOME to R.string.view_home,
    CameraView.TOP to R.string.view_top,
    CameraView.FRONT to R.string.view_front,
)

/** Navegação lateral a partir desta largura; abaixo, barra inferior. */
private const val RAIL_MIN_WIDTH_DP = 600

/** Painel lateral a partir desta largura; abaixo, bottom sheet. */
private const val PANEL_MIN_WIDTH_DP = 840
private val PANEL_WIDTH = 340.dp
private val SHEET_PEEK_HEIGHT = 128.dp

private val ParameterFilterSaver = listSaver<ParameterFilter, Any>(
    save = { listOf(it.kind.ordinal, it.advanced, it.query) },
    restore = { ParameterFilter(PresetKind.entries[it[0] as Int], it[1] as Boolean, it[2] as String) },
)
