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
import io.github.hatake716.sekaishiearth.data.UserDataRepository
import io.github.hatake716.sekaishiearth.globe.MarkerFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
    var showBookmarks by mutableStateOf(false)
    var showAbout by mutableStateOf(false)

    /** 既読(一度でも詳細を開いた)用語の id 集合。 */
    var readIds by mutableStateOf<Set<Int>>(emptySet())
        private set

    /** ブックマークした用語の id リスト(追加順)。 */
    var bookmarkIds by mutableStateOf<List<Int>>(emptyList())
        private set

    private val userData = UserDataRepository(app)

    /** 年代の数値入力ダイアログ。null=非表示、true=上端(新しい年)編集、false=下端(古い年)編集。 */
    var editYearUpper by mutableStateOf<Boolean?>(null)
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
        // 既読・ブックマークを DataStore から監視
        userData.readIds.onEach { readIds = it }.launchIn(viewModelScope)
        userData.bookmarkIds.onEach { bookmarkIds = it }.launchIn(viewModelScope)
    }

    fun isRead(id: Int): Boolean = id in readIds
    fun isBookmarked(id: Int): Boolean = id in bookmarkIds

    fun toggleBookmark(id: Int) {
        viewModelScope.launch { userData.toggleBookmark(id) }
    }

    fun removeBookmark(id: Int) {
        viewModelScope.launch { userData.removeBookmark(id) }
    }

    val selectedEntry: Entry? get() = catalog?.byId(selectedId)

    fun select(id: Int) {
        selectedId = id
        detailExpanded = false
        // 詳細を開いた(地球儀・一覧・検索いずれからでも)時点で既読にする
        if (id >= 0 && id !in readIds) viewModelScope.launch { userData.markRead(id) }
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

    fun openYoutube(e: Entry) {
        webTitle = "YouTube: ${e.term}"
        webUrl = e.youtubeSearchUrl
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

    /** 絞り込みシートの「全選択」。分類・地域を全項目チェック状態にする(年代はこのシートの対象外なので触らない)。 */
    fun selectAllFilter() {
        val regionCount = catalog?.regions?.size ?: 0
        filter = filter.copy(categories = Category.entries.toSet(), regions = (0 until regionCount).toSet())
    }

    /** 絞り込みシートの「全解除」。分類・地域のチェックを全部外す＝絞り込みなし(年代は触らない)。 */
    fun deselectAllFilter() {
        filter = filter.copy(categories = emptySet(), regions = emptySet())
    }

    fun isFilterActive(): Boolean {
        val f = filter
        return f.yearFilterActive() || f.categoryFilterActive() || f.regionFilterActive(catalog?.regions?.size ?: 0)
    }
}
