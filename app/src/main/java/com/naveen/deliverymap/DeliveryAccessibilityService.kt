package com.naveen.deliverymap

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
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
        private val DEFAULT_CITY = LatLng(13.0827, 80.2707) // Chennai, only used if GPS is not ready
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var overlay: MapOverlay
    private lateinit var loc: LocationProvider
    private val readers: List<AppReader> = listOf(PorterReader, RapidoReader)

    private var job: Job? = null
    private var currentKey: String? = null
    private var dismissedKey: String? = null
    private var popupBottom = 0
    private var lastSeen = 0L
    private var lastScan = 0L
    private var lastTargetEvent = 0L
    private var hiddenAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (!Prefs.enabled(this@DeliveryAccessibilityService)) {
                // main switch OFF: do nothing at all
                if (overlay.isShowing) hideAll()
                loc.stop()
                dismissedKey = null
            } else {
                if (overlay.isShowing || now - lastTargetEvent < 8000) scan()
                if (now - lastSeen > 2300) {
                    dismissedKey = null
                    if (overlay.isShowing) hideAll()
                }
                if (!overlay.isShowing && hiddenAt != 0L && now - hiddenAt > 120_000) {
                    loc.stop(); hiddenAt = 0L
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onServiceConnected() {
        instance = this
        overlay = MapOverlay(this)
        overlay.onClose = { dismissCurrent() }
        loc = LocationProvider(this)
        handler.post(tick)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!Prefs.enabled(this)) return
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (readers.any { pkg in it.packages(this) }) {
            lastTargetEvent = SystemClock.elapsedRealtime()
            if (SystemClock.elapsedRealtime() - lastScan > 300) scan()
        } else if (event.text.any { it.contains("₹") }) {
            unknownPackage = pkg // helps to find the real package name of a delivery app
        }
    }

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
        val screenH = resources.displayMetrics.heightPixels

        for (root in roots) {
            val pkg = root.packageName?.toString() ?: continue
            val reader = readers.firstOrNull { pkg in it.packages(this) } ?: continue
            val texts = ArrayList<String>()
            val st = IntArray(2) // [0] node count, [1] lowest bottom edge of the popup
            collect(root, texts, st, screenH, Rect())
            val order = reader.parse(texts) ?: continue

            lastSeen = SystemClock.elapsedRealtime()
            if (st[1] > 0) popupBottom = st[1]
            lastInfo = "${order.app} popup read at " +
                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

            if (order.key == dismissedKey) return        // user pressed X for this popup
            if (order.key != currentKey) startOrder(order)
            else overlay.setTop(mapTop())
            return
        }
    }

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<String>, st: IntArray, screenH: Int, r: Rect) {
        if (n == null || st[0] > 400) return
        st[0]++
        if (n.isVisibleToUser) {
            val t = n.text?.toString() ?: n.contentDescription?.toString()
            if (!t.isNullOrBlank()) out.add(t)
            n.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0 && r.height() < screenH * 0.7) {
                if (r.bottom > st[1]) st[1] = r.bottom
            }
        }
        for (i in 0 until n.childCount) collect(n.getChild(i), out, st, screenH, r)
    }

    /** Map starts below the delivery popup + the gap (default 1 inch). */
    private fun mapTop(): Int {
        val dm = resources.displayMetrics
        val base = if (popupBottom > 0) popupBottom else (dm.heightPixels * 0.40).toInt()
        return base + (Prefs.gapInch(this) * dm.ydpi).toInt()
    }

    // ---------- building the map ----------

    private fun startOrder(order: Order) {
        val mode = Prefs.mode(this)
        job?.cancel()
        currentKey = order.key
        overlay.show(mapTop())
        overlay.clear()
        loc.start()
        hiddenAt = 0L

        job = scope.launch {
            val me = loc.quick() ?: loc.get(if (mode == "normal") 2500 else 1200)
            val ref = me ?: DEFAULT_CITY
            if (me != null) overlay.setMe(me)

            if (mode == "normal") {
                // you + pickup + drop, road route
                val pickup = Resolver.resolve(order.pickup, ref, listOfNotNull(me))
                if (pickup != null) overlay.setPickup(pickup)

                val drop = Resolver.resolve(order.drop, pickup?.point ?: ref, listOfNotNull(me, pickup?.point))
                if (drop != null) overlay.setDrop(drop)

                val pts = listOfNotNull(me, pickup?.point, drop?.point)
                if (pts.size >= 2) {
                    overlay.setRoute(pts)                       // quick straight line first
                    Router.route(pts)?.let { overlay.setRoute(it) }
                }
            } else {
                // fast / ultra: only you + drop
                val fast = mode == "fast"
                val drop = Resolver.resolve(
                    order.drop, ref, listOfNotNull(me),
                    slackKm = order.pickup.km ?: 0.0,
                    maxQueries = if (fast) 3 else 2,
                    timeoutMs = if (fast) 5000 else 3500,
                    fallback = if (fast) Fallback.ONLY_IF_NONE else Fallback.NEVER
                )
                if (drop != null) overlay.setDrop(drop)
                if (me != null && drop != null) {
                    val pts = listOf(me, drop.point)
                    overlay.setRoute(pts)                       // straight line right away
                    if (fast) Router.route(pts, 6000)?.let { overlay.setRoute(it) }
                }
            }
        }
    }

    private fun hideAll() {
        job?.cancel()
        currentKey = null
        overlay.hide()
        hiddenAt = SystemClock.elapsedRealtime()
    }

    /** X button: closes the map for THIS popup only. The next popup shows the map again. */
    private fun dismissCurrent() {
        dismissedKey = currentKey
        hideAll()
    }

    /** Test button in the app. */
    fun demo() {
        dismissedKey = null
        lastSeen = SystemClock.elapsedRealtime() + 8000 // keep the demo map visible a bit longer
        startOrder(
            Order(
                "Demo",
                Leg("Vanagaram", "44, 3rd Cross St, Rajiv Nagar, Vanagaram, Chennai, Tamil Nadu 600077, India", 1.9),
                Leg("Kil Ayanambakkam", "Velammal Vidyalaya Ayanambakkam, Mel Ayanambakkam, Chennai, Tamil Nadu, India", 5.1)
            )
        )
    }
}
