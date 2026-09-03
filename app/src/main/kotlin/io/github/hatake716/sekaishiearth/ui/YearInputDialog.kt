package io.github.hatake716.sekaishiearth.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 年代の数値入力ダイアログ。
 * @param upper true なら上端(新しい年=yearMax)、false なら下端(古い年=yearMin)を編集する。
 * 「紀元前」チェックで負数として扱う。空欄で決定すると「端まで(制限なし)」になる。
 */
@Composable
fun YearInputDialog(
    upper: Boolean,
    currentYearMin: Int,
    currentYearMax: Int,
    minYear: Int,
    maxYear: Int,
    onDismiss: () -> Unit,
    onConfirm: (yearMin: Int, yearMax: Int) -> Unit,
) {
    val target = if (upper) currentYearMax else currentYearMin
    val unlimited = if (upper) currentYearMax == Int.MAX_VALUE else currentYearMin == Int.MIN_VALUE
    var bc by remember { mutableStateOf(!unlimited && target < 0) }
    var textValue by remember { mutableStateOf(if (unlimited) "" else abs(target).toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (upper) "新しい方の年" else "古い方の年") },
        text = {
            Column {
                Text(
                    "西暦を数字で入力してください。空欄にすると" +
                        (if (upper) "「現在まで」" else "「最古まで」") + "になります。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = bc,
                        onClick = { bc = !bc },
                        label = { Text("紀元前") },
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = textValue,
                        onValueChange = { s -> textValue = s.filter { it.isDigit() }.take(5) },
                        singleLine = true,
                        placeholder = { Text(if (upper) "現在" else "最古") },
                        suffix = { Text("年") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(150.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val n = textValue.toIntOrNull()
                val year = when {
                    n == null -> if (upper) Int.MAX_VALUE else Int.MIN_VALUE
                    bc -> -n
                    else -> n
                }
                var lo = currentYearMin
                var hi = currentYearMax
                if (upper) {
                    hi = if (year == Int.MAX_VALUE) Int.MAX_VALUE else year.coerceIn(minYear, maxYear)
                    if (hi != Int.MAX_VALUE && lo != Int.MIN_VALUE && hi < lo) lo = hi
                } else {
                    lo = if (year == Int.MIN_VALUE) Int.MIN_VALUE else year.coerceIn(minYear, maxYear)
                    if (lo != Int.MIN_VALUE && hi != Int.MAX_VALUE && lo > hi) hi = lo
                }
                onConfirm(lo, hi)
                onDismiss()
            }) { Text("決定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}
