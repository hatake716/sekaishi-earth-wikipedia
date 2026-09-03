package io.github.hatake716.sekaishiearth.data

import androidx.compose.ui.graphics.Color

/** ピンの分類。色は地球儀上のマーカー色と詳細シートのチップで共通。 */
enum class Category(val label: String, val color: Color) {
    EVENT("出来事", Color(0xFFFF6B4A)),
    PERSON("人物", Color(0xFFFFD54F)),
    POLITY("国家・王朝", Color(0xFFB388FF)),
    PLACE("都市・地名", Color(0xFF4DD0E1)),
    CULTURE("文化・宗教", Color(0xFF81C784)),
    CONCEPT("制度・概念", Color(0xFFB0BEC5));

    companion object {
        fun fromCode(code: Int): Category = entries.getOrElse(code) { CONCEPT }
    }
}

/**
 * 世界史の用語 1 件。用語名(見出し)以外の内容 — 解説文・座標・年代・分類 — は
 * すべて Wikipedia / Wikidata の情報から生成している。
 * 年は西暦(紀元前は負数、0 は使わない)。
 */
data class Entry(
    val id: Int,
    val term: String,
    val aliases: List<String>,
    val wikiTitle: String,
    val lat: Double,
    val lon: Double,
    val place: String,
    val year: Int?,
    val yearEnd: Int?,
    val era: String,
    val category: Category,
    val importance: Int,
    /** Wikipedia の記事冒頭を要約した一文。出典は Wikipedia。 */
    val desc: String,
    /** 時代の区分(古代・中世・近世・近代・現代など)。年代からの機械分類で、絞り込みに使う。 */
    val periodIndex: Int,
    /** 地域(東アジア・ヨーロッパ・西アジアなど)。座標からの機械分類で、絞り込みに使う。 */
    val regionIndex: Int,
    /** wikiTitle が用語そのものの記事なら true、関連記事で代用しているなら false。 */
    val exactTitle: Boolean = true,
) {
    private fun encodedTitle(): String =
        java.net.URLEncoder.encode(wikiTitle.replace(' ', '_'), "UTF-8").replace("+", "%20")

    /** モバイル版 Wikipedia(アプリ内 WebView 用)。 */
    val wikipediaUrl: String get() = "https://ja.m.wikipedia.org/wiki/" + encodedTitle()

    /** デスクトップ版 Wikipedia(共有用)。 */
    val wikipediaDesktopUrl: String get() = "https://ja.wikipedia.org/wiki/" + encodedTitle()

    /** この用語に関連する YouTube 動画の検索結果(アプリ内 WebView で開く)。 */
    val youtubeSearchUrl: String
        get() {
            val q = java.net.URLEncoder.encode("$term 世界史 歴史 解説", "UTF-8")
            return "https://m.youtube.com/results?search_query=$q"
        }

    val hasWikipedia: Boolean get() = wikiTitle.isNotBlank()
}

/** 年の表示用文字列(前500年 / 1815年)。 */
fun formatYear(year: Int): String = if (year < 0) "前${-year}年" else "${year}年"

fun Entry.eraLabel(): String {
    if (era.isNotBlank()) return era
    val y = year ?: return ""
    val e = yearEnd
    return if (e != null && e != y) "${formatYear(y)}〜${formatYear(e)}" else formatYear(y)
}
