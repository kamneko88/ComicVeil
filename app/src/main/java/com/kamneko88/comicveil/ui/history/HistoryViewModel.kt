package com.kamneko88.comicveil.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kamneko88.comicveil.data.LocalFileRepository
import com.kamneko88.comicveil.data.db.ComicVeilDatabase
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** 履歴1件分の表示用データ */
data class HistoryItem(
    /** ビューワーへ渡すキー（巻フォルダを含む場合はマーカー付き） */
    val key: String,
    /** 表示用タイトル */
    val title: String,
    /** 巻フォルダ名（内包フォルダの場合のみ） */
    val volumeName: String?,
    val currentPage: Int,
    val totalPages: Int,
    val lastReadAt: Long,
    /** 元のファイルがまだ存在するか（キャッシュ削除などで消えている場合がある） */
    val exists: Boolean
) {
    /** 読み進めた割合（0.0〜1.0） */
    val progress: Float
        get() = if (totalPages > 0) (currentPage + 1).toFloat() / totalPages else 0f
}

/**
 * 閲覧履歴の画面用ViewModel。
 *
 * 履歴は専用テーブルを持たず、読書位置（reading_progress）の「最終閲覧日時」を
 * 新しい順に並べたものをそのまま使う。
 */
class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val dao          = ComicVeilDatabase.getDatabase(application).readingProgressDao()
    private val fileTitleDao = ComicVeilDatabase.getDatabase(application).fileTitleDao()
    private val fileRepo     = LocalFileRepository()

    /** キャッシュパス → 元のファイル名（リモートの本用） */
    private val originalNames = MutableStateFlow<Map<String, String>>(emptyMap())

    init {
        viewModelScope.launch {
            originalNames.value = runCatching {
                fileTitleDao.getAll().associate { it.filePath to it.originalName }
            }.getOrDefault(emptyMap())
        }
    }

    /** 履歴からビューワーを開くためのイベント */
    val navigateEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)

    val history = combine(dao.observeHistory(), originalNames) { list, names ->
        list.map { progress ->
            // ビューワーのキーは「アーカイブパス##vol##巻フォルダ名」の形式のことがある
            val parts      = progress.filePath.split(VOLUME_MARKER)
            val archive    = parts[0]
            val volumeName = parts.getOrNull(1)

            // リモートの本はキャッシュ上で機械的な名前になっているので、
            // 控えておいた元の名前を優先する
            val fileName   = names[archive] ?: File(archive).name
            val (title, _) = fileRepo.parseFileName(fileName)

            HistoryItem(
                key         = progress.filePath,
                title       = title,
                volumeName  = volumeName,
                currentPage = progress.currentPage,
                totalPages  = progress.totalPages,
                lastReadAt  = progress.lastReadAt,
                exists      = File(archive).exists()
            )
        }
    }
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    /** 履歴をタップ：その本をビューワーで開く */
    fun openHistory(item: HistoryItem) {
        if (!item.exists) return
        navigateEvent.tryEmit(item.key)
    }

    /** 履歴を1件削除する（読書位置も消えるので、次に開くと最初からになる） */
    fun deleteHistory(item: HistoryItem) {
        viewModelScope.launch { dao.deleteProgress(item.key) }
    }

    /** 履歴をすべて削除する */
    fun clearHistory() {
        viewModelScope.launch { dao.clearHistory() }
    }

    companion object {
        /** ViewerViewModelと同じ、アーカイブパスと巻フォルダ名を区切るマーカー */
        private const val VOLUME_MARKER = "##vol##"
    }
}
