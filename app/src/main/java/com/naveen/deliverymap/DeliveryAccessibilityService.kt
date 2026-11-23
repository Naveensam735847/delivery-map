package com.naveen.deliverymap

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeliveryAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: DeliveryAccessibilityService? = null
        @Volatile var lastInfo: String = "Nothing read yet"
        @Volatile var unknownPackage: String = ""
        private val DEFAULT_CITY = LatLng(13.0827, 80.2707) // Chennai, used only if GPS is not available yet
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var overlay: MapOverlay
    private lateinit var loc: LocationProvider
    private val readers: List<AppReader> = listOf(PorterReader, RapidoReader)

    private var job: Job? = null
    private var currentKey: String? = null
    private var lastSeen = 0L
    private var lastScan = 0L
    private var lastTargetEvent = 0L
    private var hiddenAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (overlay.isShowing || now - lastTargetEvent < 8000) scan()
            if (overlay.isShowing && now - lastSeen > 2300) hideAll()
            if (!overlay.isShowing && hiddenAt != 0L && now - hiddenAt > 30_000) {
                loc.stop(); hiddenAt = 0L
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onServiceConnected() {
        instance = this
        overlay = MapOverlay(this)
        loc = LocationProvider(this)
        handler.post(tick)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (readers.any { pkg in it.packages(this) }) {
            lastTargetEvent = SystemClock.elapsedRealtime()
            if (lastLastScanOk()) scan()
        } else if (event.text.any { it.contains("₹") }) {
            unknownPackage = pkg // helps to find the real package name of a delivery app
        }
    }

    private fun lastLastScanOk() = SystemClock.elapsedRealtime() - lastScan > 350

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        overlay.hide()
        loc.stop()
        instance = null
        super.onDestroy()
    }

    // ---------- reading the popup ----------

    private fun scan() {
        lastScan = SystemClock.elapsedRealtime()
        val roots = ArrayList<AccessibilityNodeInfo>()
        try { windows?.forEach { w -> w.root?.let { roots.add(it) } } } catch (_: Exception) {}
        rootInActiveWindow?.let { roots.add(it) }

        for (root in roots) {
            val pkg = root.packageName?.toString() ?: continue
            val reader = readers.firstOrNull { pkg in it.packages(this) } ?: continue
            val texts = ArrayList<String>()
            collect(root, texts, IntArray(1))
            val order = reader.parse(texts) ?: continue
            lastSeen = SystemClock.elapsedRealtime()
            lastInfo = "${order.app} popup read at " +
                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            if (order.key != currentKey) startOrder(order)
            return
        }
    }

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<String>, count: IntArray) {
        if (n == null || count[0] > 400) return
        count[0]++
        if (n.isVisibleToUser) {
            val t = n.text?.toString() ?: n.contentDescription?.toString()
            if (!t.isNullOrBlank()) out.add(t)
        }
        for (i in 0 until n.childCount) collect(n.getChild(i), out, count)
    }

    // ---------- building the map ----------

    private fun startOrder(order: Order) {
        job?.cancel()
        currentKey = order.key
        overlay.show()
        overlay.clear()
        loc.start()
        hiddenAt = 0L

        job = scope.launch {
            val me = loc.get(2500)
            val ref = me ?: DEFAULT_CITY
            if (me != null) overlay.setMe(me)

            val pickup = Resolver.resolve(order.pickup, ref, listOfNotNull(me))
            if (pickup != null) overlay.setPickup(pickup)

            val drop = Resolver.resolve(order.drop, pickup?.point ?: ref, listOfNotNull(me, pickup?.point))
            if (drop != null) overlay.setDrop(drop)

            val pts = listOfNotNull(me, pickup?.point, drop?.point)
            if (pts.size >= 2) overlay.setRoute(Router.route(pts) ?: pts)
        }
    }

    private fun hideAll() {
        job?.cancel()
        currentKey = null
        overlay.hide()
        hiddenAt = SystemClock.elapsedRealtime()
    }

    /** Test button in the app: shows the map for the sample Porter popup. */
    fun demo() {
        lastSeen = SystemClock.elapsedRealtime() + 8000 // keep the demo map visible a bit longer
        startOrder(
            Order(
                "Demo",
                Leg("Chennai Outer Ring Road", "55V2+3PV, Chennai Outer Ring Rd, Avadi, Pammadukulam, Tamil Nadu 600052, India", 2.4),
                Leg("Ambattur", "1, Sidco Industrial Estate, Ambattur, Chennai, Tamil Nadu 600053, India", 17.0)
            )
        )
    }
}
