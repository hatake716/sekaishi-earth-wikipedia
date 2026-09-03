package io.github.hatake716.sekaishiearth.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

/** 全用語の一覧。世界史の窓の目次順と五十音順を切り替えられる。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(catalog: Catalog, onClose: () -> Unit, onSelect: (Entry) -> Unit) {
    BackHandler(onBack = onClose)
    var mode by rememberSaveable { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    val rows = remember(mode, catalog) {
        val out = ArrayList<Row>()
        if (mode == 0) {
            val sorted = catalog.entries.sortedWith(compareBy({ it.order }, { it.id }))
            var lastChapter = -1
            var lastSection = -1
            var lastSub = ""
            for (e in sorted) {
                if (e.chapterIndex != lastChapter) {
                    out.add(Row.Header(catalog.chapters.getOrNull(e.chapterIndex) ?: "", 0))
                    lastChapter = e.chapterIndex; lastSection = -1; lastSub = ""
                }
                if (e.sectionIndex != lastSection) {
                    out.add(Row.Header(catalog.sections.getOrNull(e.sectionIndex) ?: "", 1))
                    lastSection = e.sectionIndex; lastSub = ""
                }
                if (e.sub != lastSub && e.sub.isNotBlank()) {
                    out.add(Row.Header(e.sub, 2))
                    lastSub = e.sub
                }
                out.add(Row.Item(e))
            }
        } else {
            var lastInitial = ""
            for (e in catalog.entries.sortedBy { it.id }) {
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
                SegmentedButton(selected = mode == 0, onClick = { mode = 0 }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("目次順") }
                SegmentedButton(selected = mode == 1, onClick = { mode = 1 }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("五十音順") }
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(rows, key = { r -> when (r) { is Row.Item -> "e${r.entry.id}"; is Row.Header -> "h${r.level}-${r.text}-${rows.indexOf(r)}" } }) { r ->
                    when (r) {
                        is Row.Header -> {
                            val style = when (r.level) {
                                0 -> MaterialTheme.typography.titleMedium
                                1 -> MaterialTheme.typography.titleSmall
                                else -> MaterialTheme.typography.labelLarge
                            }
                            val bg = when (r.level) {
                                0 -> MaterialTheme.colorScheme.primaryContainer
                                1 -> MaterialTheme.colorScheme.surfaceContainerHigh
                                else -> MaterialTheme.colorScheme.surfaceContainer
                            }
                            Text(
                                r.text,
                                style = style,
                                fontWeight = if (r.level == 0) FontWeight.Bold else FontWeight.SemiBold,
                                modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 16.dp + (r.level * 8).dp, vertical = if (r.level == 0) 10.dp else 6.dp),
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

private sealed class Row {
    class Header(val text: String, val level: Int) : Row()
    class Item(val entry: Entry) : Row()
}

/** 五十音の行見出し(ア・カ・サ…)。アルファベット始まりは「A〜Z」。 */
private fun initialOf(term: String): String {
    val c = term.firstOrNull() ?: return "その他"
    if (c in 'A'..'Z' || c in 'a'..'z' || c in 'Ａ'..'Ｚ' || c in '0'..'9' || c in '０'..'９') return "A〜Z・数字"
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
        in 'ヮ'..'ン' -> "ワ行"
        else -> "漢字"
    }
}
