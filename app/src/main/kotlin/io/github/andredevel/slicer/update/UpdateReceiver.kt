package io.github.andredevel.slicer.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast
import androidx.core.content.IntentCompat
import io.github.andredevel.slicer.R

/** Resultado da sessão do PackageInstaller aberta por [AppUpdater.install]. */
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
            PackageInstaller.STATUS_FAILURE_ABORTED -> {}
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "update failed: $status $message")
                Toast.makeText(context, context.getString(R.string.update_install_failed, message ?: status.toString()), Toast.LENGTH_LONG).show()
            }
        }
    }

    private companion object {
        const val TAG = "UpdateReceiver"
    }
}
