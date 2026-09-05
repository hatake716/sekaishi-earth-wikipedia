package io.github.hatake716.sekaishiearth.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.hatake716.sekaishiearth.data.Catalog
import io.github.hatake716.sekaishiearth.data.Category
import io.github.hatake716.sekaishiearth.data.Entry

/** 並び順。 */
private enum class SortMode { ERA, KANA }

/**
 * 用語一覧の共通画面。全件表示にもブックマーク表示にも使う。
 * - 並び替え: 年代順(既定) / 五十音順
 * - 絞り込み: 分類・地域
 * - 各行に既読マークとブックマークのトグルを表示
 *
 * @param source 表示対象の用語(全件、またはブックマーク済み)
 * @param title 画面タイトル
 * @param emptyMessage source が空のときの案内
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryListScreen(
    catalog: Catalog,
    source: List<Entry>,
    title: String,
    emptyMessage: String,
    readIds: Set<Int>,
    bookmarkIds: List<Int>,
    onClose: () -> Unit,
    onSelect: (Entry) -> Unit,
    onToggleBookmark: (Int) -> Unit,
) {
    BackHandler(onBack = onClose)
    var sort by rememberSaveable { mutableStateOf(SortMode.ERA) }
    var showFilterRow by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    // チェックした項目だけ表示。空(何もチェックしていない)は絞り込みなし＝全件。地球儀側の MarkerFilter と同じ意味。
    var catFilter by remember { mutableStateOf<Set<Category>>(emptySet()) }
    var regionFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val listState = rememberLazyListState()

    val rows = remember(source, sort, catFilter, regionFilter, query, catalog) {
        val q = Catalog.normalize(query)
        val filtered = source.filter { e ->
            if (catFilter.isNotEmpty() && e.category !in catFilter) return@filter false
            if (regionFilter.isNotEmpty() && e.regionIndex !in regionFilter) return@filter false
            if (q.isNotEmpty()) {
                val hit = Catalog.normalize(e.term).contains(q) ||
                    e.aliases.any { Catalog.normalize(it).contains(q) } ||
                    Catalog.normalize(e.wikiTitle).contains(q) ||
                    Catalog.normalize(e.place).contains(q)
                if (!hit) return@filter false
            }
            true
        }
        buildRows(filtered, sort, catalog)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("$title(${"%,d".format(source.size)})", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "閉じる") } },
                actions = {
                    IconButton(onClick = {
                        showSearch = !showSearch
                        if (!showSearch) query = ""
                    }) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "この一覧内を検索",
                            tint = if (query.isNotEmpty()) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(onClick = { showFilterRow = !showFilterRow }) {
                        Icon(
                            Icons.Default.FilterList,
                            contentDescription = "絞り込み",
                            // 一部だけ選択しているときだけ「絞り込み中」。空も全選択も絞っていない扱い。
                            tint = if (
                                (catFilter.isNotEmpty() && catFilter.size < Category.entries.size) ||
                                (regionFilter.isNotEmpty() && regionFilter.size < catalog.regions.size)
                            ) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (showSearch) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "クリア") }
                        }
                    },
                    placeholder = { Text("この一覧内を検索") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                SegmentedButton(selected = sort == SortMode.ERA, onClick = { sort = SortMode.ERA }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("年代順") }
                SegmentedButton(selected = sort == SortMode.KANA, onClick = { sort = SortMode.KANA }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("五十音順") }
            }

            if (showFilterRow) {
                // 全選択 / 全解除(地球儀の絞り込みシートと同じ操作)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.TextButton(onClick = {
                        catFilter = Category.entries.toSet()
                        regionFilter = catalog.regions.indices.toSet()
                    }) { Text("全選択", style = MaterialTheme.typography.labelMedium) }
                    androidx.compose.material3.TextButton(onClick = {
                        catFilter = emptySet()
                        regionFilter = emptySet()
                    }) { Text("全解除", style = MaterialTheme.typography.labelMedium) }
                }
                // 分類チップ(横スクロール)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (c in Category.entries) {
                        val on = c in catFilter
                        FilterChip(selected = on, onClick = {
                            catFilter = catFilter.toMutableSet().apply { if (on) remove(c) else add(c) }
                        }, label = { Text(c.label, style = MaterialTheme.typography.labelSmall) })
                    }
                }
                Spacer(Modifier.height(4.dp))
                // 地域チップ(横スクロール)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for ((i, rg) in catalog.regions.withIndex()) {
                        val on = i in regionFilter
                        FilterChip(selected = on, onClick = {
                            regionFilter = regionFilter.toMutableSet().apply { if (on) remove(i) else add(i) }
                        }, label = { Text(rg, style = MaterialTheme.typography.labelSmall) })
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            val shownCount = rows.count { it is Row.Item }
            Text(
                "${"%,d".format(shownCount)} 件",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
            )

            if (source.isEmpty()) {
                Text(emptyMessage, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (shownCount == 0) {
                Text(
                    if (query.isNotEmpty()) "「$query」に一致する用語がありません" else "条件に一致する用語がありません",
                    Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(
                        count = rows.size,
                        key = { i -> when (val r = rows[i]) { is Row.Item -> r.entry.id.toLong(); is Row.Header -> -(i.toLong()) - 1 } },
                    ) { i ->
                        when (val r = rows[i]) {
                            is Row.Header -> {
                                Text(
                                    r.text,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 16.dp, vertical = 6.dp),
                                )
                            }
                            is Row.Item -> {
                                SearchResultRow(
                                    r.entry,
                                    onClick = { onSelect(r.entry) },
                                    read = r.entry.id in readIds,
                                    bookmarked = r.entry.id in bookmarkIds,
                                    onToggleBookmark = { onToggleBookmark(r.entry.id) },
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                            }
                        }
                    }
                }
            }
        }
    }
}

private sealed class Row {
    class Header(val text: String) : Row()
    class Item(val entry: Entry) : Row()
}

private fun buildRows(entries: List<Entry>, sort: SortMode, catalog: Catalog): List<Row> {
    val out = ArrayList<Row>()
    if (sort == SortMode.ERA) {
        val sorted = entries.sortedWith(compareBy({ it.periodIndex }, { it.year ?: Int.MAX_VALUE }, { it.id }))
        var last = -1
        for (e in sorted) {
            if (e.periodIndex != last) {
                out.add(Row.Header(catalog.periods.getOrNull(e.periodIndex) ?: "時代不明"))
                last = e.periodIndex
            }
            out.add(Row.Item(e))
        }
    } else {
        // 読み(yomi)があればそれを、なければ用語名を正規化した読みキーで並べ替える。
        // キーは 1 回だけ計算する(比較のたびの再正規化を避ける)。
        val keyed = entries.map { it to sortKey(if (it.yomi.isNotEmpty()) it.yomi else it.term) }
            .sortedWith(compareBy({ it.second }, { it.first.id }))
        var last = ""
        for ((e, key) in keyed) {
            val ini = initialOfKey(key)
            if (ini != last) { out.add(Row.Header(ini)); last = ini }
            out.add(Row.Item(e))
        }
    }
    return out
}

/** 並べ替え用の読みキー: NFKC + ひらがな→カタカナ。 */
private fun sortKey(s: String): String {
    val n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC)
    val sb = StringBuilder(n.length)
    for (ch in n) sb.append(if (ch in 'ぁ'..'ゖ') ch + 0x60 else ch)
    return sb.toString()
}

/** 正規化済みの読みキーから行見出しを導く(sortKey と同じ正規化結果を使い回す)。 */
private fun initialOfKey(key: String): String {
    val k = key.firstOrNull() ?: return "その他"
    if (k in 'A'..'Z' || k in 'a'..'z' || k in '0'..'9') return "A〜Z・数字"
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
