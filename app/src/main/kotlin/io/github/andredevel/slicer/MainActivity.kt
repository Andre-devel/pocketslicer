package io.github.andredevel.slicer

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.IntentCompat
import io.github.andredevel.slicer.ui.theme.PocketSlicerTheme

class MainActivity : ComponentActivity() {
    private val viewModel: SlicerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Também quando recriada porque o usuário trocou o idioma.
        viewModel.setLocale(resources.configuration.locales[0])
        // Recriada (rotação...): o arquivo do intent já foi aberto.
        if (savedInstanceState == null) openFrom(intent)
        setContent {
            // Claro ou escuro conforme Ajustes (ou o sistema), sempre com a paleta do app.
            val mode by viewModel.theme.collectAsState()
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Ícones da barra de status e de navegação legíveis sobre o tema escolhido.
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
            }
            val scale by viewModel.uiScale.collectAsState()
            PocketSlicerTheme(dark) {
                UiScale(scale ?: defaultUiScale(resources.configuration.smallestScreenWidthDp)) {
                    SlicerScreen(viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    /** "Abrir com" e compartilhar de outro app (RF-01); a notificação de fatiamento concluído. */
    private fun openFrom(intent: Intent) {
        if (intent.action == SliceService.ACTION_SHOW_PREVIEW) {
            viewModel.showPreview(true)
            return
        }
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        uri?.let(viewModel::openExternal)
    }
}

/**
 * Escala a interface toda em [percent]% (Ajustes): a densidade, e as medidas da tela em dp
 * em [LocalConfiguration], para que os pontos de quebra do layout vejam a tela escalada.
 * O viewport 3D trabalha em pixels e não muda.
 */
@Composable
private fun UiScale(percent: Int, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val factor = percent / 100f
    val scaledDensity = remember(density, factor) { Density(density.density * factor, density.fontScale) }
    val scaledConfiguration = remember(configuration, factor) {
        Configuration(configuration).apply {
            screenWidthDp = (screenWidthDp / factor).toInt()
            screenHeightDp = (screenHeightDp / factor).toInt()
            smallestScreenWidthDp = (smallestScreenWidthDp / factor).toInt()
        }
    }
    CompositionLocalProvider(LocalDensity provides scaledDensity, LocalConfiguration provides scaledConfiguration, content = content)
}

// Os véus padrão de enableEdgeToEdge() na barra de navegação de três botões.
private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
