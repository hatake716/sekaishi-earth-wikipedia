package io.github.hatake716.sekaishiearth.globe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.LruCache
import io.github.hatake716.sekaishiearth.data.Entry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 地球儀の描画。GL スレッド上でカメラ・アニメーション・タイル・マーカー・ラベルを処理する。
 * カメラ操作は GlobeView から queueEvent 経由で呼ばれる(すべて GL スレッド)。
 */
class GlobeRenderer(
    context: Context,
    entries: List<Entry>,
    private val requestRender: () -> Unit,
    private val onCameraIdle: (lat: Double, lon: Double, alt: Double) -> Unit,
) : GLSurfaceView.Renderer {

    val camera = Camera()
    private val density = context.resources.displayMetrics.density
    private val tiles = TileManager(context.assets)
    val markers = MarkerLayer(entries, density)
    private val entryById = entries.associateBy { it.id }

    private var tileProgram = 0
    private var glowProgram = 0
    private var starProgram = 0
    private var markerProgram = 0
    private var labelProgram = 0

    private val meshes = HashMap<Int, Mesh>()
    private var starBuffer: FloatBuffer? = null
    private var starCount = 0
    private var quadBuffer: FloatBuffer? = null
    private var glowQuad: FloatBuffer? = null
    private var markerBuffer: FloatBuffer? = null
    private var frame = 0L
    private var lastFrameNanos = 0L
    private val resolved = TileManager.Resolved()

    // ---- アニメーション状態 ----
    private var flingLat = 0.0
    private var flingLon = 0.0
    private var flyFrom: DoubleArray? = null
    private var flyTo: DoubleArray? = null
    private var flyStart = 0L
    private var flyDuration = 0L
    private var idleNotified = true

    private class Mesh(val vertices: FloatBuffer, val indices: ShortBuffer, val indexCount: Int)

    private class LabelTex(val texture: Int, val width: Int, val height: Int)
    private val labelCache = object : LruCache<String, LabelTex>(160) {
        override fun entryRemoved(evicted: Boolean, key: String, old: LabelTex, new: LabelTex?) {
            GLES20.glDeleteTextures(1, intArrayOf(old.texture), 0)
        }
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * density
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val labelStroke = Paint(labelPaint).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        color = 0xCC000000.toInt()
        strokeJoin = Paint.Join.ROUND
    }

    // ------------------------------------------------------------ lifecycle
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.02f, 0.03f, 0.06f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glCullFace(GLES20.GL_BACK)
        tileProgram = Shaders.buildProgram(Shaders.TILE_VS, Shaders.TILE_FS)
        glowProgram = Shaders.buildProgram(Shaders.GLOW_VS, Shaders.GLOW_FS)
        starProgram = Shaders.buildProgram(Shaders.STAR_VS, Shaders.STAR_FS)
        markerProgram = Shaders.buildProgram(Shaders.MARKER_VS, Shaders.MARKER_FS)
        labelProgram = Shaders.buildProgram(Shaders.LABEL_VS, Shaders.LABEL_FS)
        meshes.clear()
        labelCache.evictAll()
        tiles.onSurfaceCreated()
        buildStars()
        quadBuffer = floatBufferOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
        glowQuad = floatBufferOf(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        markerBuffer = ByteBuffer.allocateDirect(markers.markerData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        lastFrameNanos = System.nanoTime()
    }

    /** 高度未設定(<=0)で setCamera されたら、ビューポート確定後に全体表示の高度にする。 */
    @Volatile var pendingFit = true

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        camera.setViewport(width, height)
        if (pendingFit) {
            camera.altitude = camera.fitAltitude()
            pendingFit = false
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        frame++
        val now = System.nanoTime()
        val dt = ((now - lastFrameNanos) / 1e9).coerceIn(0.0, 0.1)
        lastFrameNanos = now
        val animating = stepAnimation(now, dt)
        camera.update()

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        drawStars()
        drawGlow()
        val uploaded = tiles.uploadPending()
        drawTiles()
        markers.update(camera)
        drawMarkers()
        drawLabels()
        tiles.evict(frame)

        if (animating || uploaded || tiles.hasPending) {
            idleNotified = false
            requestRender()
        } else if (!idleNotified) {
            idleNotified = true
            onCameraIdle(camera.centerLat, camera.centerLon, camera.altitude)
        }
    }

    // ------------------------------------------------------------ camera ops (GL thread)
    fun stopAnimations() {
        flingLat = 0.0
        flingLon = 0.0
        flyFrom = null
        flyTo = null
    }

    fun setFling(latPerSec: Double, lonPerSec: Double) {
        flingLat = latPerSec
        flingLon = lonPerSec
    }

    /** 指定地点へ滑らかに移動する。altitude は目標高度。 */
    fun flyTo(lat: Double, lon: Double, altitude: Double, durationMs: Long = 1400) {
        stopAnimations()
        val fromLon = camera.centerLon
        var toLon = Camera.wrapLon(lon)
        // 経度は近い方向へ回る
        if (toLon - fromLon > PI) toLon -= 2 * PI
        if (toLon - fromLon < -PI) toLon += 2 * PI
        flyFrom = doubleArrayOf(camera.centerLat, fromLon, camera.altitude)
        flyTo = doubleArrayOf(lat.coerceIn(-Camera.MAX_LAT, Camera.MAX_LAT), toLon, altitude.coerceIn(Camera.MIN_ALT, Camera.MAX_ALT))
        flyStart = System.nanoTime()
        flyDuration = durationMs
    }

    private fun stepAnimation(now: Long, dt: Double): Boolean {
        var active = false
        val from = flyFrom
        val to = flyTo
        if (from != null && to != null) {
            val t = ((now - flyStart) / 1e6 / flyDuration).coerceIn(0.0, 1.0)
            val e = easeInOut(t)
            camera.centerLat = from[0] + (to[0] - from[0]) * e
            camera.centerLon = from[1] + (to[1] - from[1]) * e
            // 高度は遠い移動ほど一度上がってから下りる
            val dist = Camera.angularDistance(from[0], from[1], to[0], to[1])
            val bump = min(1.8, dist * 1.2) * max(0.0, 1.0 - max(from[2], to[2]) / 1.5)
            camera.altitude = from[2] + (to[2] - from[2]) * e + bump * sin(PI * t)
            if (t >= 1.0) { flyFrom = null; flyTo = null } else active = true
        }
        if (abs(flingLat) > 1e-5 || abs(flingLon) > 1e-5) {
            camera.centerLat += flingLat * dt
            camera.centerLon += flingLon * dt
            val decay = exp(-dt * 4.0)
            flingLat *= decay
            flingLon *= decay
            if (abs(flingLat) < 1e-5 && abs(flingLon) < 1e-5) { flingLat = 0.0; flingLon = 0.0 } else active = true
        }
        return active
    }

    private fun easeInOut(t: Double): Double = if (t < 0.5) 4 * t * t * t else 1 - (-2 * t + 2).pow(3) / 2

    // ------------------------------------------------------------ drawing
    private fun buildStars() {
        val rnd = java.util.Random(20260903L)
        starCount = 420
        val arr = FloatArray(starCount * 3)
        for (i in 0 until starCount) {
            arr[i * 3] = rnd.nextFloat()
            arr[i * 3 + 1] = rnd.nextFloat()
            arr[i * 3 + 2] = (1f + rnd.nextFloat() * 2f) * density
        }
        starBuffer = floatBufferOf(arr)
    }

    private fun drawStars() {
        val buf = starBuffer ?: return
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(starProgram)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(starProgram, "uViewport"), camera.viewportWidth.toFloat(), camera.viewportHeight.toFloat())
        val aPos = GLES20.glGetAttribLocation(starProgram, "aPos")
        buf.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 12, buf)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, starCount)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun drawGlow() {
        if (camera.altitude < 0.03) return
        val d = 1.0 + camera.altitude
        val r0 = d / sqrt(d * d - 1)  // z=0 平面での見かけ半径
        val size = (r0 * 1.22).toFloat()
        GLES20.glDepthMask(false)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
        GLES20.glUseProgram(glowProgram)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(glowProgram, "uMvp"), 1, false, camera.viewProjection, 0)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(glowProgram, "uRight"), 1f, 0f, 0f)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(glowProgram, "uUp"), 0f, 1f, 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(glowProgram, "uSize"), size)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(glowProgram, "uInner"), (r0 / size).toFloat())
        val aPos = GLES20.glGetAttribLocation(glowProgram, "aPos")
        val quad = glowQuad ?: return
        quad.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glDepthMask(true)
    }

    private fun chooseLevel(): Int {
        val degPerPx = Math.toDegrees(camera.radiansPerPixel())
        for (l in 0..TileManager.MAX_LEVEL) {
            val texelDeg = 180.0 / (tiles.rows(l) * TileManager.TILE_SIZE)
            if (texelDeg <= degPerPx * 1.05) return l
        }
        return TileManager.MAX_LEVEL
    }

    private val visibleKeys = HashSet<Long>()
    private val sampleTmp = FloatArray(3)
    private val xyzTmp = DoubleArray(3)

    private fun tileVisible(level: Int, x: Int, y: Int): Boolean {
        val lat0 = tiles.lat0(level, y)
        val lon0 = tiles.lon0(level, x)
        val dLat = tiles.dLat(level)
        val dLon = tiles.dLon(level)
        val w = camera.viewportWidth
        val h = camera.viewportHeight
        val margin = 0.15f
        val samples = 6
        for (i in 0..samples) {
            val lat = lat0 - dLat * i / samples
            for (j in 0..samples) {
                val lon = lon0 + dLon * j / samples
                Camera.toXyz(lat, lon, xyzTmp)
                if (!camera.isFrontFacing(xyzTmp[0], xyzTmp[1], xyzTmp[2])) continue
                if (!camera.project(xyzTmp[0], xyzTmp[1], xyzTmp[2], sampleTmp)) continue
                val sx = sampleTmp[0]
                val sy = sampleTmp[1]
                if (sx >= -w * margin && sx <= w * (1 + margin) && sy >= -h * margin && sy <= h * (1 + margin)) return true
            }
        }
        return false
    }

    private fun tileAt(level: Int, lat: Double, lon: Double): Long {
        val cols = tiles.cols(level)
        val rows = tiles.rows(level)
        val x = ((Camera.wrapLon(lon) + PI) / (2 * PI) * cols).toInt().coerceIn(0, cols - 1)
        val y = ((PI / 2 - lat) / PI * rows).toInt().coerceIn(0, rows - 1)
        return (x.toLong() shl 20) or y.toLong()
    }

    private fun drawTiles() {
        val level = chooseLevel()
        visibleKeys.clear()
        val cols = tiles.cols(level)
        val rows = tiles.rows(level)
        for (y in 0 until rows) for (x in 0 until cols) {
            if (tileVisible(level, x, y)) visibleKeys.add((x.toLong() shl 20) or y.toLong())
        }
        // 画面中央・四隅・辺の中点の直下にあるタイル(拡大時にサンプル点が画面外になる場合の保険)
        val w = camera.viewportWidth.toFloat()
        val h = camera.viewportHeight.toFloat()
        val probes = floatArrayOf(0.5f, 0.5f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f, 0.5f, 0f, 0.5f, 1f, 0f, 0.5f, 1f, 0.5f)
        for (i in probes.indices step 2) {
            val ll = camera.screenToLatLon(probes[i] * w, probes[i + 1] * h) ?: continue
            visibleKeys.add(tileAt(level, ll[0], ll[1]))
        }
        // 全体表示時は裏側も含めて描く必要はない(カリング)。level 0 は常に描画対象に含める
        if (level == 0) { visibleKeys.add(0L); visibleKeys.add(1L shl 20) }

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(tileProgram)
        val uMvp = GLES20.glGetUniformLocation(tileProgram, "uMvp")
        val uModel = GLES20.glGetUniformLocation(tileProgram, "uModel")
        val uLatLon = GLES20.glGetUniformLocation(tileProgram, "uLatLon")
        val uUv = GLES20.glGetUniformLocation(tileProgram, "uUv")
        val uLight = GLES20.glGetUniformLocation(tileProgram, "uLightDir")
        val uEye = GLES20.glGetUniformLocation(tileProgram, "uEye")
        val uTex = GLES20.glGetUniformLocation(tileProgram, "uTex")
        val aUv = GLES20.glGetAttribLocation(tileProgram, "aUv")
        GLES20.glUniformMatrix4fv(uMvp, 1, false, camera.mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, camera.model, 0)
        val l = floatArrayOf(-0.45f, 0.55f, 0.75f)
        val ln = sqrt(l[0] * l[0] + l[1] * l[1] + l[2] * l[2])
        GLES20.glUniform3f(uLight, l[0] / ln, l[1] / ln, l[2] / ln)
        GLES20.glUniform3f(uEye, 0f, 0f, (1.0 + camera.altitude).toFloat())
        GLES20.glUniform1i(uTex, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        val mesh = meshFor(level)
        GLES20.glEnableVertexAttribArray(aUv)
        mesh.vertices.position(0)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 8, mesh.vertices)

        // 地平線(縁)が画面内にある高度では、level 0 タイルで球全体を先に描き、縁の欠けを防ぐ。
        // 十分に近づいて縁が画面外なら、可視タイルだけで覆えるので省略する(深度の干渉も避けられる)
        val needBase = level > 0 && camera.altitude > 0.12
        if (needBase) {
            for (x in 0..1) {
                tiles.resolve(0, x, 0, frame, resolved)
                if (resolved.texture == 0) continue
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, resolved.texture)
                GLES20.glUniform4f(uLatLon, tiles.lat0(0, 0).toFloat(), tiles.lon0(0, x).toFloat(), tiles.dLat(0).toFloat(), tiles.dLon(0).toFloat())
                GLES20.glUniform4f(uUv, 0f, 0f, 1f, 1f)
                val m0 = meshFor(0)
                m0.vertices.position(0)
                GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 8, m0.vertices)
                m0.indices.position(0)
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, m0.indexCount, GLES20.GL_UNSIGNED_SHORT, m0.indices)
            }
            mesh.vertices.position(0)
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 8, mesh.vertices)
            // 深度が同じ面を上書きするため僅かに手前へ(ポリゴンオフセット)
            GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL)
            GLES20.glPolygonOffset(-2f, -4f)
        }
        for (k in visibleKeys) {
            val x = (k shr 20).toInt()
            val y = (k and 0xfffff).toInt()
            tiles.request(level, x, y)
            tiles.resolve(level, x, y, frame, resolved)
            if (!resolved.exact && level >= 2) {
                // 途中の先祖も先読みしておくと段階的に精細になる
                tiles.request(level - 1, x shr 1, y shr 1)
            }
            if (resolved.texture == 0) continue
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, resolved.texture)
            GLES20.glUniform4f(uLatLon, tiles.lat0(level, y).toFloat(), tiles.lon0(level, x).toFloat(), tiles.dLat(level).toFloat(), tiles.dLon(level).toFloat())
            GLES20.glUniform4f(uUv, resolved.u0, resolved.v0, resolved.su, resolved.sv)
            mesh.indices.position(0)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.indexCount, GLES20.GL_UNSIGNED_SHORT, mesh.indices)
        }
        GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)
        GLES20.glDisableVertexAttribArray(aUv)
    }

    private fun meshFor(level: Int): Mesh {
        val seg = when (level) { 0 -> 64; 1 -> 32; 2 -> 20; else -> 12 }
        return meshes.getOrPut(seg) { buildMesh(seg) }
    }

    private fun buildMesh(seg: Int): Mesh {
        val verts = FloatArray((seg + 1) * (seg + 1) * 2)
        var k = 0
        for (i in 0..seg) for (j in 0..seg) {
            verts[k++] = j.toFloat() / seg
            verts[k++] = i.toFloat() / seg
        }
        val idx = ShortArray(seg * seg * 6)
        k = 0
        for (i in 0 until seg) for (j in 0 until seg) {
            val a = (i * (seg + 1) + j).toShort()
            val b = (i * (seg + 1) + j + 1).toShort()
            val c = ((i + 1) * (seg + 1) + j).toShort()
            val d = ((i + 1) * (seg + 1) + j + 1).toShort()
            // 反時計回り(外側から見て)。緯度は v が増えると南へ、経度は u が増えると東へ
            idx[k++] = a; idx[k++] = c; idx[k++] = b
            idx[k++] = b; idx[k++] = c; idx[k++] = d
        }
        val ib = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        ib.put(idx).position(0)
        return Mesh(floatBufferOf(verts), ib, idx.size)
    }

    private fun drawMarkers() {
        val count = markers.markerCount
        if (count == 0) return
        val buf = markerBuffer ?: return
        buf.clear()
        buf.put(markers.markerData, 0, count * 8)
        buf.position(0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(markerProgram)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(markerProgram, "uViewport"), camera.viewportWidth.toFloat(), camera.viewportHeight.toFloat())
        val aPos = GLES20.glGetAttribLocation(markerProgram, "aPos")
        val aColor = GLES20.glGetAttribLocation(markerProgram, "aColor")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aColor)
        buf.position(0)
        GLES20.glVertexAttribPointer(aPos, 4, GLES20.GL_FLOAT, false, 32, buf)
        buf.position(4)
        GLES20.glVertexAttribPointer(aColor, 4, GLES20.GL_FLOAT, false, 32, buf)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aColor)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun drawLabels() {
        val labels = markers.labels
        if (labels.isEmpty()) return
        val quad = quadBuffer ?: return
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(labelProgram)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(labelProgram, "uViewport"), camera.viewportWidth.toFloat(), camera.viewportHeight.toFloat())
        val uRect = GLES20.glGetUniformLocation(labelProgram, "uRect")
        val uAlpha = GLES20.glGetUniformLocation(labelProgram, "uAlpha")
        GLES20.glUniform1i(GLES20.glGetUniformLocation(labelProgram, "uTex"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        val aUv = GLES20.glGetAttribLocation(labelProgram, "aUv")
        GLES20.glEnableVertexAttribArray(aUv)
        quad.position(0)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 8, quad)
        for (l in labels) {
            val tex = labelTexture(l.text)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex.texture)
            val lx = if (l.left) l.x - 11 * density - tex.width else l.x + 11 * density
            GLES20.glUniform4f(uRect, lx, l.y - tex.height / 2f, tex.width.toFloat(), tex.height.toFloat())
            GLES20.glUniform1f(uAlpha, if (l.priority >= 9) 1f else 0.95f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glDisableVertexAttribArray(aUv)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun labelTexture(text: String): LabelTex {
        labelCache.get(text)?.let { return it }
        val pad = (4 * density).toInt()
        val w = (labelPaint.measureText(text) + pad * 2 + labelStroke.strokeWidth).toInt().coerceAtLeast(2)
        val fm = labelPaint.fontMetrics
        val h = (fm.descent - fm.ascent + pad * 2).toInt().coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val x = pad + labelStroke.strokeWidth / 2
        val y = pad - fm.ascent
        c.drawText(text, x, y, labelStroke)
        labelPaint.color = 0xFFFFFFFF.toInt()
        c.drawText(text, x, y, labelPaint)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        val t = LabelTex(ids[0], w, h)
        labelCache.put(text, t)
        return t
    }

    fun entry(id: Int): Entry? = entryById[id]

    private fun floatBufferOf(arr: FloatArray): FloatBuffer {
        val b = ByteBuffer.allocateDirect(arr.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        b.put(arr).position(0)
        return b
    }

    /** 表示中ピン数(フィルタ後)。 */
    fun visibleEntryCount(): Int = markers.enabledCount()

    companion object {
        /** 高度(地球半径単位)から、その高度で画面に収まる概ねの角度幅を返す。 */
        fun altitudeForSpan(spanRad: Double, fovY: Double): Double = (spanRad / 2) / Math.tan(fovY / 2)
        fun clampAlt(a: Double) = a.coerceIn(Camera.MIN_ALT, Camera.MAX_ALT)
        fun deg(v: Double) = Math.toDegrees(v)
        fun rad(v: Double) = Math.toRadians(v)
        fun cosd(v: Double) = cos(rad(v))
    }
}
