package com.naveen.deliverymap

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.delay

class LocationProvider(private val ctx: Context) {
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Volatile
    var current: Location? = null
        private set
    private var running = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            val c = current
            if (c == null || l.time >= c.time || l.accuracy < c.accuracy) current = l
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private fun hasPermission() =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (running || !hasPermission()) return
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper())
                    lm.getLastKnownLocation(p)?.let { listener.onLocationChanged(it) }
                }
            }
            running = true
        } catch (_: SecurityException) {
        }
    }

    fun stop() {
        if (!running) return
        try { lm.removeUpdates(listener) } catch (_: Exception) {}
        running = false
    }

    /** Instant answer: last known location (max 10 min old), no waiting. */
    fun quick(): LatLng? {
        val c = current ?: return null
        return if (System.currentTimeMillis() - c.time < 600_000) LatLng(c.latitude, c.longitude) else null
    }

    /** Waits briefly for a fresh fix; falls back to the last known one (max 10 min old). */
    suspend fun get(timeoutMs: Long): LatLng? {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            val c = current
            if (c != null && System.currentTimeMillis() - c.time < 20_000) return LatLng(c.latitude, c.longitude)
            delay(150)
        }
        val c = current ?: return null
        return if (System.currentTimeMillis() - c.time < 600_000) LatLng(c.latitude, c.longitude) else null
    }
}
