package io.github.hatake716.sekaishiearth.globe

import io.github.hatake716.sekaishiearth.data.Category
import io.github.hatake716.sekaishiearth.data.Entry
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 表示フィルタ。
 * categories/regions は「チェックした項目だけ表示」。何もチェックしていない(空)ときは絞り込みなし＝全件表示。
 * 「全選択」で全項目を明示的に入れた状態と、「全解除」の空集合は、どちらも全件表示になる。
 */
data class MarkerFilter(
    val yearMin: Int = Int.MIN_VALUE,
    val yearMax: Int = Int.MAX_VALUE,
    val categories: Set<Category> = emptySet(),
    val regions: Set<Int> = emptySet(),
) {
    /** 分類で実際に絞られているか(一部だけ選択)。空と全選択はどちらも「絞っていない」。 */
    fun categoryFilterActive(): Boolean = categories.isNotEmpty() && categories.size < Category.entries.size

    /** 地域で実際に絞られているか(一部だけ選択)。regionCount は地域の総数。 */
    fun regionFilterActive(regionCount: Int): Boolean = regions.isNotEmpty() && regions.size < regionCount

    fun yearFilterActive(): Boolean = yearMin != Int.MIN_VALUE || yearMax != Int.MAX_VALUE

    fun accepts(e: Entry): Boolean {
        if (categories.isNotEmpty() && e.category !in categories) return false
        if (regions.isNotEmpty() && e.regionIndex !in regions) return false
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
    /** 代表ピン(ラベル付きの大きいピン)なら true。小さな点は false。 */
    val reps: BooleanArray,
    val count: Int,
    val altitude: Double,
) {
    /**
     * (sx, sy) から radius px 以内のエントリ ID を近い順に返す。
     * 代表ピンに当たっている場合はそれらを優先し、間引かれた小さな点は指のごく近く(radius/3)のものだけ含める。
     */
    fun pick(sx: Float, sy: Float, radius: Float): List<Int> {
        val hits = ArrayList<Triple<Float, Int, Boolean>>()
        for (i in 0 until count) {
            val d = hypot(xs[i] - sx, ys[i] - sy)
            if (d <= radius) hits.add(Triple(d, ids[i], reps[i]))
        }
        if (hits.isEmpty()) return emptyList()
        val anyRep = hits.any { it.third }
        val filtered = if (anyRep) hits.filter { it.third || it.first <= radius / 3 } else hits
        return filtered.sortedBy { it.first }.map { it.second }
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

    @Volatile var snapshot: MarkerSnapshot = MarkerSnapshot(IntArray(0), FloatArray(0), FloatArray(0), BooleanArray(0), 0, 0.0)
        private set

    private val tmp = FloatArray(3)
    private val cellMap = HashMap<Long, Int>()
    private val labelRects = ArrayList<FloatArray>()
    private val snapIds = IntArray(n)
    private val snapXs = FloatArray(n)
    private val snapYs = FloatArray(n)
    private val snapReps = BooleanArray(n)
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
            val sorted = g.sortedWith(compareByDescending<Int> { importance[it] }.thenBy { entries[it].id })
            for ((k, i) in sorted.withIndex()) { groupIndex[i] = k; groupSize[i] = sorted.size }
        }
        priorityOrder = (0 until n).sortedWith(compareByDescending<Int> { importance[it] }.thenBy { entries[it].id }).toIntArray()
    }

    /** 適用済みフィルタ。lost update を避けるため、掴んだ値がまだ最新の時だけ dirty を落とす。 */
    private var appliedFilter: MarkerFilter? = null
    private fun applyFilter() {
        val f = filter
        for (i in 0 until n) enabled[i] = f.accepts(entries[i])
        appliedFilter = f
        // ループ中に UI が別のフィルタを設定していたら dirty のままにして次フレームで再適用する
        if (filter === f) filterDirty = false
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
                if (k > 0) {
                    // リング r には 6r 件配置。中心(k=0)を除き、累積 3r(r-1) < k を満たす最小 r を求める。
                    var ring = 1
                    while (k > 3 * ring * (ring + 1)) ring++
                    val startOfRing = 3 * ring * (ring - 1) // このリング先頭の k-1 の基準
                    val perRing = 6 * ring
                    val idxInRing = (k - 1) - startOfRing
                    val ang = 2 * Math.PI * idxInRing / perRing
                    sx += (spreadRadius * ring * cos(ang)).toFloat()
                    sy += (spreadRadius * ring * sin(ang)).toFloat()
                }
            }
            if (sx < -40 || sy < -40 || sx > w + 40 || sy > h + 40) continue
            visIdx[vis] = i; visX[vis] = sx; visY[vis] = sy; vis++
        }
        // 優先順に代表を決める(セルごとに 1 件)。画面外余白の負座標も含むため floor + オフセットでキーを作る。
        for (o in 0 until vis) {
            isRep[o] = false
            val cx = floor(visX[o] / cell).toInt() + 2
            val cy = floor(visY[o] / cell).toInt() + 2
            val key = cy.toLong() * (cellsX + 4) + cx
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
            snapIds[snapCount] = e.id; snapXs[snapCount] = visX[o]; snapYs[snapCount] = visY[o]; snapReps[snapCount] = rep; snapCount++
        }
        if (!selX.isNaN()) {
            val e = entries.first { it.id == sel }
            val c = e.category.color
            md[k++] = selX; md[k++] = selY; md[k++] = 30f * density; md[k++] = 2f
            md[k++] = c.red; md[k++] = c.green; md[k++] = c.blue; md[k++] = 1f
            snapIds[snapCount] = e.id; snapXs[snapCount] = selX; snapYs[snapCount] = selY; snapReps[snapCount] = true; snapCount++
            if (labels.none { it.id == sel }) labels.add(0, Label(e.id, selX, selY, e.term, 9))
        }
        markerCount = k / 8
        snapshot = MarkerSnapshot(snapIds.copyOf(snapCount), snapXs.copyOf(snapCount), snapYs.copyOf(snapCount), snapReps.copyOf(snapCount), snapCount, camera.altitude)
    }
}
