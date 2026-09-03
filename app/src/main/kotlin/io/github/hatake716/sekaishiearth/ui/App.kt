package io.github.hatake716.sekaishiearth.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.hatake716.sekaishiearth.data.Entry
import io.github.hatake716.sekaishiearth.data.eraLabel
import io.github.hatake716.sekaishiearth.globe.GlobeView

@Composable
fun App(vm: MainViewModel = viewModel()) {
    val catalog = vm.catalog
    val error = vm.loadError
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            error != null -> Text("データの読み込みに失敗しました\n$error", Modifier.align(Alignment.Center).padding(24.dp))
            catalog == null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("地球儀を準備しています…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> GlobeScreen(vm)
        }
        val url = vm.webUrl
        if (url != null) {
            WebScreen(url = url, title = vm.webTitle, onClose = { vm.closeWeb() })
        }
    }
}

@Composable
private fun BoxScope.GlobeScreen(vm: MainViewModel) {
    val catalog = vm.catalog ?: return
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    var globe by remember { mutableStateOf<GlobeView?>(null) }

    val selected = vm.selectedEntry

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            GlobeView(
                ctx,
                catalog.entries,
                onTap = { ids ->
                    when (ids.size) {
                        0 -> vm.clearSelection()
                        1 -> vm.select(ids[0])
                        else -> vm.pickCandidates = ids.mapNotNull { catalog.byId(it) }
                    }
                },
                onCameraIdle = { lat, lon, alt ->
                    vm.saveCamera(Math.toDegrees(lat), Math.toDegrees(lon), alt)
                },
            ).also {
                it.setCamera(vm.savedLat, vm.savedLon, vm.savedAlt)
                globe = it
            }
        },
        update = { view ->
            view.setFilter(vm.filter)
            view.setSelected(vm.selectedId)
        },
    )

    // ライフサイクル: GLSurfaceView の pause/resume
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, globe) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> globe?.onResume()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> globe?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ---- 上部: 検索バー ----
    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 12.dp, vertical = 8.dp)) {
        SearchBar(
            query = vm.query,
            onQueryChange = { vm.updateQuery(it) },
            onMenu = { vm.showList = true },
            onFilter = { vm.showFilter = true },
            filterActive = vm.isFilterActive(),
            onClear = { vm.updateQuery(""); focus.clearFocus() },
        )
        AnimatedVisibility(visible = vm.query.isNotBlank(), enter = fadeIn(), exit = fadeOut()) {
            Surface(
                Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(max = 380.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 3.dp,
            ) {
                val results = vm.searchResults
                // 退場フェード中(query が空)に「該当なし」がちらつかないよう query 非空を条件に含める
                if (results.isEmpty()) {
                    if (vm.query.isNotBlank()) {
                        Text("該当する用語がありません", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn {
                        items(results, key = { it.id }) { e ->
                            SearchResultRow(e) {
                                focus.clearFocus()
                                vm.updateQuery("")
                                vm.select(e.id)
                                globe?.flyToEntry(e, GlobeView.ENTRY_ALTITUDE)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        }
                    }
                }
            }
        }
    }

    // ---- 左下: ズーム・ランダム・全体のボタン列 ----
    Column(
        Modifier.align(Alignment.BottomStart).windowInsetsPadding(WindowInsets.navigationBars).padding(start = 12.dp, bottom = if (selected != null) 8.dp else 40.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimatedVisibility(visible = selected == null, enter = fadeIn(), exit = fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.Start) {
                SmallFloatingActionButton(onClick = { vm.showAbout = true }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Icon(Icons.Default.Info, contentDescription = "このアプリについて")
                }
                SmallFloatingActionButton(
                    onClick = {
                        vm.randomEntry()?.let { e ->
                            vm.select(e.id)
                            globe?.flyToEntry(e, GlobeView.ENTRY_ALTITUDE)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ) { Icon(Icons.Default.Casino, contentDescription = "ランダムに表示") }
                SmallFloatingActionButton(onClick = { globe?.fitWorld() }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Icon(Icons.Default.Public, contentDescription = "地球全体を表示")
                }
                SmallFloatingActionButton(onClick = { globe?.zoomBy(2.0) }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Icon(Icons.Default.Add, contentDescription = "拡大")
                }
                SmallFloatingActionButton(onClick = { globe?.zoomBy(0.5) }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Icon(Icons.Default.Remove, contentDescription = "縮小")
                }
            }
        }
    }

    // ---- 右端: 年代レンジバー(縦) ----
    AnimatedVisibility(
        visible = selected == null,
        modifier = Modifier.align(Alignment.CenterEnd),
        enter = fadeIn(), exit = fadeOut(),
    ) {
        val shown = remember(vm.filter, catalog) { catalog.entries.count { vm.filter.accepts(it) } }
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
            tonalElevation = 3.dp,
            shadowElevation = 4.dp,
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(end = 8.dp)
                .height(360.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
            ) {
                Text("年代", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                YearRangeBar(
                    yearMin = vm.filter.yearMin,
                    yearMax = vm.filter.yearMax,
                    minYear = -3500,
                    maxYear = 2030,
                    modifier = Modifier.weight(1f),
                    onRangeChange = { lo, hi ->
                        vm.filter = vm.filter.copy(yearMin = lo, yearMax = hi)
                    },
                )
                if (vm.filter.yearMin != Int.MIN_VALUE || vm.filter.yearMax != Int.MAX_VALUE) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${"%,d".format(shown)}件",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }

    // ---- 左下(ボタン列の下): 件数と出典 ----
    AnimatedVisibility(
        visible = selected == null,
        modifier = Modifier.align(Alignment.BottomStart),
        enter = fadeIn(), exit = fadeOut(),
    ) {
        // 出典表記は画面最下部の中央寄りに小さく置く(ボタン列と重ならないよう bottom 端)
        Column(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(start = 84.dp, end = 12.dp, bottom = 6.dp),
        ) {
            Text(
                "地図画像: NASA Blue Marble ／ 解説・記事: Wikipedia",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 9.sp,
            )
        }
    }

    // ---- 詳細カード ----
    // 退場アニメーション中も内容を保つため、直近の非 null 用語を覚えておく。
    var lastShown by remember { mutableStateOf<Entry?>(null) }
    if (selected != null) lastShown = selected
    AnimatedVisibility(
        visible = selected != null,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        val e = selected ?: lastShown ?: return@AnimatedVisibility
        DetailCard(
            entry = e,
            catalog = catalog,
            expanded = vm.detailExpanded,
            onToggleExpand = { vm.detailExpanded = !vm.detailExpanded },
            onClose = { vm.clearSelection() },
            onWikipedia = { vm.openWikipedia(e) },
            onYoutube = { vm.openYoutube(e) },
            onZoom = { globe?.flyToEntry(e, 0.012) },
            onShare = { shareEntry(context, e) },
        )
    }
    if (selected != null) {
        BackHandler { vm.clearSelection() }
    }

    // ---- 複数ヒット時の選択 ----
    val candidates = vm.pickCandidates
    if (candidates.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { vm.pickCandidates = emptyList() },
            confirmButton = { TextButton(onClick = { vm.pickCandidates = emptyList() }) { Text("閉じる") } },
            title = { Text("この付近の項目 (${candidates.size})") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(candidates, key = { it.id }) { e ->
                        SearchResultRow(e) {
                            vm.pickCandidates = emptyList()
                            vm.select(e.id)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    }
                }
            },
        )
    }

    if (vm.showFilter) {
        FilterSheet(vm = vm, catalog = catalog, onDismiss = { vm.showFilter = false })
    }
    if (vm.showList) {
        ListScreen(
            catalog = catalog,
            onClose = { vm.showList = false },
            onSelect = { e ->
                vm.showList = false
                vm.select(e.id)
                globe?.flyToEntry(e, GlobeView.ENTRY_ALTITUDE)
            },
        )
    }
    if (vm.showAbout) {
        AboutDialog(catalog = catalog, onDismiss = { vm.showAbout = false })
    }

    // 選択が変わったら地球儀側にも反映(検索/一覧以外からの選択時はカメラを動かさない)
    LaunchedEffect(vm.selectedId) { globe?.setSelected(vm.selectedId) }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onMenu: () -> Unit,
    onFilter: () -> Unit,
    filterActive: Boolean,
    onClear: () -> Unit,
) {
    val focus = LocalFocusManager.current
    Surface(
        Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, contentDescription = "目次") }
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text("世界史の用語を検索(6,000語以上)", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) { Icon(Icons.Default.Close, contentDescription = "クリア") }
            } else {
                Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
            }
            IconButton(onClick = onFilter) {
                Icon(
                    Icons.Default.FilterList,
                    contentDescription = "絞り込み",
                    tint = if (filterActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
fun SearchResultRow(e: Entry, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(e.category.color, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.term, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOf(e.eraLabel(), e.place).filter { it.isNotBlank() }.joinToString(" ・ ")
            if (sub.isNotBlank()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun shareEntry(context: android.content.Context, e: Entry) {
    val text = buildString {
        append(e.term)
        if (e.place.isNotBlank()) append("(${e.place})")
        append("\n")
        if (e.hasWikipedia) append(e.wikipediaDesktopUrl)
    }
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    context.startActivity(android.content.Intent.createChooser(intent, e.term))
}
