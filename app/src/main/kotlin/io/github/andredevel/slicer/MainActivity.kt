package io.github.andredevel.slicer

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
            PocketSlicerTheme(dark) {
                SlicerScreen(viewModel)
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

// Os véus padrão de enableEdgeToEdge() na barra de navegação de três botões.
private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
