package ch.dansoft.karoofusedgps

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log

/**
 * Leitet die GPS-Positionen des Karoo an den "fused"-Provider weiter.
 *
 * Hintergrund: Auf dem Karoo 3 liefert der Fused-Provider von Qualcomm keine Positionen.
 * Apps wie Trailforks (Mapbox) hören aber auf "fused" und bleiben deshalb stehen.
 * Dieser Dienst ersetzt "fused" durch einen Test-Provider und speist dort jede echte
 * GPS-Position ein. Der GPS-Provider selbst bleibt unangetastet (Karoo-App, Locus).
 *
 * Er fragt das GPS selbst jede Sekunde an. Passives Mithören reicht nicht: Ist die Karoo-App
 * nicht im Vordergrund (z. B. Trailforks offen), fragt das Karoo-System GPS nur alle 30 min an.
 * Auf Wunsch (Schalter in der App) pausiert die GPS-Anfrage bei ausgeschaltetem Display.
 */
class ForwardService : Service() {

    companion object {
        const val TAG = "KarooFusedGPS"
        const val FUSED = "fused"
        private const val CHANNEL = "forward"
        private const val NOTIF_ID = 1
        private const val PREFS = "settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_AUTOSTART = "autostart"
        private const val KEY_SCREEN_OFF = "forward_when_screen_off"
        private const val ACTION_REFRESH = "ch.dansoft.karoofusedgps.REFRESH"
        private const val RETRY_MS = 10_000L
        private const val GPS_INTERVAL_MS = 1_000L

        @Volatile var running = false
        @Volatile var providerReady = false
        @Volatile var gpsActive = false
        @Volatile var forwarded = 0L
        @Volatile var lastFixTime = 0L
        @Volatile var lastError: String? = null

        // Einstellungen im geräteverschlüsselten Speicher: schon beim LOCKED_BOOT_COMPLETED lesbar
        private fun prefs(ctx: Context) =
            ctx.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, true)

        fun setEnabled(ctx: Context, enabled: Boolean) {
            prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
            if (enabled) start(ctx) else ctx.stopService(Intent(ctx, ForwardService::class.java))
        }

        /** Autostart beim Einschalten des Karoo (Grundeinstellung: ein). */
        fun isAutostart(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTOSTART, true)

        fun setAutostart(ctx: Context, on: Boolean) {
            prefs(ctx).edit().putBoolean(KEY_AUTOSTART, on).apply()
        }

        /** Auch bei ausgeschaltetem Display weiterleiten, damit Aufzeichnungen in Trailforks nicht unterbrechen (Grundeinstellung: ein). */
        fun isForwardWhenScreenOff(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SCREEN_OFF, true)

        fun setForwardWhenScreenOff(ctx: Context, on: Boolean) {
            prefs(ctx).edit().putBoolean(KEY_SCREEN_OFF, on).apply()
            if (running) ctx.startService(Intent(ctx, ForwardService::class.java).setAction(ACTION_REFRESH))
        }

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, ForwardService::class.java))
        }

        /** Start mit Protokoll; Fehler landen im Log und in der Statusanzeige. */
        fun startLogged(ctx: Context, reason: String) {
            Log.i(TAG, "Start angefordert: $reason")
            try {
                start(ctx)
            } catch (e: Exception) {
                lastError = "Start ($reason): ${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, "Start fehlgeschlagen ($reason)", e)
            }
        }
    }

    private lateinit var lm: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var listening: Boolean
        get() = gpsActive
        set(v) { gpsActive = v }

    private val listener = LocationListener { loc -> onLocation(loc) }

    // Display aus: GPS-Anfrage abmelden (spart Strom; die Karoo-App fragt für Aufzeichnungen
    // selbst an), ausser "auch bei Display aus weiterleiten" ist eingeschaltet.
    // Display an: sofort wieder anmelden. Der Fused-Ersatz bleibt eingerichtet.
    @Volatile private var screenOn = true

    private fun gpsWanted(): Boolean = screenOn || isForwardWhenScreenOff(this)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    if (!gpsWanted()) stopGps()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    handler.removeCallbacks(retry)
                    handler.post(retry)
                }
            }
        }
    }

    private val retry = object : Runnable {
        override fun run() {
            setup()
            if (!providerReady || (!listening && gpsWanted())) handler.postDelayed(this, RETRY_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Dienst startet")
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            startInForeground()
        } catch (e: Exception) {
            lastError = "Vordergrund-Dienst: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "startForeground fehlgeschlagen", e)
            stopSelf()
            return
        }
        running = true
        screenOn = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        handler.post(retry)
    }

    private fun stopGps() {
        handler.removeCallbacks(retry)
        if (listening) lm.removeUpdates(listener)
        listening = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH && running) {
            // Einstellung "auch bei Display aus" geändert
            if (gpsWanted()) {
                handler.removeCallbacks(retry)
                handler.post(retry)
            } else {
                stopGps()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) { }
        handler.removeCallbacksAndMessages(null)
        if (listening) lm.removeUpdates(listener)
        listening = false
        removeProvider()
        running = false
        super.onDestroy()
    }

    private fun setup() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            lastError = "Keine Standortberechtigung"
            return
        }
        if (!providerReady) addProvider()
        if (!listening && gpsWanted()) {
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_INTERVAL_MS, 0f, listener, Looper.getMainLooper())
                listening = true
            } catch (e: Exception) {
                lastError = "GPS-Empfang: ${e.message}"
                Log.w(TAG, "requestLocationUpdates", e)
            }
        }
        if (providerReady && (listening || !gpsWanted())) lastError = null
    }

    @Suppress("DEPRECATION")
    private fun addProvider() {
        try {
            try { lm.removeTestProvider(FUSED) } catch (_: Exception) { }
            lm.addTestProvider(
                FUSED,
                false, false, false, false,
                true, true, true,
                Criteria.POWER_LOW, Criteria.ACCURACY_FINE
            )
            lm.setTestProviderEnabled(FUSED, true)
            providerReady = true
            Log.i(TAG, "Test-Provider 'fused' aktiv")
        } catch (e: SecurityException) {
            providerReady = false
            lastError = "Nicht als \"App für simulierte Standorte\" gewählt"
            Log.w(TAG, "addTestProvider", e)
        } catch (e: Exception) {
            providerReady = false
            lastError = "Test-Provider: ${e.message}"
            Log.w(TAG, "addTestProvider", e)
        }
    }

    private fun removeProvider() {
        try { lm.removeTestProvider(FUSED) } catch (_: Exception) { }
        providerReady = false
    }

    private fun onLocation(loc: Location) {
        // Nur echte GPS-Positionen
        if (loc.provider != LocationManager.GPS_PROVIDER || loc.isFromMockProvider) return
        if (!providerReady) return
        try {
            val f = Location(loc)
            f.provider = FUSED
            lm.setTestProviderLocation(FUSED, f)
            forwarded++
            lastFixTime = System.currentTimeMillis()
        } catch (e: Exception) {
            // z. B. Mock-Erlaubnis entzogen: neu einrichten
            providerReady = false
            lastError = "Weiterleiten: ${e.message}"
            Log.w(TAG, "setTestProviderLocation", e)
            handler.removeCallbacks(retry)
            handler.postDelayed(retry, RETRY_MS)
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "GPS-Weiterleitung", NotificationManager.IMPORTANCE_MIN)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("GPS wird an \"fused\" weitergeleitet")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }
}
