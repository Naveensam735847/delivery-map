package com.naveen.deliverymap

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import java.io.File
import kotlin.math.abs

/** Small map window under the delivery popup. Only the X button is touchable. */
class MapOverlay(private val svc: AccessibilityService) {
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: FrameLayout? = null
    private var map: MapView? = null
    private var closeView: TextView? = null
    private var frameLp: WindowManager.LayoutParams? = null
    private var closeLp: WindowManager.LayoutParams? = null

    var onClose: (() -> Unit)? = null

    private var me: LatLng? = null
    private var pickup: Place? = null
    private var drop: Place? = null
    private var route: List<LatLng>? = null

    val isShowing: Boolean get() = root != null

    private fun dp(v: Int) = (v * svc.resources.displayMetrics.density).toInt()
    private val screenH get() = svc.resources.displayMetrics.heightPixels
    private val margin get() = dp(12)
    private val closeSize get() = dp(46)

    init {
        val cfg = Configuration.getInstance()
        cfg.userAgentValue = Net.UA
        val base = File(svc.cacheDir, "osmdroid")
        cfg.osmdroidBasePath = base
        cfg.osmdroidTileCache = File(base, "tiles")
        cfg.tileFileSystemCacheMaxBytes = 120L * 1024 * 1024
        cfg.tileFileSystemCacheTrimBytes = 90L * 1024 * 1024
    }

    private fun clampTop(top: Int): Int = minOf(top, screenH - dp(56) - dp(160))

    private fun heightFor(top: Int): Int {
        val avail = screenH - top - dp(56)
        return avail.coerceIn(dp(160), (screenH * 0.42).toInt())
    }

    fun show(top: Int) {
        if (root != null) return
        val dm = svc.resources.displayMetrics
        val w = dm.widthPixels - margin * 2
        val t = clampTop(top)

        val mv = MapView(svc).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(false)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 4.0
            maxZoomLevel = 19.0
            controller.setZoom(15.0)
            controller.setCenter(GeoPoint(13.0827, 80.2707))
        }
        val bg = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(Color.parseColor("#E8E4DC"))
            setStroke(dp(2), Color.WHITE)
        }
        val frame = FrameLayout(svc).apply {
            background = bg
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            addView(mv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val flp = WindowManager.LayoutParams(
            w, heightFor(t),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = margin; y = t }

        val close = TextView(svc).apply {
            text = "✕"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#D9000000"))
                setStroke(dp(2), Color.WHITE)
            }
            setOnClickListener { onClose?.invoke() }
        }
        val clp = WindowManager.LayoutParams(
            closeSize, closeSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = margin + w - closeSize - dp(8); y = t + dp(8) }

        try {
            wm.addView(frame, flp)
            wm.addView(close, clp)
            root = frame; map = mv; closeView = close; frameLp = flp; closeLp = clp
        } catch (e: Exception) {
            try { wm.removeView(frame) } catch (_: Exception) {}
            root = null; map = null; closeView = null; frameLp = null; closeLp = null
        }
    }

    /** Moves the map window (used when the delivery popup is higher or lower). */
    fun setTop(top: Int) {
        val r = root ?: return
        val c = closeView ?: return
        val flp = frameLp ?: return
        val clp = closeLp ?: return
        val t = clampTop(top)
        if (abs(t - flp.y) < dp(6)) return
        flp.y = t
        flp.height = heightFor(t)
        clp.y = t + dp(8)
        try {
            wm.updateViewLayout(r, flp)
            wm.updateViewLayout(c, clp)
        } catch (_: Exception) {}
        map?.let { mv -> mv.post { fit(mv) } }
    }

    fun clear() {
        me = null; pickup = null; drop = null; route = null
        refresh()
    }

    fun setMe(p: LatLng) { me = p; refresh() }
    fun setPickup(p: Place) { pickup = p; refresh() }
    fun setDrop(p: Place) { drop = p; refresh() }
    fun setRoute(r: List<LatLng>) { route = r; refresh() }

    fun hide() {
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        closeView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        try { map?.onDetach() } catch (_: Exception) {}
        root = null; map = null; closeView = null; frameLp = null; closeLp = null
        me = null; pickup = null; drop = null; route = null
    }

    private fun refresh() {
        val mv = map ?: return
        mv.overlays.clear()
        route?.let { r ->
            if (r.size >= 2) {
                mv.overlays.add(Polyline().apply {
                    setPoints(r.map { GeoPoint(it.lat, it.lng) })
                    outlinePaint.color = Color.BLACK
                    outlinePaint.strokeWidth = dp(5).toFloat()
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.strokeJoin = Paint.Join.ROUND
                })
            }
        }
        val d = svc.resources.displayMetrics.density
        me?.let { mv.overlays.add(DotOverlay(GeoPoint(it.lat, it.lng), Color.parseColor("#1A73E8"), true, d, halo = true)) }
        pickup?.let { mv.overlays.add(DotOverlay(GeoPoint(it.point.lat, it.point.lng), Color.parseColor("#1DB954"), it.solid, d)) }
        drop?.let { mv.overlays.add(DotOverlay(GeoPoint(it.point.lat, it.point.lng), Color.parseColor("#E53935"), it.solid, d)) }
        mv.post { fit(mv) }
        mv.invalidate()
    }

    private fun fit(mv: MapView) {
        val pts = ArrayList<GeoPoint>()
        val r = route
        if (r != null && r.size >= 2) r.forEach { pts.add(GeoPoint(it.lat, it.lng)) }
        else {
            me?.let { pts.add(GeoPoint(it.lat, it.lng)) }
            pickup?.let { pts.add(GeoPoint(it.point.lat, it.point.lng)) }
            drop?.let { pts.add(GeoPoint(it.point.lat, it.point.lng)) }
        }
        if (pts.isEmpty()) return
        if (pts.size == 1) {
            mv.controller.setZoom(16.0)
            mv.controller.setCenter(pts[0])
        } else {
            val bb = BoundingBox.fromGeoPoints(pts)
            if (bb.latitudeSpan < 0.002 && bb.longitudeSpan < 0.002) {
                mv.controller.setZoom(16.0)
                mv.controller.setCenter(bb.centerWithDateLine)
            } else {
                mv.zoomToBoundingBox(bb, false, dp(40))
            }
        }
    }
}

/** Round dot. solid = filled; hollow ring = not sure about this location. */
private class DotOverlay(
    private val point: GeoPoint,
    private val color: Int,
    private val solid: Boolean,
    private val density: Float,
    private val halo: Boolean = false
) : Overlay() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val px = Point()

    override fun draw(c: Canvas, osmv: MapView, shadow: Boolean) {
        if (shadow) return
        osmv.projection.toPixels(point, px)
        val x = px.x.toFloat()
        val y = px.y.toFloat()
        if (halo) {
            paint.style = Paint.Style.FILL
            paint.color = (color and 0x00FFFFFF) or 0x40000000
            c.drawCircle(x, y, 22 * density, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        c.drawCircle(x, y, 13 * density, paint)
        if (solid) {
            paint.color = color
            c.drawCircle(x, y, 9 * density, paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4 * density
            paint.color = color
            c.drawCircle(x, y, 8 * density, paint)
        }
    }
}
