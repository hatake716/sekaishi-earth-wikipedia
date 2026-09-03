package io.github.hatake716.sekaishiearth.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.hatake716.sekaishiearth.BuildConfig
import io.github.hatake716.sekaishiearth.data.Catalog

@Composable
fun AboutDialog(catalog: Catalog, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
        title = { Text("地球儀で見る世界史wikipedia") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("バージョン ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Text(
                    "世界史の窓の用語集(${"%,d".format(catalog.entries.size)} 語)を、出来事や人物にゆかりのある場所へピンとして地球儀に配置しました。" +
                        "ピンをタップすると概要が表示され、Wikipedia の記事と世界史の窓の解説をアプリ内で読めます。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Text("操作", fontWeight = FontWeight.Bold)
                Text("ドラッグ: 回転 ／ ピンチ: 拡大縮小 ／ ダブルタップ: 拡大 ／ ピンをタップ: 詳細", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Text("データと出典", fontWeight = FontWeight.Bold)
                Text(
                    "・用語一覧: 世界史の窓 (y-history.net) の「アイウエオ順リスト」に掲載された用語。各用語の解説は同サイトへリンクしています。\n" +
                        "・記事: Wikipedia 日本語版へアプリ内ブラウザでアクセスします(記事本文は CC BY-SA 4.0)。\n" +
                        "・地球画像: NASA Blue Marble: Next Generation (NASA Earth Observatory, Reto Stöckli 他)。パブリックドメイン。\n" +
                        "・座標・年代・分類・要約: 各用語の主題に対応する場所を独自に整理したものです。広い範囲に関わる用語(帝国・思想など)は代表的な場所に置いています。誤りにお気づきの場合は GitHub の Issue でお知らせください。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                Text("ライセンス", fontWeight = FontWeight.Bold)
                Text("アプリ本体: MIT License\nソースコード: github.com/hatake716/sekaishi-earth-wikipedia", style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}
