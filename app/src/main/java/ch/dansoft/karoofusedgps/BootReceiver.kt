package ch.dansoft.karoofusedgps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Startet die Weiterleitung nach dem Einschalten des Karoo und nach einem App-Update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                if (ForwardService.isEnabled(context)) ForwardService.start(context)
        }
    }
}
