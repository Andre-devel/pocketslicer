package io.github.andredevel.slicer.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Resultado da instalação que não fecha o app. */
sealed interface InstallResult {
    /** Confirmação do sistema recusada. */
    data object Aborted : InstallResult
    data class Failed(val message: String) : InstallResult
}

/** Resultado da sessão do PackageInstaller aberta por [AppUpdater.install], repassado em [results]. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            // Primeira atualização (o app foi instalado por outro instalador) ou Android 11 e antes.
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            // Não costuma chegar: o Android encerra o app ao substituí-lo.
            PackageInstaller.STATUS_SUCCESS -> {}
            PackageInstaller.STATUS_FAILURE_ABORTED -> _results.tryEmit(InstallResult.Aborted)
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: status.toString()
                Log.w(TAG, "update failed: $status $message")
                _results.tryEmit(InstallResult.Failed(message))
            }
        }
    }

    companion object {
        private const val TAG = "UpdateReceiver"
        private val _results = MutableSharedFlow<InstallResult>(extraBufferCapacity = 4)
        /** Coletado pelo SlicerViewModel, que vive no mesmo processo. */
        val results: SharedFlow<InstallResult> = _results
    }
}
