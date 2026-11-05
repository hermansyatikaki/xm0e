package miku.moe.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cache progress baca chapter untuk daftar chapter Detail Manga (v1-v4).
 *
 * Sebelumnya setiap baris chapter memanggil MangaHistoryManager.getProgress() langsung saat komposisi
 * (di main thread): query Room + baca seluruh riwayat + parse JSON untuk SETIAP baris yang muncul saat
 * di-scroll, sehingga gulir patah-patah. Sekarang progress satu manga dimuat SEKALI di background lewat
 * [refresh], dan baris cukup membaca peta di memori lewat [progress].
 */
internal object MangaProgressCache {
    private class Snapshot(val key: String, val map: Map<Int, MangaHistoryManager.Progress>)

    private var snapshot by mutableStateOf(Snapshot("", emptyMap()))
    private var revisionState by mutableIntStateOf(0)

    /** Berubah setiap kali cache dimuat ulang; dipakai sebagai key remember untuk filter berbasis progress. */
    val revision: Int get() = revisionState

    private fun keyOf(manga: MangaPost): String = manga.getSourceId() + ":" + (manga.slug ?: "").trim()

    fun progress(manga: MangaPost, chapterIndex: Float): MangaHistoryManager.Progress? {
        val current = snapshot
        if (current.key != keyOf(manga)) return null
        return current.map[MangaHistoryManager.progressIndexKey(chapterIndex)]
    }

    suspend fun refresh(context: Context, manga: MangaPost) {
        val key = keyOf(manga)
        val appContext = context.applicationContext
        val map = withContext(Dispatchers.IO) { MangaHistoryManager.getProgressMap(appContext, manga) }
        snapshot = Snapshot(key, map)
        revisionState = revisionState + 1
    }
}
