package io.github.hatake716.sekaishiearth.globe

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI

/**
 * 地球テクスチャのタイル管理。assets/tiles/{level}/{x}_{y}.jpg (正距円筒図法、1024px 四方)。
 * level L は 2^(L+1) 列 × 2^L 行。level 0 の 2 枚は常駐、それ以外は LRU で GPU から追い出す。
 * デコードはワーカースレッド、GPU へのアップロードは GL スレッド(uploadPending)。
 */
class TileManager(private val assets: AssetManager) {

    class TileTex(val key: Int) {
        var texture = 0
        var lastUsedFrame = 0L
        var loading = false
        var failed = false // デコード失敗タイル(毎フレームの再デコードを防ぐ)
        val loaded: Boolean get() = texture != 0
    }

    /** タイル描画時のテクスチャ解決結果(先祖タイルにフォールバックした場合は UV を部分参照する)。 */
    class Resolved {
        var texture = 0
        var u0 = 0f
        var v0 = 0f
        var su = 1f
        var sv = 1f
        var exact = false
    }

    private val tiles = HashMap<Int, TileTex>()
    private val pending = ConcurrentLinkedQueue<Pair<Int, Bitmap?>>()
    private val inFlight = AtomicInteger(0)
    private var executor: ExecutorService = Executors.newFixedThreadPool(2)
    @Volatile private var generation = 0
    var anisotropy = 0f

    /** デコード完了時に GL スレッドの再描画を促すためのコールバック(RENDERMODE_WHEN_DIRTY 対策)。 */
    var onTileDecoded: (() -> Unit)? = null

    fun cols(level: Int) = 2 shl level
    fun rows(level: Int) = 1 shl level
    fun key(level: Int, x: Int, y: Int) = (level shl 24) or (x shl 12) or y

    /** タイルの北端緯度(rad)。 */
    fun lat0(level: Int, y: Int): Double = PI / 2 - y * (PI / rows(level))
    /** タイルの西端経度(rad)。 */
    fun lon0(level: Int, x: Int): Double = -PI + x * (2 * PI / cols(level))
    fun dLat(level: Int): Double = PI / rows(level)
    fun dLon(level: Int): Double = 2 * PI / cols(level)

    /** GL コンテキスト再生成時。既存のテクスチャ ID は無効なので破棄する。 */
    fun onSurfaceCreated() {
        tiles.clear()
        pending.clear()
        generation++
        executor.shutdownNow()
        executor = Executors.newFixedThreadPool(2)
        inFlight.set(0)
        val ext = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
        anisotropy = if (ext.contains("GL_EXT_texture_filter_anisotropic")) {
            val v = FloatArray(1)
            GLES20.glGetFloatv(0x84FF /* GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT */, v, 0)
            v[0].coerceAtMost(8f)
        } else 0f
        // level 0 は常駐
        request(0, 0, 0)
        request(0, 1, 0)
    }

    /** 未ロードならデコードを予約する。 */
    fun request(level: Int, x: Int, y: Int) {
        val k = key(level, x, y)
        val t = tiles.getOrPut(k) { TileTex(k) }
        if (t.loaded || t.loading || t.failed) return
        if (inFlight.get() >= MAX_IN_FLIGHT) return
        t.loading = true
        inFlight.incrementAndGet()
        val gen = generation
        executor.execute {
            var bmp: Bitmap? = null
            try {
                assets.open("tiles/$level/${x}_$y.jpg").use { input ->
                    val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                    bmp = BitmapFactory.decodeStream(input, null, opts)
                }
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "tile decode OOM $level/$x/$y")
            } catch (e: Exception) {
                Log.w(TAG, "tile decode failed $level/$x/$y: $e")
            }
            if (gen == generation) {
                pending.add(k to bmp)
                onTileDecoded?.invoke() // GL スレッドへ再描画を要求(デコード完了が画面へ反映されるように)
            } else {
                bmp?.recycle()
            }
            inFlight.decrementAndGet()
        }
    }

    /** デコード済みビットマップを GPU へ転送する(GL スレッド)。1 フレームあたり数枚に制限してカクつきを抑える。 */
    fun uploadPending(maxPerFrame: Int = 2): Boolean {
        var n = 0
        var uploaded = false
        while (n < maxPerFrame) {
            val (k, bmp) = pending.poll() ?: break
            val t = tiles[k] ?: continue
            t.loading = false
            if (bmp == null) { t.failed = true; continue }
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            if (anisotropy > 1f) GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, 0x84FE /* GL_TEXTURE_MAX_ANISOTROPY_EXT */, anisotropy)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            bmp.recycle()
            t.texture = ids[0]
            uploaded = true
            n++
        }
        return uploaded
    }

    val hasPending: Boolean get() = pending.isNotEmpty()

    fun isLoaded(level: Int, x: Int, y: Int): Boolean = tiles[key(level, x, y)]?.loaded == true

    /**
     * タイル (level,x,y) を描くためのテクスチャを解決する。自身が未ロードなら最も近い先祖を使い、UV を部分参照にする。
     * 先祖も無ければ texture=0。
     */
    fun resolve(level: Int, x: Int, y: Int, frame: Long, out: Resolved): Resolved {
        var l = level
        var tx = x
        var ty = y
        var scale = 1f
        var u0 = 0f
        var v0 = 0f
        while (l >= 0) {
            val t = tiles[key(l, tx, ty)]
            if (t != null && t.loaded) {
                t.lastUsedFrame = frame
                out.texture = t.texture
                out.u0 = u0
                out.v0 = v0
                out.su = scale
                out.sv = scale
                out.exact = l == level
                return out
            }
            if (l == 0) break
            // 親へ: 子 (tx,ty) は親の (tx/2, ty/2) の中の (tx%2, ty%2) 象限
            scale *= 0.5f
            u0 = u0 * 0.5f + (tx and 1) * 0.5f
            v0 = v0 * 0.5f + (ty and 1) * 0.5f
            tx = tx shr 1
            ty = ty shr 1
            l--
        }
        out.texture = 0
        out.exact = false
        return out
    }

    /** 使われていないタイルを GPU から解放する。level 0 は残す。 */
    fun evict(frame: Long) {
        val loaded = tiles.values.filter { it.loaded && (it.key shr 24) > 0 }
        if (loaded.size <= MAX_RESIDENT) return
        val victims = loaded.sortedBy { it.lastUsedFrame }.take(loaded.size - MAX_RESIDENT)
        val ids = IntArray(victims.size)
        for ((i, v) in victims.withIndex()) {
            if (frame - v.lastUsedFrame < 30) continue // 直近で使ったものは残す
            ids[i] = v.texture
            v.texture = 0
        }
        GLES20.glDeleteTextures(ids.size, ids, 0)
    }

    /** GlobeView 破棄時にワーカースレッドを止める(非デーモンスレッドの漏れを防ぐ)。 */
    fun shutdown() {
        generation++
        executor.shutdownNow()
        pending.clear()
    }

    companion object {
        const val TAG = "TileManager"
        const val TILE_SIZE = 1024
        const val MAX_LEVEL = 4
        const val MAX_RESIDENT = 48
        const val MAX_IN_FLIGHT = 6
    }
}
