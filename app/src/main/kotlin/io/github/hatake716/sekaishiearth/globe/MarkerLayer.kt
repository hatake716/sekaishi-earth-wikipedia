package io.github.hatake716.sekaishiearth.globe

import io.github.hatake716.sekaishiearth.data.Category
import io.github.hatake716.sekaishiearth.data.Entry
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** 表示フィルタ。 */
data class MarkerFilter(
    val yearMin: Int = Int.MIN_VALUE,
    val yearMax: Int = Int.MAX_VALUE,
    val categories: Set<Category> = Category.entries.toSet(),
    val chapters: Set<Int>? = null,
) {
    fun accepts(e: Entry): Boolean {
        if (e.category !in categories) return false
        if (chapters != null && e.chapterIndex !in chapters) return false
        if (yearMin != Int.MIN_VALUE || yearMax != Int.MAX_VALUE) {
            val y = e.year ?: return true // 年不明は常に表示
            val ye = e.yearEnd ?: y
            if (ye < yearMin || y > yearMax) return false
        }
        return true
    }
}

/** 1 フレーム分の投影結果(UI スレッドからのヒットテスト用に不変スナップショットとして公開)。 */
class MarkerSnapshot(
    val ids: IntArray,
    val xs: FloatArray,
    val ys: FloatArray,
    val count: Int,
    val altitude: Double,
) {
    /** (sx, sy) から radius px 以内のエントリ ID を近い順に返す。 */
    fun pick(sx: Float, sy: Float, radius: Float): List<Int> {
        val hits = ArrayList<Pair<Float, Int>>()
        for (i in 0 until count) {
            val d = hypot(xs[i] - sx, ys[i] - sy)
            if (d <= radius) hits.add(d to ids[i])
        }
        return hits.sortedBy { it.first }.map { it.second }
    }
}

/**
 * ピンの投影・間引き・ラベル配置を CPU で毎フレーム行う。
 * 結果は GL 描画用配列(markerData / labelList)と、UI スレッド用スナップショットに書き出す。
 */
class MarkerLayer(private val entries: List<Entry>, private val density: Float) {

    private val n = entries.size
    private val px = DoubleArray(n)
    private val py = DoubleArray(n)
    private val pz = DoubleArray(n)
    private val enabled = BooleanArray(n) { true }
    private val groupIndex = IntArray(n)   // 同一座標グループ内での順番
    private val groupSize = IntArray(n)
    private val importance = IntArray(n)

    @Volatile var filter: MarkerFilter = MarkerFilter()
        set(value) {
            if (value == field) return
            field = value
            filterDirty = true
        }
    @Volatile private var filterDirty = true
    @Volatile var selectedId: Int = -1

    /** 描画用: 1 マーカー = 8 float (x, y, size, style, r, g, b, a)。 */
    var markerData = FloatArray(n * 8)
        private set
    var markerCount = 0
        private set

    /** left=true ならピンの左側にラベルを置く。 */
    class Label(val id: Int, val x: Float, val y: Float, val text: String, val priority: Int, val left: Boolean = false)
    val labels = ArrayList<Label>()

    @Volatile var snapshot: MarkerSnapshot = MarkerSnapshot(IntArray(0), FloatArray(0), FloatArray(0), 0, 0.0)
        private set

    private val tmp = FloatArray(3)
    private val cellMap = HashMap<Long, Int>()
    private val labelRects = ArrayList<FloatArray>()
    private val snapIds = IntArray(n)
    private val snapXs = FloatArray(n)
    private val snapYs = FloatArray(n)
    /** 重要度降順→目次順に並べた添字。表示順・代表選出はこの順で走査すれば済む。 */
    private val priorityOrder: IntArray
    private val visIdx = IntArray(n)
    private val visX = FloatArray(n)
    private val visY = FloatArray(n)
    private val isRep = BooleanArray(n)

    init {
        val xyz = DoubleArray(3)
        val groups = HashMap<Long, ArrayList<Int>>()
        for (i in 0 until n) {
            val e = entries[i]
            Camera.toXyz(Math.toRadians(e.lat), Math.toRadians(e.lon), xyz)
            px[i] = xyz[0]; py[i] = xyz[1]; pz[i] = xyz[2]
            importance[i] = e.importance
            val gk = (Math.round(e.lat * 200.0) shl 32) or (Math.round(e.lon * 200.0) and 0xffffffffL)
            groups.getOrPut(gk) { ArrayList() }.add(i)
        }
        for (g in groups.values) {
            val sorted = g.sortedWith(compareByDescending<Int> { importance[it] }.thenBy { entries[it].order })
            for ((k, i) in sorted.withIndex()) { groupIndex[i] = k; groupSize[i] = sorted.size }
        }
        priorityOrder = (0 until n).sortedWith(compareByDescending<Int> { importance[it] }.thenBy { entries[it].order }).toIntArray()
    }

    private fun applyFilter() {
        val f = filter
        for (i in 0 until n) enabled[i] = f.accepts(entries[i])
        filterDirty = false
    }

    /** 表示中の(フィルタ通過)件数。 */
    fun enabledCount(): Int {
        if (filterDirty) applyFilter()
        return enabled.count { it }
    }

