package com.naveen.deliverymap

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var rapido: EditText
    private lateinit var porter: EditText
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        fun title(t: String) = TextView(this).apply { text = t; textSize = 22f; setPadding(0, 0, 0, dp(8)) }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 14f; setPadding(0, dp(12), 0, dp(4)) }
        fun btn(t: String, a: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { a() } }

        col.addView(title("Delivery Map"))
        status = TextView(this).apply { textSize = 15f; setPadding(0, 0, 0, dp(8)) }
        col.addView(status)

        col.addView(label("Setup (do once, in order)"))
        col.addView(btn("1. Allow location (choose 'Allow all the time')") { askLocation() })
        col.addView(btn("2. Turn on Accessibility service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(btn("3. If step 2 is greyed out: open App info") { openAppInfo() })
        col.addView(btn("4. Battery: No restrictions") { batterySettings() })
        col.addView(btn("5. Xiaomi Autostart: turn ON") { autostart() })

        col.addView(label("Test"))
        col.addView(btn("Show sample map now") {
            val s = DeliveryAccessibilityService.instance
            if (s == null) Toast.makeText(this, "Turn on the Accessibility service first", Toast.LENGTH_LONG).show()
            else s.demo()
        })

        col.addView(label("App package names (change only if popups are not detected)"))
        rapido = EditText(this).apply { setText(Prefs.rapidoRaw(this@MainActivity)); setSingleLine(true) }
        porter = EditText(this).apply { setText(Prefs.porterRaw(this@MainActivity)); setSingleLine(true) }
        col.addView(TextView(this).apply { text = "Rapido" })
        col.addView(rapido)
        col.addView(TextView(this).apply { text = "Porter" })
        col.addView(porter)
        col.addView(btn("Save package names") {
            Prefs.save(this, rapido.text.toString(), porter.text.toString())
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        })

        setContentView(ScrollView(this).apply {
            addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    override fun onResume() { super.onResume(); handler.post(refresher) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(refresher) }

    private fun refresh() {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val bg = Build.VERSION.SDK_INT < 29 ||
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        val svc = DeliveryAccessibilityService.instance != null
        fun mark(b: Boolean) = if (b) "✅" else "❌"
        status.text = "${mark(fine)} Location\n" +
            "${mark(bg)} Location all the time\n" +
            "${mark(svc)} Accessibility service running\n\n" +
            "Last read: ${DeliveryAccessibilityService.lastInfo}" +
            (DeliveryAccessibilityService.unknownPackage.let {
                if (it.isNotEmpty()) "\nSaw ₹ text in app: $it (if this is Rapido/Porter, put it in package names)" else ""
            })
    }

    private fun askLocation() {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        } else if (Build.VERSION.SDK_INT >= 29) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
        }
    }

    private fun openAppInfo() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        Toast.makeText(this, "Tap the ⋮ menu (top right) → Allow restricted settings", Toast.LENGTH_LONG).show()
    }

    private fun batterySettings() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } else {
                Toast.makeText(this, "Already allowed. Also set Battery saver → No restrictions in App info.", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) { openAppInfo() }
    }

    private fun autostart() {
        try {
            startActivity(Intent().setComponent(ComponentName(
                "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
        } catch (e: Exception) {
            Toast.makeText(this, "Open Settings → Apps → Permissions → Autostart", Toast.LENGTH_LONG).show()
        }
    }
}
