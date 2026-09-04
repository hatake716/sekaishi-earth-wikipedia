package io.github.hatake716.sekaishiearth.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.userDataStore: DataStore<Preferences> by preferencesDataStore(name = "user_data")

/**
 * 既読・ブックマークの永続化(端末内、DataStore Preferences)。
 * - 既読: 一度でも詳細を開いた用語の id 集合。
 * - ブックマーク: 追加順の id リスト(最大 [MAX_BOOKMARKS] 件)。
 * どちらもカンマ区切り文字列で保存する(数千件でも数十 KB で収まる)。
 */
class UserDataRepository(private val context: Context) {

    private val readKey = stringPreferencesKey("read_ids")
    private val bookmarkKey = stringPreferencesKey("bookmark_ids")

    val readIds: Flow<Set<Int>> = context.userDataStore.data.map { prefs ->
        parseIds(prefs[readKey]).toSet()
    }

    /** ブックマークは追加順(新しいものが末尾)。 */
    val bookmarkIds: Flow<List<Int>> = context.userDataStore.data.map { prefs ->
        parseIds(prefs[bookmarkKey])
    }

    suspend fun markRead(id: Int) {
        context.userDataStore.edit { prefs ->
            val cur = parseIds(prefs[readKey]).toMutableSet()
            if (cur.add(id)) prefs[readKey] = cur.joinToString(",")
        }
    }

    suspend fun toggleBookmark(id: Int): Boolean {
        var added = false
        context.userDataStore.edit { prefs ->
            val cur = parseIds(prefs[bookmarkKey]).toMutableList()
            if (cur.contains(id)) {
                cur.remove(id)
                added = false
            } else {
                if (cur.size >= MAX_BOOKMARKS) cur.removeAt(0) // 上限超過時は最古を削除
                cur.add(id)
                added = true
            }
            prefs[bookmarkKey] = cur.joinToString(",")
        }
        return added
    }

    suspend fun removeBookmark(id: Int) {
        context.userDataStore.edit { prefs ->
            val cur = parseIds(prefs[bookmarkKey]).toMutableList()
            if (cur.remove(id)) prefs[bookmarkKey] = cur.joinToString(",")
        }
    }

    private fun parseIds(s: String?): List<Int> {
        if (s.isNullOrEmpty()) return emptyList()
        return s.split(',').mapNotNull { it.trim().toIntOrNull() }
    }

    companion object {
        const val MAX_BOOKMARKS = 1000
    }
}
