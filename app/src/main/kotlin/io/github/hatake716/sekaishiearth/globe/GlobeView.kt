package io.github.hatake716.sekaishiearth.globe

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import io.github.hatake716.sekaishiearth.data.Entry
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/**
 * 地球儀ビュー。タッチ操作(ドラッグ回転・慣性・ピンチズーム・ダブルタップ・タップ選択)を扱う。
 * カメラの読み書きはすべて GL スレッド(queueEvent)で行い、UI スレッドとの競合を避ける。
 */
@SuppressLint("ViewConstructor")
class GlobeView(
    context: Context,
    entries: List<Entry>,
    private val onTap: (ids: List<Int>) -> Unit,
    onCameraIdle: (lat: Double, lon: Double, alt: Double) -> Unit,
) : GLSurfaceView(context) {

    val renderer: GlobeRenderer
    private val density = resources.displayMetrics.density

    private var mode = Mode.NONE
    private var grab: DoubleArray? = null
    private var lastX = 0f
    private var lastY = 0f
    private var lastTime = 0L
    private var velLat = 0.0
    private var velLon = 0.0
    private var pinchDist = 0f
    private var pinchFocalX = 0f
    private var pinchFocalY = 0f

    private enum class Mode { NONE, DRAG, PINCH }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val ids = renderer.markers.snapshot.pick(e.x, e.y, 26f * density)
            onTap(ids)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val sx = e.x
            val sy = e.y
            queueEvent {
                renderer.camera.update()
                val ll = renderer.camera.screenToLatLon(sx, sy)
                val cam = renderer.camera
                if (ll != null) renderer.flyTo(ll[0], ll[1], cam.altitude / 2.5, 550)
                else renderer.flyTo(cam.centerLat, cam.centerLon, cam.altitude / 2.5, 550)
            }
            requestRender()
            return true
        }
    })

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        preserveEGLContextOnPause = true
        renderer = GlobeRenderer(context, entries, { requestRender() }, onCameraIdle)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    // ------------------------------------------------------------ public API (UI thread)
    fun flyToEntry(e: Entry, altitude: Double = 0.05) {
        queueEvent { renderer.flyTo(Math.toRadians(e.lat), Math.toRadians(e.lon), altitude) }
        requestRender()
    }

    fun flyTo(latDeg: Double, lonDeg: Double, altitude: Double, durationMs: Long = 1400) {
        queueEvent { renderer.flyTo(Math.toRadians(latDeg), Math.toRadians(lonDeg), altitude, durationMs) }
        requestRender()
    }

    /** altitude <= 0 なら地球全体が収まる高度にする。 */
    fun setCamera(latDeg: Double, lonDeg: Double, altitude: Double) {
        queueEvent {
            renderer.stopAnimations()
            renderer.camera.centerLat = Math.toRadians(latDeg)
            renderer.camera.centerLon = Math.toRadians(lonDeg)
            if (altitude > 0) {
                renderer.camera.altitude = altitude
                renderer.pendingFit = false
            } else if (renderer.camera.viewportWidth > 1) {
                renderer.camera.altitude = renderer.camera.fitAltitude()
                renderer.pendingFit = false
            } else {
                renderer.pendingFit = true
            }
        }
        requestRender()
    }

    /** 地球全体が見える位置へ戻る。 */
    fun fitWorld() {
        queueEvent {
            val cam = renderer.camera
            renderer.flyTo(cam.centerLat, cam.centerLon, cam.fitAltitude(), 900)
        }
        requestRender()
    }

    fun zoomBy(factor: Double) {
        queueEvent {
            val cam = renderer.camera
            renderer.flyTo(cam.centerLat, cam.centerLon, cam.altitude / factor, 350)
        }
        requestRender()
    }

    fun setFilter(filter: MarkerFilter) {
        renderer.markers.filter = filter
        requestRender()
    }

    fun setSelected(id: Int) {
        renderer.markers.selectedId = id
        requestRender()
    }

    // ------------------------------------------------------------ touch
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = Mode.DRAG
                lastX = event.x
                lastY = event.y
                lastTime = event.eventTime
                velLat = 0.0
                velLon = 0.0
                val sx = event.x
                val sy = event.y
                queueEvent {
                    renderer.stopAnimations()
                    renderer.camera.update()
                    grab = renderer.camera.screenToLatLon(sx, sy)
                }
                requestRender()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    mode = Mode.PINCH
                    pinchDist = dist(event)
                    pinchFocalX = (event.getX(0) + event.getX(1)) / 2
                    pinchFocalY = (event.getY(0) + event.getY(1)) / 2
                    velLat = 0.0
                    velLon = 0.0
                    val fx = pinchFocalX
                    val fy = pinchFocalY
                    queueEvent {
                        renderer.camera.update()
                        grab = renderer.camera.screenToLatLon(fx, fy)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.PINCH && event.pointerCount >= 2) {
                    val d = dist(event)
                    val fx = (event.getX(0) + event.getX(1)) / 2
                    val fy = (event.getY(0) + event.getY(1)) / 2
                    val scale = if (pinchDist > 0f) (d / pinchDist).toDouble() else 1.0
                    pinchDist = d
                    queueEvent { applyPinch(scale, fx, fy) }
                    requestRender()
                } else if (mode == Mode.DRAG && event.pointerCount == 1) {
                    val sx = event.x
                    val sy = event.y
                    val dx = sx - lastX
                    val dy = sy - lastY
                    val dt = max(1L, event.eventTime - lastTime)
                    lastX = sx
                    lastY = sy
                    lastTime = event.eventTime
                    queueEvent { applyDrag(sx, sy, dx, dy, dt) }
                    requestRender()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount == 2) {
                    // 残る指でドラッグを続ける
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    mode = Mode.DRAG
                    lastX = event.getX(remaining)
                    lastY = event.getY(remaining)
                    lastTime = event.eventTime
                    val sx = lastX
                    val sy = lastY
                    queueEvent {
                        renderer.camera.update()
                        grab = renderer.camera.screenToLatLon(sx, sy)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.DRAG) {
                    val stale = event.eventTime - lastTime > 80
                    val vLat = if (stale) 0.0 else velLat
                    val vLon = if (stale) 0.0 else velLon
                    if (abs(vLat) > 0.02 || abs(vLon) > 0.02) {
                        queueEvent { renderer.setFling(vLat, vLon) }
                        requestRender()
                    }
                }
                mode = Mode.NONE
                grab = null
            }
        }
        return true
    }

    private fun dist(e: MotionEvent): Float = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

    /** GL スレッド: 指の下の地表点が指に追従するように回転する。 */
    private fun applyDrag(sx: Float, sy: Float, dx: Float, dy: Float, dtMs: Long) {
        val cam = renderer.camera
        cam.update()
        val g = grab
        val cur = cam.screenToLatLon(sx, sy)
        var dLat = 0.0
        var dLon = 0.0
        if (g != null && cur != null) {
            val lat0 = cam.centerLat
            val lon0 = cam.centerLon
            // 緯度経度の差分で近似し、数回反復して指の下の地点を一致させる
            for (k in 0 until 3) {
                val c = if (k == 0) cur else (cam.screenToLatLon(sx, sy) ?: break)
                cam.centerLat += g[0] - c[0]
                cam.centerLon += Camera.wrapLon(g[1] - c[1])
                cam.clamp()
                cam.update()
            }
            // 極付近での暴走を抑える
            val limit = cam.radiansPerPixel() * 120 * density
            dLat = (cam.centerLat - lat0).coerceIn(-limit, limit)
            dLon = Camera.wrapLon(cam.centerLon - lon0).coerceIn(-limit * 3, limit * 3)
            cam.centerLat = lat0 + dLat
            cam.centerLon = lon0 + dLon
        } else {
            val rpp = cam.radiansPerPixel()
            dLat = dy * rpp
            dLon = -dx * rpp / max(cos(cam.centerLat), 0.2)
            cam.centerLat += dLat
            cam.centerLon += dLon
            grab = null
        }
        cam.clamp()
        val dt = dtMs / 1000.0
        val a = 0.5
        velLat = velLat * (1 - a) + (dLat / dt) * a
        velLon = velLon * (1 - a) + (dLon / dt) * a
        // 極限に張り付いたら速度を殺す
        if (abs(cam.centerLat) >= Camera.MAX_LAT - 1e-9) velLat = 0.0
    }

    /** GL スレッド: 焦点の下の地表点を固定したまま高度を変える。 */
    private fun applyPinch(scale: Double, fx: Float, fy: Float) {
        val cam = renderer.camera
        cam.update()
        val before = grab ?: cam.screenToLatLon(fx, fy)
        cam.altitude = (cam.altitude / scale).coerceIn(Camera.MIN_ALT, Camera.MAX_ALT)
        cam.update()
        if (before != null) {
            for (k in 0 until 3) {
                val after = cam.screenToLatLon(fx, fy) ?: break
                cam.centerLat += before[0] - after[0]
                cam.centerLon += Camera.wrapLon(before[1] - after[1])
                cam.clamp()
                cam.update()
            }
            grab = before
        } else {
            grab = cam.screenToLatLon(fx, fy)
        }
    }

    companion object {
        /** エントリを画面に収めるのに適した高度(地球半径単位)。 */
        const val ENTRY_ALTITUDE = 0.045
        fun deg2rad(d: Double) = d * PI / 180
    }
}
