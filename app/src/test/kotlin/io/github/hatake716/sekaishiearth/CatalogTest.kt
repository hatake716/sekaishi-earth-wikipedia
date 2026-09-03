package io.github.hatake716.sekaishiearth

import io.github.hatake716.sekaishiearth.data.Catalog
import io.github.hatake716.sekaishiearth.data.Category
import io.github.hatake716.sekaishiearth.data.Entry
import io.github.hatake716.sekaishiearth.globe.MarkerFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {
    private fun e(id: Int, term: String, aliases: List<String> = emptyList(), title: String = term, place: String = "", year: Int? = null, yearEnd: Int? = null, imp: Int = 2, cat: Category = Category.EVENT) =
        Entry(id, term, aliases, title, 0.0, 0.0, place, year, yearEnd, "", cat, imp, "", 0, 0, "", id, "x.html")

    private val catalog = Catalog(
        listOf("1章"), listOf("1節"),
        listOf(
            e(0, "ナポレオン１世", listOf("ナポレオン＝ボナパルト"), "ナポレオン・ボナパルト", "フランス・パリ", 1769, 1821, 3, Category.PERSON),
            e(1, "ナポレオン３世", emptyList(), "ナポレオン3世", "パリ", 1808, 1873, 2, Category.PERSON),
            e(2, "ワーテルローの戦い", emptyList(), "ワーテルローの戦い", "ベルギー", 1815, null, 3),
            e(3, "ヴァスコ＝ダ＝ガマ", listOf("ガマ"), "ヴァスコ・ダ・ガマ", "ポルトガル", 1498),
            e(4, "パリ", emptyList(), "パリ", "フランス", null, null, 3, Category.PLACE),
        ),
    )

    @Test
    fun normalizeUnifiesKanaAndSeparators() {
        assertEquals(Catalog.normalize("なぽれおん"), Catalog.normalize("ナポレオン"))
        assertEquals(Catalog.normalize("ヴァスコ＝ダ＝ガマ"), Catalog.normalize("ヴァスコ・ダ・ガマ"))
        assertEquals(Catalog.normalize("ナポレオン１世"), Catalog.normalize("ナポレオン1世"))
        assertEquals("abc", Catalog.normalize("ＡＢＣ"))
    }

    @Test
    fun searchRanksTermMatchesFirstThenAliasThenTitleThenPlace() {
        val r = catalog.search("なぽれおん")
        assertEquals(listOf(0, 1), r.map { it.id })
        val g = catalog.search("ガマ")
        assertEquals(3, g.first().id)
        val p = catalog.search("パリ")
        assertEquals(4, p.first().id)
        assertTrue(p.map { it.id }.containsAll(listOf(0, 1)))
        assertTrue(catalog.search("存在しない語").isEmpty())
        assertTrue(catalog.search("").isEmpty())
    }

    @Test
    fun searchAcceptsHalfWidthDigitsForFullWidthTerms() {
        assertEquals(1, catalog.search("ナポレオン3世").first().id)
    }

    @Test
    fun markerFilterByYearAndCategory() {
        val f = MarkerFilter(yearMin = 1800, yearMax = 1830)
        assertTrue(f.accepts(catalog.byId(0)!!))
        assertTrue(f.accepts(catalog.byId(2)!!))
        assertTrue(f.accepts(catalog.byId(4)!!))
        assertTrue(!f.accepts(catalog.byId(3)!!))
        val c = MarkerFilter(categories = setOf(Category.PERSON))
        assertTrue(c.accepts(catalog.byId(0)!!))
        assertTrue(!c.accepts(catalog.byId(2)!!))
        val ch = MarkerFilter(chapters = setOf(1))
        assertTrue(!ch.accepts(catalog.byId(0)!!))
    }
}
