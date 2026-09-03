package io.github.hatake716.sekaishiearth.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.hatake716.sekaishiearth.data.Catalog
import io.github.hatake716.sekaishiearth.data.Category
import io.github.hatake716.sekaishiearth.data.Entry
import io.github.hatake716.sekaishiearth.globe.MarkerFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 画面全体の状態。回転などで Activity が再生成されても保持される。 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    var catalog by mutableStateOf<Catalog?>(null)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set

    var selectedId by mutableStateOf(-1)
    var query by mutableStateOf("")
    var searchResults by mutableStateOf<List<Entry>>(emptyList())
        private set
    var filter by mutableStateOf(MarkerFilter())
    var pickCandidates by mutableStateOf<List<Entry>>(emptyList())
    var webUrl by mutableStateOf<String?>(null)
    var webTitle by mutableStateOf("")
    var showFilter by mutableStateOf(false)
    var showList by mutableStateOf(false)
    var showAbout by mutableStateOf(false)
    var detailExpanded by mutableStateOf(false)

    /** カメラの復元用(度、高度)。 */
    var savedLat = 35.0
    var savedLon = 135.0
    var savedAlt = 0.0
    var cameraRestored = false

    private val prefs = app.getSharedPreferences("globe", Context.MODE_PRIVATE)

    init {
        savedLat = prefs.getFloat("lat", 35f).toDouble()
        savedLon = prefs.getFloat("lon", 135f).toDouble()
        savedAlt = prefs.getFloat("alt", 0f).toDouble()
        viewModelScope.launch {
            try {
                val c = withContext(Dispatchers.IO) { Catalog.load(getApplication()) }
                catalog = c
            } catch (e: Exception) {
                loadError = e.toString()
            }
        }
    }

    val selectedEntry: Entry? get() = catalog?.byId(selectedId)

    fun select(id: Int) {
        selectedId = id
        detailExpanded = false
    }

    fun clearSelection() {
        selectedId = -1
        detailExpanded = false
    }

    fun updateQuery(q: String) {
        query = q
        val c = catalog
        searchResults = if (c == null || q.isBlank()) emptyList() else c.search(q)
    }

    fun saveCamera(latDeg: Double, lonDeg: Double, alt: Double) {
        savedLat = latDeg; savedLon = lonDeg; savedAlt = alt
        prefs.edit().putFloat("lat", latDeg.toFloat()).putFloat("lon", lonDeg.toFloat()).putFloat("alt", alt.toFloat()).apply()
    }

    fun openWikipedia(e: Entry) {
        webTitle = e.term
        webUrl = e.wikipediaUrl
    }

    fun openSource(e: Entry) {
        webTitle = "世界史の窓: ${e.term}"
        webUrl = e.sourceUrl
    }

    fun closeWeb() {
        webUrl = null
    }

    fun randomEntry(): Entry? {
        val c = catalog ?: return null
        val f = filter
        val pool = c.entries.filter { f.accepts(it) }
        if (pool.isEmpty()) return null
        return pool.random()
    }

    fun resetFilter() {
        filter = MarkerFilter()
    }

    fun isFilterActive(): Boolean {
        val f = filter
        return f.yearMin != Int.MIN_VALUE || f.yearMax != Int.MAX_VALUE || f.categories.size != Category.entries.size || f.chapters != null
    }
}