    /**
     * 投影と間引きを行う。GL スレッドから毎フレーム呼ぶ。
     */
    fun update(camera: Camera) {
        if (filterDirty) applyFilter()
        val w = camera.viewportWidth.toFloat()
        val h = camera.viewportHeight.toFloat()
        val rpp = camera.radiansPerPixel()
        // 同一座標グループを円状に展開するズーム閾値(1px が約 0.002° 以下)
        val spread = rpp < 4e-5
        val spreadRadius = 26f * density
        val cell = (34f * density)
        val cellsX = (w / cell).toInt() + 2
        cellMap.clear()
        labels.clear()
        labelRects.clear()
        markerCount = 0
        var snapCount = 0
        val eye = camera.eyeInEarth
        val sel = selectedId
        // まず表示対象を投影(優先順に走査するので vis 配列は優先順に並ぶ)
        var vis = 0
        val limbMargin = 0.02
        for (pi in 0 until n) {
            val i = priorityOrder[pi]
            if (!enabled[i]) continue
            val x = px[i]; val y = py[i]; val z = pz[i]
            // 地平線の少し内側までを表示
            if ((eye[0] - x) * x + (eye[1] - y) * y + (eye[2] - z) * z <= limbMargin) continue
            if (!camera.project(x, y, z, tmp)) continue
            var sx = tmp[0]; var sy = tmp[1]
            if (spread && groupSize[i] > 1) {
                val k = groupIndex[i]
                val ring = (k + 5) / 6
                val perRing = 6 * ring
                val idxInRing = (k - 1) - 6 * (ring - 1) * ring / 2
                if (k > 0) {
                    val ang = 2 * Math.PI * idxInRing / perRing
                    sx += (spreadRadius * ring * cos(ang)).toFloat()
                    sy += (spreadRadius * ring * sin(ang)).toFloat()
                }
            }
            if (sx < -40 || sy < -40 || sx > w + 40 || sy > h + 40) continue
            visIdx[vis] = i; visX[vis] = sx; visY[vis] = sy; vis++
        }
        // 優先順に代表を決める(セルごとに 1 件)
        for (o in 0 until vis) {
            isRep[o] = false
            val cx = (visX[o] / cell).toInt(); val cy = (visY[o] / cell).toInt()
            val key = cy.toLong() * cellsX + cx
            if (!cellMap.containsKey(key)) { cellMap[key] = o; isRep[o] = true }
        }
        val fewMode = vis <= 120
        val centerX = w / 2; val centerY = h / 2
        // ラベル候補: 代表(少数なら全件)を重要度→画面中央からの距離で並べ、重ならないものだけ採用
        val labelCandidates = (0 until vis).filter { isRep[it] || fewMode }
            .sortedWith(compareByDescending<Int> { importance[visIdx[it]] }.thenBy { hypot(visX[it] - centerX, visY[it] - centerY) })
        val maxLabels = if (fewMode) 60 else 36
        val labelH = 20f * density
        val charW = 12.5f * density
        for (o in labelCandidates) {
            if (labels.size >= maxLabels) break
            val i = visIdx[o]
            val e = entries[i]
            val text = e.term
            val lw = text.length * charW + 12 * density
            val ly = visY[o] - labelH / 2
            var placed = false
            for (side in 0..1) {
                val lx = if (side == 0) visX[o] + 10 * density else visX[o] - 10 * density - lw
                val rect = floatArrayOf(lx, ly, lx + lw, ly + labelH)
                if (rect[2] > w + 4 || rect[0] < -4) continue
                var overlap = false
                for (r in labelRects) {
                    if (rect[0] < r[2] && rect[2] > r[0] && rect[1] < r[3] && rect[3] > r[1]) { overlap = true; break }
                }
                if (overlap) continue
                labelRects.add(rect)
                labels.add(Label(e.id, visX[o], visY[o], text, importance[i], left = side == 1))
                placed = true
                break
            }
            if (!placed) continue
        }
        // 描画データ
        val md = markerData
        var k = 0
        var selX = Float.NaN; var selY = Float.NaN
        for (o in 0 until vis) {
            val i = visIdx[o]
            val e = entries[i]
            val c = e.category.color
            val rep = isRep[o] || fewMode
            val isSel = e.id == sel
            if (isSel) { selX = visX[o]; selY = visY[o]; continue }
            md[k++] = visX[o]; md[k++] = visY[o]
            if (rep) {
                md[k++] = (11f + 2.5f * importance[i]) * density; md[k++] = 1f
                md[k++] = c.red; md[k++] = c.green; md[k++] = c.blue; md[k++] = 1f
            } else {
                md[k++] = 5f * density; md[k++] = 0f
                md[k++] = c.red; md[k++] = c.green; md[k++] = c.blue; md[k++] = 0.75f
            }
            snapIds[snapCount] = e.id; snapXs[snapCount] = visX[o]; snapYs[snapCount] = visY[o]; snapCount++
        }
        if (!selX.isNaN()) {
            val e = entries.first { it.id == sel }
            val c = e.category.color
            md[k++] = selX; md[k++] = selY; md[k++] = 30f * density; md[k++] = 2f
            md[k++] = c.red; md[k++] = c.green; md[k++] = c.blue; md[k++] = 1f
            snapIds[snapCount] = e.id; snapXs[snapCount] = selX; snapYs[snapCount] = selY; snapCount++
            if (labels.none { it.id == sel }) labels.add(0, Label(e.id, selX, selY, e.term, 9))
        }
        markerCount = k / 8
        snapshot = MarkerSnapshot(snapIds.copyOf(snapCount), snapXs.copyOf(snapCount), snapYs.copyOf(snapCount), snapCount, camera.altitude)
    }
}
