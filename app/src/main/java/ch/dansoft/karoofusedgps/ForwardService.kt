package ch.dansoft.karoofusedgps

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import android.util.Log

/**
 * Leitet die GPS-Positionen des Karoo an den "fused"-Provider weiter.
 *
 * Hintergrund: Auf dem Karoo 3 liefert der Fused-Provider von Qualcomm keine Positionen.
 * Apps wie Trailforks (Mapbox) hören aber auf "fused" und bleiben deshalb stehen.
 * Dieser Dienst ersetzt "fused" durch einen Test-Provider und speist dort jede echte
 * GPS-Position ein. Der GPS-Provider selbst bleibt unangetastet (Karoo-App, Locus).
 *
 * Er hört passiv mit: Positionen entstehen nur, wenn das Karoo-System ohnehin GPS anfragt,
 * die App schaltet das GPS also nicht selbst ein und kostet kaum Strom.
 */
class ForwardService : Service() {

    companion object {
        private const val TAG = "KarooFusedGPS"
        const val FUSED = "fused"
        private const val CHANNEL = "forward"
        private const val NOTIF_ID = 1
        private const val PREFS = "settings"
        private const val KEY_ENABLED = "enabled"
        private const val RETRY_MS = 10_000L

        @Volatile var running = false
        @Volatile var providerReady = false
        @Volatile var forwarded = 0L
        @Volatile var lastFixTime = 0L
        @Volatile var lastError: String? = null

        fun isEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

        fun setEnabled(ctx: Context, enabled: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, enabled).apply()
            if (enabled) start(ctx) else ctx.stopService(Intent(ctx, ForwardService::class.java))
        }

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, ForwardService::class.java))
        }
    }

    private lateinit var lm: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var listening = false

    private val listener = LocationListener { loc -> onLocation(loc) }

    private val retry = object : Runnable {
        override fun run() {
            setup()
            if (!providerReady || !listening) handler.postDelayed(this, RETRY_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        startInForeground()
        running = true
        handler.post(retry)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
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
        if (!listening) {
            try {
                lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0L, 0f, listener, Looper.getMainLooper())
                listening = true
            } catch (e: Exception) {
                lastError = "Passiver Empfang: ${e.message}"
                Log.w(TAG, "requestLocationUpdates", e)
            }
        }
        if (providerReady && listening) lastError = null
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
        // Nur echte GPS-Positionen; die eigenen "fused"-Positionen kommen passiv auch zurück
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
            .setContentTitle("Karoo Fused GPS")
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
