package io.github.hatake716.sekaishiearth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.sekaishiearth.data.formatYear
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * メイン画面右端に縦に置く年代レンジバー。
 * 上端が新しい年代(maxYear)、下端が古い年代(minYear)。2 つのハンドルで範囲を絞る。
 *
 * @param yearMin/yearMax 現在の選択範囲(Int.MIN_VALUE/MAX_VALUE は「端まで」= 制限なし)
 * @param onRangeChange 範囲確定時に (下端年, 上端年) を渡す。端まで動かした側は限界値そのもの。
 */
@Composable
fun YearRangeBar(
    yearMin: Int,
    yearMax: Int,
    minYear: Int,
    maxYear: Int,
    modifier: Modifier = Modifier,
    onRangeChange: (Int, Int) -> Unit,
    onEditUpper: () -> Unit = {},
    onEditLower: () -> Unit = {},
) {
    val span = (maxYear - minYear).toFloat().coerceAtLeast(1f)
    fun toFrac(y: Int) = ((y - minYear) / span).coerceIn(0f, 1f)
    val loInit = if (yearMin == Int.MIN_VALUE) 0f else toFrac(yearMin)
    val hiInit = if (yearMax == Int.MAX_VALUE) 1f else toFrac(yearMax)

    // 内部状態(0..1、0=最古/下端, 1=最新/上端)。lo <= hi を保つ。
    // ドラッグ中は自前の値を優先し、外部(フィルタ)の変化はドラッグしていない時だけ取り込む。
    var lo by remember { mutableFloatStateOf(loInit) }
    var hi by remember { mutableFloatStateOf(hiInit) }
    var dragging by remember { mutableStateOf(false) }
    var trackHeightPx by remember { mutableFloatStateOf(1f) }
    val density = LocalDensity.current

    // 外部から yearMin/yearMax が変わったら(数値入力等)、ドラッグ中でなければ同期する。
    androidx.compose.runtime.LaunchedEffect(yearMin, yearMax) {
        if (!dragging) { lo = loInit; hi = hiInit }
    }

    fun yearAt(frac: Float): Int = (minYear + frac * span).roundToInt()

    fun commit() {
        val loY = if (lo <= 0.001f) Int.MIN_VALUE else yearAt(lo)
        val hiY = if (hi >= 0.999f) Int.MAX_VALUE else yearAt(hi)
        onRangeChange(loY, hiY)
    }

    // トラック上の y(px, 上原点) = (1 - frac) * height。上が新しい(hi)、下が古い(lo)。
    fun fracToY(frac: Float) = (1f - frac) * trackHeightPx
    fun yToFrac(y: Float) = (1f - (y / trackHeightPx.coerceAtLeast(1f))).coerceIn(0f, 1f)

    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val handleH = with(density) { 9.dp.toPx() }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 上端(最新)の年ラベル。タップで数値入力。
        Text(
            text = if (hi >= 0.999f) "現在" else formatYear(yearAt(hi)),
            color = onSurface,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .width(52.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onEditUpper)
                .padding(vertical = 2.dp),
        )
        Spacer(Modifier.height(4.dp))

        // トラック本体
        Box(
            modifier = Modifier
                .weight(1f)
                .width(40.dp)
                .pointerInput(minYear, maxYear) {
                    detectTapGestures { pos ->
                        val f = yToFrac(pos.y)
                        if (abs(f - lo) <= abs(f - hi)) lo = f.coerceAtMost(hi) else hi = f.coerceAtLeast(lo)
                        commit()
                    }
                }
                .pointerInput(minYear, maxYear) {
                    var draggingLo = false
                    detectDragGestures(
                        onDragStart = { pos ->
                            dragging = true
                            val f = yToFrac(pos.y)
                            // 掴んだ側を即座にその位置へ寄せ、指の下にハンドルを付ける
                            draggingLo = abs(f - lo) <= abs(f - hi)
                            if (draggingLo) lo = f.coerceIn(0f, hi) else hi = f.coerceIn(lo, 1f)
                            commit()
                        },
                        onDragEnd = { dragging = false; commit() },
                        onDragCancel = { dragging = false; commit() },
                        onDrag = { change, _ ->
                            change.consume()
                            val f = yToFrac(change.position.y)
                            if (draggingLo) lo = f.coerceIn(0f, hi) else hi = f.coerceIn(lo, 1f)
                            // ドラッグ中も即時反映して指に追従させる
                            commit()
                        },
                    )
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            val trackWidth = 5.dp
            // トラック(非アクティブ全体)。高さを測ってスケールに使う。
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(trackWidth)
                    .background(inactive, RoundedCornerShape(50))
                    .onHeight { trackHeightPx = it },
            )
            // アクティブ区間(lo..hi)
            val topY = fracToY(hi)
            val botY = fracToY(lo)
            Box(
                Modifier
                    .width(trackWidth)
                    .offsetTop(topY)
                    .heightPx((botY - topY).coerceAtLeast(0f))
                    .background(active, RoundedCornerShape(50)),
            )
            // ハンドル(上=hi=新しい, 下=lo=古い)
            Handle(color = active, top = fracToY(hi) - handleH)
            Handle(color = active, top = fracToY(lo) - handleH)
        }

        Spacer(Modifier.height(4.dp))
        // 下端(最古)の年ラベル。タップで数値入力。
        Text(
            text = if (lo <= 0.001f) "最古" else formatYear(yearAt(lo)),
            color = onSurface,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .width(52.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onEditLower)
                .padding(vertical = 2.dp),
        )
    }
}

@Composable
private fun Handle(color: Color, top: Float) {
    Box(
        Modifier
            .offsetTop(top)
            .size(18.dp)
            .background(Color.White, RoundedCornerShape(50))
            .padding(3.dp)
            .background(color, RoundedCornerShape(50)),
    )
}

/** 高さ(px)をコールバックで受け取る Modifier。 */
private fun Modifier.onHeight(cb: (Float) -> Unit): Modifier =
    this.then(Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        cb(placeable.height.toFloat())
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    })

/** 親の上端からの px オフセットで配置する。 */
private fun Modifier.offsetTop(px: Float): Modifier =
    this.then(Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.place(0, px.roundToInt()) }
    })

/** px 指定の高さ。 */
private fun Modifier.heightPx(px: Float): Modifier =
    this.then(Modifier.layout { measurable, constraints ->
        val h = px.roundToInt().coerceAtLeast(0)
        val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
        layout(placeable.width, h) { placeable.place(0, 0) }
    })

