package io.github.hatake716.sekaishiearth.globe

import android.opengl.Matrix
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.min
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 地球儀カメラ。地球は半径 1 の球。カメラは +Z 軸上 (0,0,1+altitude) から原点を見る。
 * 地球側を回転させ、(centerLat, centerLon) が画面中央 (+Z 方向) に来るようにする。
 * 角度はラジアン。フィールドの書き換えは GL スレッドからのみ行う(GlobeView.queueEvent 経由)。
 */
class Camera {
    var centerLat = Math.toRadians(35.0)
    var centerLon = Math.toRadians(135.0)

    /** 地表からの高さ(地球半径単位)。 */
    var altitude = 2.2

    var viewportWidth = 1
        private set
    var viewportHeight = 1
        private set

    val fovY: Double = Math.toRadians(40.0)

    val model = FloatArray(16)
    val view = FloatArray(16)
    val projection = FloatArray(16)
    val viewProjection = FloatArray(16)
    val mvp = FloatArray(16)

    /** カメラ位置(地球座標系 = モデル回転を打ち消した座標)。 */
    val eyeInEarth = DoubleArray(3)

    fun setViewport(w: Int, h: Int) {
        viewportWidth = max(1, w)
        viewportHeight = max(1, h)
    }

    fun clamp() {
        centerLat = centerLat.coerceIn(-MAX_LAT, MAX_LAT)
        centerLon = wrapLon(centerLon)
        altitude = altitude.coerceIn(MIN_ALT, MAX_ALT)
    }

