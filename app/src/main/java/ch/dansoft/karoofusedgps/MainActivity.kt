package ch.dansoft.karoofusedgps

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** Ein Schalter "Weiterleitung" und eine Statusanzeige. */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView

    private val refresh = object : Runnable {
        override fun run() {
            status.text = statusText()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        }
        val switch = Switch(this).apply {
            text = "Weiterleitung GPS → fused"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            isChecked = ForwardService.isEnabled(this@MainActivity)
            setPadding(0, pad, 0, pad)
            setOnCheckedChangeListener { _, on -> ForwardService.setEnabled(this@MainActivity, on) }
        }
        status = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.START
        }
        root.addView(title)
        root.addView(switch)
        root.addView(status)
        setContentView(root)

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1
            )
        }
        if (ForwardService.isEnabled(this) && !ForwardService.running) ForwardService.start(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (ForwardService.isEnabled(this)) ForwardService.start(this)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun statusText(): String {
        if (!ForwardService.running) return "Weiterleitung aus"
        val sb = StringBuilder()
        sb.append(if (ForwardService.providerReady) "Fused-Ersatz aktiv\n" else "Fused-Ersatz nicht aktiv\n")
        sb.append("Weitergeleitet: ${ForwardService.forwarded} Positionen\n")
        val last = ForwardService.lastFixTime
        if (ForwardService.gpsActive) sb.append("GPS-Anfrage aktiv\n") else sb.append("GPS-Anfrage pausiert\n")
        if (last > 0) {
            val s = (System.currentTimeMillis() - last) / 1000
            sb.append("Letzte Position vor $s s\n")
        } else {
            sb.append("Noch keine GPS-Position\n")
        }
        ForwardService.lastError?.let { sb.append("\nProblem: $it") }
        return sb.toString()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
