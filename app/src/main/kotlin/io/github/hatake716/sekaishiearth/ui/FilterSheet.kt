package io.github.hatake716.sekaishiearth.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.hatake716.sekaishiearth.data.Catalog
import io.github.hatake716.sekaishiearth.data.Category
import kotlinx.coroutines.launch

/** 未選択のまま閉じようとしたときに出すメッセージ。 */
const val FILTER_NOTHING_SELECTED_MESSAGE = "何も選択されていません。最低1つは選んでください。"

/**
 * 分類・地域で表示するピンを絞り込む(年代は画面右の縦バー)。
 * チェックした項目だけを表示する。起動直後に開くとチップはすべて未選択で、見たい項目だけチェックする形。
 * 分類・地域とも何もチェックしていない状態ではシートを閉じられない(「何も選択されていません」を出して戻す)。
 * 片方の欄だけ空の場合は、その欄は絞らない(例: 分類だけ「人物」なら全地域の人物)。
 *
 * 戻る操作はシート側で自前処理する(shouldDismissOnBackPress=false + BackHandler)。
 * Material3 に任せると、予測型戻る(Android 16 以降は既定で有効)のジェスチャーで縮小した内部状態が
 * hide 後も残り、show() で戻したシートが縮んだまま表示されるため。未選択時はシートを動かさずトーストだけ出す。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(vm: MainViewModel, catalog: Catalog, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val f = vm.filter
    val nothingChecked = f.categories.isEmpty() && f.regions.isEmpty()

    // 最新の状態で判定する(ラムダ生成時に掴んだ f ではなく vm.filter を読む)
    fun currentlyNothingChecked(): Boolean {
        val cur = vm.filter
        return cur.categories.isEmpty() && cur.regions.isEmpty()
    }
    // 連打で Toast がキューに溜まり、閉じた後も残らないよう、直前のものを消してから出す
    val lastToast = remember { arrayOfNulls<Toast>(1) }
    fun notifyNothingSelected() {
        lastToast[0]?.cancel()
        lastToast[0] = Toast.makeText(context, FILTER_NOTHING_SELECTED_MESSAGE, Toast.LENGTH_SHORT).also { it.show() }
    }
    fun close() {
        lastToast[0]?.cancel()
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = {
            // スワイプ下げ・外側タップはここに来る(シートは既に隠れている)。未選択なら戻す。
            if (currentlyNothingChecked()) {
                notifyNothingSelected()
                scope.launch { state.show() }
            } else {
                close()
            }
        },
        sheetState = state,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
    ) {
        // 戻るキー/ジェスチャー。未選択ならシートを動かさずメッセージだけ、選択済みなら閉じる。
        BackHandler {
            if (currentlyNothingChecked()) {
                notifyNothingSelected()
            } else {
                scope.launch { state.hide() }.invokeOnCompletion { if (!state.isVisible) close() }
            }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("絞り込み", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (nothingChecked) {
                        Text("何も選択されていません", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    } else {
                        val shown = remember(f, catalog) { catalog.entries.count { f.accepts(it) } }
                        Text("${"%,d".format(shown)} 件を表示中", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // 全選択の下に全解除。どちらも年代には触らない。
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { vm.selectAllFilter() }) { Text("全選択") }
                    TextButton(onClick = { vm.deselectAllFilter() }) { Text("全解除") }
                }
            }
            Text(
                if (nothingChecked) "見たい項目にチェックを入れてください。最低1つ選ばないと閉じられません(すべて表示するなら「全選択」)。"
                else "チェックした項目だけを表示しています(何も選んでいない欄はすべて表示)。",
                style = MaterialTheme.typography.labelSmall,
                color = if (nothingChecked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("年代は画面右のバーで絞り込めます。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))

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
                    val on = i in f.regions
                    FilterChip(
                        selected = on,
                        onClick = {
                            val set = f.regions.toMutableSet()
                            if (on) set.remove(i) else set.add(i)
                            vm.filter = f.copy(regions = set)
                        },
                        label = { Text(rg, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        }
    }
}
