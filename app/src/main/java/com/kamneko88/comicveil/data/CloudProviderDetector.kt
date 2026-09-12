package com.kamneko88.comicveil.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CloudProviderEntry(
    val authority: String,
    val label: String,
    val packageName: String
)

// 端末ローカルの標準プロバイダ（内部ストレージ／ダウンロード／メディア）は
// 既存の「ダウンロードフォルダ」ショートカットと役割が重複するため一覧から除外する。
private val EXCLUDED_AUTHORITIES = setOf(
    "com.android.externalstorage.documents",
    "com.android.providers.downloads.documents",
    "com.android.providers.media.documents"
)

/**
 * 検出済みプロバイダ候補から、除外対象authority・自アプリ自身・重複authorityを除き、
 * 表示名（label）の昇順でソートする。PackageManager等のcontext依存処理は含まないため
 * ここだけが単体テスト対象。
 */
internal fun filterCloudProviders(
    candidates: List<CloudProviderEntry>,
    selfPackageName: String
): List<CloudProviderEntry> =
    candidates
        .filter { it.authority !in EXCLUDED_AUTHORITIES && it.packageName != selfPackageName }
        .distinctBy { it.authority }
        .sortedBy { it.label }

/**
 * インストール済みのSAF対応（DocumentsProvider）アプリを検出する。
 * PackageManagerに依存するため単体テスト対象外（filterCloudProvidersに委譲した部分のみテストする）。
 */
fun detectInstalledCloudProviders(context: Context): List<CloudProviderEntry> {
    val pm = context.packageManager
    val intent = Intent("android.content.action.DOCUMENTS_PROVIDER")
    val candidates = pm.queryIntentContentProviders(intent, 0).mapNotNull { resolveInfo ->
        val info = resolveInfo.providerInfo ?: return@mapNotNull null
        val authority = info.authority ?: return@mapNotNull null
        val label = runCatching { info.loadLabel(pm)?.toString() }.getOrNull() ?: return@mapNotNull null
        CloudProviderEntry(authority = authority, label = label, packageName = info.packageName)
    }
    return filterCloudProviders(candidates, context.packageName)
}

/**
 * 指定authorityのルートドキュメントURIをベストエフォートで解決する。
 * クエリに失敗した場合・ルートが取得できない場合はnullを返す（呼び出し側は
 * EXTRA_INITIAL_URIを付けずに通常のピッカーを開く＝現状と同じ動作にフォールバックすること）。
 */
suspend fun resolveInitialRootUri(context: Context, authority: String): Uri? =
    withContext(Dispatchers.IO) {
        runCatching {
            val rootsUri = DocumentsContract.buildRootsUri(authority)
            context.contentResolver.query(rootsUri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val docIdIndex = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_DOCUMENT_ID)
                val documentId = if (docIdIndex >= 0) cursor.getString(docIdIndex) else null
                documentId?.let { DocumentsContract.buildDocumentUri(authority, it) }
            }
        }.getOrNull()
    }
