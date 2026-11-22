package ch.dansoft.karoofusedgps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Startet die Weiterleitung nach dem Einschalten des Karoo (nur mit Autostart) und nach einem App-Update.
 *
 * Zwei Wege: LOCKED_BOOT_COMPLETED (früh, Receiver und Dienst sind directBootAware) und
 * BOOT_COMPLETED. Schlägt der Start fehl, wird er noch zweimal im Abstand von 5 s versucht
 * (Android erlaubt den Start eines Vordergrund-Dienstes nach dem Boot-Signal ca. 20 s lang).
 * Jeder Schritt steht im Log (Tag KarooFusedGPS).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(ForwardService.TAG, "Signal empfangen: $action")
        when (action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (!ForwardService.isEnabled(context)) {
                    Log.i(ForwardService.TAG, "Weiterleitung ausgeschaltet, kein Start")
                    return
                }
                // Nach einem App-Update immer weiterlaufen; beim Einschalten nur mit Autostart
                if (action != Intent.ACTION_MY_PACKAGE_REPLACED && !ForwardService.isAutostart(context)) {
                    Log.i(ForwardService.TAG, "Autostart ausgeschaltet, kein Start")
                    return
                }
                if (ForwardService.running) {
                    Log.i(ForwardService.TAG, "Dienst läuft schon")
                    return
                }
                val app = context.applicationContext
                val pending = goAsync()
                val handler = Handler(Looper.getMainLooper())
                fun attempt(n: Int) {
                    ForwardService.startLogged(app, "$action, Versuch $n")
                    handler.postDelayed({
                        if (!ForwardService.running && n < 3) attempt(n + 1)
                        else {
                            Log.i(ForwardService.TAG, "Dienst läuft: ${ForwardService.running}")
                            pending.finish()
                        }
                    }, 5_000)
                }
                attempt(1)
            }
        }
    }
}
