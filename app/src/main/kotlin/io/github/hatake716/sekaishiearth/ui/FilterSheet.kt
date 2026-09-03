package io.github.hatake716.sekaishiearth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.hatake716.sekaishiearth.data.Catalog
import io.github.hatake716.sekaishiearth.data.Category
import io.github.hatake716.sekaishiearth.data.formatYear
import io.github.hatake716.sekaishiearth.globe.MarkerFilter
import kotlin.math.roundToInt

/** 年代・分類・章で表示するピンを絞り込む。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(vm: MainViewModel, catalog: Catalog, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val f = vm.filter
    val minY = -3500f
    val maxY = 2030f
    var range by remember(f) {
        mutableStateOf(
            (if (f.yearMin == Int.MIN_VALUE) minY else f.yearMin.toFloat())..(if (f.yearMax == Int.MAX_VALUE) maxY else f.yearMax.toFloat())
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("絞り込み", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.resetFilter(); range = minY..maxY }) { Text("リセット") }
            }
            val shown = remember(f) { catalog.entries.count { f.accepts(it) } }
            Text("${"%,d".format(shown)} 件を表示中", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(16.dp))
            Text("年代", style = MaterialTheme.typography.titleSmall)
            val lo = range.start.roundToInt()
            val hi = range.endInclusive.roundToInt()
            Text(
                if (lo <= minY.toInt() && hi >= maxY.toInt()) "すべての年代" else "${if (lo <= minY.toInt()) "" else formatYear(yearOf(lo))} 〜 ${if (hi >= maxY.toInt()) "" else formatYear(yearOf(hi))}",
                style = MaterialTheme.typography.bodyMedium,
            )
            RangeSlider(
                value = range,
                onValueChange = { range = it },
                valueRange = minY..maxY,
                steps = 0,
                onValueChangeFinished = {
                    val s = range.start.roundToInt()
                    val e = range.endInclusive.roundToInt()
                    vm.filter = vm.filter.copy(
                        yearMin = if (s <= minY.toInt()) Int.MIN_VALUE else yearOf(s),
                        yearMax = if (e >= maxY.toInt()) Int.MAX_VALUE else yearOf(e),
                    )
                },
            )
            Text("年不明の用語は常に表示されます", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(16.dp))
            Text("分類", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in Category.entries) {
                    val on = c in f.categories
                    FilterChip(
                        selected = on,
                        onClick = {
                            val set = f.categories.toMutableSet()
                            if (on) set.remove(c) else set.add(c)
                            vm.filter = f.copy(categories = set)
                        },
                        label = { Text(c.label) },
                        leadingIcon = { Box(Modifier.size(10.dp).background(c.color, CircleShape)) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("地域", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((i, rg) in catalog.regions.withIndex()) {
                    // regions==null は「全地域表示」。分類チップと同じく選択=表示の直感に合わせる。
                    val selected = f.regions == null || i in f.regions
                    FilterChip(
                        selected = selected,
                        onClick = {
                            val cur = f.regions?.toMutableSet() ?: catalog.regions.indices.toMutableSet()
                            if (selected) cur.remove(i) else cur.add(i)
                            vm.filter = f.copy(
                                regions = if (cur.size == catalog.regions.size) null else cur,
                            )
                        },
                        label = { Text(rg, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        }
    }
}

/** スライダー値(線形)→年。紀元前側を圧縮して近代を細かく動かせるようにする。 */
private fun yearOf(v: Int): Int = v