    /** 行列を更新する。描画前に毎フレーム呼ぶ。 */
    fun update() {
        clamp()
        val aspect = viewportWidth.toDouble() / viewportHeight
        val near = max(altitude * 0.05, 0.0004)
        val far = altitude + 2.5
        Matrix.perspectiveM(projection, 0, Math.toDegrees(fovY).toFloat(), aspect.toFloat(), near.toFloat(), far.toFloat())
        Matrix.setLookAtM(view, 0, 0f, 0f, (1.0 + altitude).toFloat(), 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(model, 0, Math.toDegrees(centerLat).toFloat(), 1f, 0f, 0f)
        Matrix.rotateM(model, 0, Math.toDegrees(-centerLon).toFloat(), 0f, 1f, 0f)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
        Matrix.multiplyMM(mvp, 0, viewProjection, 0, model, 0)
        // カメラ位置を地球座標系へ: model の逆回転 = Ry(lon) * Rx(-lat) を (0,0,1+alt) に適用
        val d = 1.0 + altitude
        val y1 = d * sin(centerLat)
        val z1 = d * cos(centerLat)
        eyeInEarth[0] = z1 * sin(centerLon)
        eyeInEarth[1] = y1
        eyeInEarth[2] = z1 * cos(centerLon)
    }

    /** 画面上での地球半径(px)。 */
    fun globeRadiusPx(): Double {
        val d = 1.0 + altitude
        val ang = asin(1.0 / d)
        return tan(ang) / tan(fovY / 2) * viewportHeight / 2
    }

    /** 画面 1px あたりの地表での角度(ラジアン)。画面中央付近での近似。 */
    fun radiansPerPixel(): Double = 2 * altitude * tan(fovY / 2) / viewportHeight

    /**
     * 地球座標 (x,y,z) を画面座標へ投影する。out[0]=sx, out[1]=sy (px, 左上原点), out[2]=NDC 深度。
     * @return 視錐台内(少し余白あり)なら true
     */
    fun project(x: Double, y: Double, z: Double, out: FloatArray): Boolean {
        val m = mvp
        val cx = m[0] * x + m[4] * y + m[8] * z + m[12]
        val cy = m[1] * x + m[5] * y + m[9] * z + m[13]
        val cz = m[2] * x + m[6] * y + m[10] * z + m[14]
        val cw = m[3] * x + m[7] * y + m[11] * z + m[15]
        if (cw <= 1e-9) return false
        val nx = cx / cw
        val ny = cy / cw
        val nz = cz / cw
        out[0] = ((nx + 1) * 0.5 * viewportWidth).toFloat()
        out[1] = ((1 - ny) * 0.5 * viewportHeight).toFloat()
        out[2] = nz.toFloat()
        return nx >= -1.1 && nx <= 1.1 && ny >= -1.1 && ny <= 1.1 && nz <= 1.0
    }

    /** 地表点が視点から見て手前側(地平線より内側)か。 */
    fun isFrontFacing(x: Double, y: Double, z: Double): Boolean {
        val e = eyeInEarth
        return (e[0] - x) * x + (e[1] - y) * y + (e[2] - z) * z > 0
    }

    /** 画面座標から地表(単位球)との交点を求め、緯度経度(ラジアン)を返す。外れたら null。 */
    fun screenToLatLon(sx: Float, sy: Float): DoubleArray? {
        val ray = screenRay(sx, sy)
        val e = eyeInEarth
        val b = 2 * (e[0] * ray[0] + e[1] * ray[1] + e[2] * ray[2])
        val c = e[0] * e[0] + e[1] * e[1] + e[2] * e[2] - 1.0
        val disc = b * b - 4 * c
        if (disc < 0) return null
        val t = (-b - sqrt(disc)) / 2
        if (t < 0) return null
        val px = e[0] + t * ray[0]
        val py = e[1] + t * ray[1]
        val pz = e[2] + t * ray[2]
        return doubleArrayOf(asin(py.coerceIn(-1.0, 1.0)), atan2(px, pz))
    }

    /** 画面座標から地球座標系での視線方向(正規化)。 */
    fun screenRay(sx: Float, sy: Float): DoubleArray {
        val nx = sx / viewportWidth * 2 - 1
        val ny = 1 - sy / viewportHeight * 2
        val aspect = viewportWidth.toDouble() / viewportHeight
        val t = tan(fovY / 2)
        var dx = nx * t * aspect
        var dy = ny * t
        var dz = -1.0
        // カメラ座標系 → 地球座標系: Ry(lon) * Rx(-lat)
        val cl = cos(centerLat)
        val sl = sin(centerLat)
        val y1 = dy * cl + dz * sl
        val z1 = -dy * sl + dz * cl
        dy = y1
        dz = z1
        val cn = cos(centerLon)
        val sn = sin(centerLon)
        val x2 = dx * cn + dz * sn
        val z2 = -dx * sn + dz * cn
        dx = x2
        dz = z2
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        return doubleArrayOf(dx / len, dy / len, dz / len)
    }

    /** 画面中央から地平線までの角度半径(ラジアン)。 */
    fun horizonAngle(): Double = acos(1.0 / (1.0 + altitude))

    /** 地球全体が画面の短辺に収まる高度。 */
    fun fitAltitude(marginRatio: Double = 0.90): Double {
        val shortSide = min(viewportWidth, viewportHeight).toDouble()
        val t = marginRatio * (shortSide / viewportHeight) * tan(fovY / 2)
        val ang = atan(t)
        val d = 1.0 / sin(ang)
        return (d - 1.0).coerceIn(MIN_ALT, MAX_ALT)
    }

    fun copyFrom(o: Camera) {
        centerLat = o.centerLat
        centerLon = o.centerLon
        altitude = o.altitude
    }

    companion object {
        const val MIN_ALT = 0.0025
        const val MAX_ALT = 9.0
        val MAX_LAT: Double = Math.toRadians(89.0)

        fun wrapLon(lon: Double): Double {
            var l = lon
            while (l > PI) l -= 2 * PI
            while (l < -PI) l += 2 * PI
            return l
        }

        /** 緯度経度(ラジアン) → 単位球上の座標。lon=0,lat=0 が +Z。 */
        fun toXyz(lat: Double, lon: Double, out: DoubleArray, radius: Double = 1.0) {
            val cl = cos(lat)
            out[0] = radius * cl * sin(lon)
            out[1] = radius * sin(lat)
            out[2] = radius * cl * cos(lon)
        }

        /** 2 点間の大円角(ラジアン)。 */
        fun angularDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val d = sin(lat1) * sin(lat2) + cos(lat1) * cos(lat2) * cos(lon1 - lon2)
            return acos(d.coerceIn(-1.0, 1.0))
        }
    }
}
