package io.github.andredevel.slicer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
        enableEdgeToEdge()
        setContent {
            // Claro ou escuro conforme o sistema, sempre com a paleta do app.
            PocketSlicerTheme {
                SlicerScreen(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    /** "Abrir com" e compartilhar de outro app (RF-01). */
    private fun openFrom(intent: Intent) {
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        uri?.let(viewModel::openExternal)
    }
}
