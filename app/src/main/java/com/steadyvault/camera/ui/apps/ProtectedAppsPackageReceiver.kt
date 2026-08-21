package com.steadyvault.camera.ui.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.steadyvault.camera.storage.security.ProtectedAppsIndexStore
import com.steadyvault.camera.storage.security.ProtectedAppsStore

/** Invalida somente o índice quando a tela inicial muda. */
class ProtectedAppsPackageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.data?.schemeSpecificPart.orEmpty()
        if (intent.action == Intent.ACTION_PACKAGE_REMOVED && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
            if (packageName.isNotBlank()) ProtectedAppsStore.setSelected(context, packageName, false)
        }
        ProtectedAppsIndexStore.invalidate(context)
    }
}
