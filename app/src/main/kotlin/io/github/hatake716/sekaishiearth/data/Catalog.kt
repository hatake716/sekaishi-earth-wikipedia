package io.github.hatake716.sekaishiearth.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import java.io.InputStreamReader
import java.text.Normalizer

/**
 * assets/entries.json を読み込んだ用語カタログ。
 *
 * JSON 形式(サイズ削減のため配列):
 * {
 *   "chapters": ["序章 先史の世界", ...],
 *   "sections": ["1節 古代オリエント世界", ...],
 *   "entries": [[id, term, "alias1|alias2", wikiTitle, lat, lon, place, year, yearEnd, era, cat, importance, desc, chapterIdx, sectionIdx, sub, order, href, exactTitle(0/1)], ...]
 * }
 */
class Catalog(
    val chapters: List<String>,
    val sections: List<String>,
    val entries: List<Entry>,
) {
    private val byId: Map<Int, Entry> = entries.associateBy { it.id }

    /** 検索キー: 用語 / 別名 / Wikipedia 題名 / 地名 を SEP 区切りで連結し正規化したもの。 */
    private val searchKeys: List<String> = entries.map { e ->
        buildString {
            append(normalize(e.term)); append(SEP)
            e.aliases.forEach { append(normalize(it)); append(SEP) }
            append(normalize(e.wikiTitle)); append(SEP)
            append(normalize(e.place))
        }
    }

    val minYear: Int = entries.mapNotNull { it.year }.minOrNull() ?: -3500
    val maxYear: Int = entries.mapNotNull { it.yearEnd ?: it.year }.maxOrNull() ?: 2030

    fun byId(id: Int): Entry? = byId[id]

    /** 部分一致検索。用語→別名→Wikipedia 題名→地名の順で一致した位置と重要度で並べる。 */
    fun search(query: String, limit: Int = 80): List<Entry> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val scored = ArrayList<Pair<Int, Entry>>()
        for (i in entries.indices) {
            val key = searchKeys[i]
            val pos = key.indexOf(q)
            if (pos < 0) continue
            val e = entries[i]
            var field = 0
            for (k in 0 until pos) if (key[k] == SEP) field++
            val atStart = pos == 0 || key[pos - 1] == SEP
            val score = field * 10 + (if (atStart) 0 else 5) + (3 - e.importance)
            scored.add(score to e)
        }
        return scored.sortedWith(compareBy({ it.first }, { it.second.term.length }, { it.second.order }))
            .map { it.second }.take(limit)
    }

    companion object {
        private const val SEP = '\u0001'

        fun load(context: Context): Catalog {
            context.assets.open("entries.json").use { input ->
                val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8))
                var chapters: List<String> = emptyList()
                var sections: List<String> = emptyList()
                val entries = ArrayList<Entry>(7000)
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "chapters" -> chapters = readStringArray(reader)
                        "sections" -> sections = readStringArray(reader)
                        "entries" -> {
                            reader.beginArray()
                            while (reader.hasNext()) entries.add(readEntry(reader))
                            reader.endArray()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                return Catalog(chapters, sections, entries)
            }
        }

        private fun readStringArray(reader: JsonReader): List<String> {
            val out = ArrayList<String>()
            reader.beginArray()
            while (reader.hasNext()) out.add(reader.nextString())
            reader.endArray()
            return out
        }

        private fun readEntry(reader: JsonReader): Entry {
            reader.beginArray()
            val id = reader.nextInt()
            val term = reader.nextString()
            val aliases = reader.nextString().split('|').filter { it.isNotBlank() }
            val wikiTitle = reader.nextString()
            val lat = reader.nextDouble()
            val lon = reader.nextDouble()
            val place = reader.nextString()
            val year = readNullableInt(reader)
            val yearEnd = readNullableInt(reader)
            val era = reader.nextString()
            val cat = Category.fromCode(reader.nextInt())
            val importance = reader.nextInt()
            val desc = reader.nextString()
            val chapterIdx = reader.nextInt()
            val sectionIdx = reader.nextInt()
            val sub = reader.nextString()
            val order = reader.nextInt()
            val href = reader.nextString()
            // 末尾の任意フィールド: titleMatch (1=用語そのものの記事, 0=関連記事)
            var exact = true
            while (reader.hasNext()) {
                if (reader.peek() == JsonToken.NUMBER) exact = reader.nextInt() != 0 else reader.skipValue()
            }
            reader.endArray()
            return Entry(
                id, term, aliases, wikiTitle, lat, lon, place, year, yearEnd, era, cat, importance, desc,
                chapterIdx, sectionIdx, sub, order, href, exact,
            )
        }

        private fun readNullableInt(reader: JsonReader): Int? =
            if (reader.peek() == JsonToken.NULL) { reader.nextNull(); null } else reader.nextInt()

        /** 検索用正規化: NFKC、小文字、ひらがな→カタカナ、区切り記号除去。 */
        fun normalize(s: String): String {
            val n = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
            val sb = StringBuilder(n.length)
            for (ch in n) {
                val c = if (ch in 'ぁ'..'ゖ') (ch + 0x60) else ch
                when (c) {
                    ' ', '　', '=', '・', '＝', '･', '-', '－', '、', '，', ',', '.', '。',
                    '「', '」', '（', '）', '(', ')', '/', '／', '"', '\'' -> Unit
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }
    }
}
