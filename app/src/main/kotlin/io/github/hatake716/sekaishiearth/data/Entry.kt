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
 * 世界史の窓の用語 1 件。座標は Wikipedia 記事の主題が起きた／存在した場所。
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
    val desc: String,
    val chapterIndex: Int,
    val sectionIndex: Int,
    val sub: String,
    val order: Int,
    val href: String,
    /** wikiTitle が用語そのものの記事なら true、関連記事で代用しているなら false。 */
    val exactTitle: Boolean = true,
) {
    private fun encodedTitle(): String =
        java.net.URLEncoder.encode(wikiTitle.replace(' ', '_'), "UTF-8").replace("+", "%20")

    /** モバイル版 Wikipedia(アプリ内 WebView 用)。 */
    val wikipediaUrl: String get() = "https://ja.m.wikipedia.org/wiki/" + encodedTitle()

    /** デスクトップ版 Wikipedia(共有用)。 */
    val wikipediaDesktopUrl: String get() = "https://ja.wikipedia.org/wiki/" + encodedTitle()

    /** 世界史の窓の元ページ。 */
    val sourceUrl: String get() = "https://www.y-history.net/appendix/$href"

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
