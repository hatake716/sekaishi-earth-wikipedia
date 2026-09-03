package io.github.hatake716.sekaishiearth.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.hatake716.sekaishiearth.data.Catalog
import io.github.hatake716.sekaishiearth.data.Entry

/** 全用語の一覧。時代順と五十音順を切り替えられる。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(catalog: Catalog, onClose: () -> Unit, onSelect: (Entry) -> Unit) {
    BackHandler(onBack = onClose)
    var mode by rememberSaveable { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    val rows = remember(mode, catalog) {
        val out = ArrayList<Row>()
        if (mode == 0) {
            // 時代順: period → 年代 → 用語
            val sorted = catalog.entries.sortedWith(
                compareBy({ it.periodIndex }, { it.year ?: Int.MAX_VALUE }, { it.id }),
            )
            var lastPeriod = -1
            for (e in sorted) {
                if (e.periodIndex != lastPeriod) {
                    out.add(Row.Header(catalog.periods.getOrNull(e.periodIndex) ?: "時代不明", 0))
                    lastPeriod = e.periodIndex
                }
                out.add(Row.Item(e))
            }
        } else {
            // 五十音順: 用語の読みを NFKC 正規化した先頭文字で行分けし、行内も読みでソート
            val sorted = catalog.entries.sortedWith(
                compareBy({ sortKey(it.term) }, { it.id }),
            )
            var lastInitial = ""
            for (e in sorted) {
                val ini = initialOf(e.term)
                if (ini != lastInitial) { out.add(Row.Header(ini, 1)); lastInitial = ini }
                out.add(Row.Item(e))
            }
        }
        out
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("用語一覧", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "閉じる") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                SegmentedButton(selected = mode == 0, onClick = { mode = 0 }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("時代順") }
                SegmentedButton(selected = mode == 1, onClick = { mode = 1 }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("五十音順") }
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(rows) { r ->
                    when (r) {
                        is Row.Header -> {
                            val style = when (r.level) {
                                0 -> MaterialTheme.typography.titleMedium
                                else -> MaterialTheme.typography.titleSmall
                            }
                            val bg = when (r.level) {
                                0 -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.surfaceContainerHigh
                            }
                            Text(
                                r.text,
                                style = style,
                                fontWeight = if (r.level == 0) FontWeight.Bold else FontWeight.SemiBold,
                                modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 16.dp, vertical = if (r.level == 0) 10.dp else 6.dp),
                            )
                        }
                        is Row.Item -> {
                            SearchResultRow(r.entry) { onSelect(r.entry) }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                        }
                    }
                }
            }
        }
    }
}

/** rows の各要素に一意で安定なキーを与える(Header はインデックス、Item は id)。 */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    rows: List<Row>,
    content: @Composable (Row) -> Unit,
) {
    items(
        count = rows.size,
        key = { i -> when (val r = rows[i]) { is Row.Item -> r.entry.id.toLong(); is Row.Header -> -(i.toLong()) - 1 } },
    ) { i -> content(rows[i]) }
}

private sealed class Row {
    class Header(val text: String, val level: Int) : Row()
    class Item(val entry: Entry) : Row()
}

/** 並べ替え用の読みキー: NFKC + ひらがな→カタカナ。 */
private fun sortKey(term: String): String {
    val n = java.text.Normalizer.normalize(term, java.text.Normalizer.Form.NFKC)
    val sb = StringBuilder(n.length)
    for (ch in n) sb.append(if (ch in 'ぁ'..'ゖ') ch + 0x60 else ch)
    return sb.toString()
}

/** 五十音の行見出し(ア行・カ行…)。アルファベット始まりは「A〜Z・数字」、漢字始まりは「漢字」。 */
private fun initialOf(term: String): String {
    val c = term.firstOrNull() ?: return "その他"
    if (c in 'A'..'Z' || c in 'a'..'z' || c in 'Ａ'..'Ｚ' || c in 'ａ'..'ｚ' || c in '0'..'9' || c in '０'..'９') return "A〜Z・数字"
    val kana = java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFKC).first()
    val k = if (kana in 'ぁ'..'ゖ') kana + 0x60 else kana
    return when (k) {
        in 'ァ'..'オ' -> "ア行"
        in 'カ'..'ゴ' -> "カ行"
        in 'サ'..'ゾ' -> "サ行"
        in 'タ'..'ド' -> "タ行"
        in 'ナ'..'ノ' -> "ナ行"
        in 'ハ'..'ポ' -> "ハ行"
        in 'マ'..'モ' -> "マ行"
        in 'ャ'..'ヨ' -> "ヤ行"
        in 'ラ'..'ロ' -> "ラ行"
        in 'ヮ'..'ヴ', 'ワ', 'ヰ', 'ヱ', 'ヲ', 'ン' -> "ワ行"
        else -> "漢字・その他"
    }
}
