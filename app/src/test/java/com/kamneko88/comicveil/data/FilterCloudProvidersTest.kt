package com.kamneko88.comicveil.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterCloudProvidersTest {

    private val selfPackage = "com.kamneko88.comicveil"

    @Test
    fun `excluded authorities are removed`() {
        val candidates = listOf(
            CloudProviderEntry("com.android.externalstorage.documents", "Internal storage", "com.android.externalstorage"),
            CloudProviderEntry("com.android.providers.downloads.documents", "Downloads", "com.android.providers.downloads"),
            CloudProviderEntry("com.android.providers.media.documents", "Media", "com.android.providers.media"),
            CloudProviderEntry("com.google.android.apps.docs.storage", "Google Drive", "com.google.android.apps.docs")
        )

        val result = filterCloudProviders(candidates, selfPackage)

        assertEquals(listOf("Google Drive"), result.map { it.label })
    }

    @Test
    fun `self package is removed`() {
        val candidates = listOf(
            CloudProviderEntry("com.kamneko88.comicveil.documents", "ComicVeil", selfPackage),
            CloudProviderEntry("com.google.android.apps.docs.storage", "Google Drive", "com.google.android.apps.docs")
        )

        val result = filterCloudProviders(candidates, selfPackage)

        assertEquals(listOf("Google Drive"), result.map { it.label })
    }

    @Test
    fun `duplicate authorities are deduplicated to a single entry`() {
        val candidates = listOf(
            CloudProviderEntry("com.google.android.apps.docs.storage", "Google Drive", "com.google.android.apps.docs"),
            CloudProviderEntry("com.google.android.apps.docs.storage", "Google Drive", "com.google.android.apps.docs")
        )

        val result = filterCloudProviders(candidates, selfPackage)

        assertEquals(1, result.size)
    }

    @Test
    fun `remaining candidates are sorted by label ascending`() {
        val candidates = listOf(
            CloudProviderEntry("com.microsoft.skydrive.content", "OneDrive", "com.microsoft.skydrive"),
            CloudProviderEntry("com.dropbox.product.android.dbapp.documentsprovider.DropboxDocumentsProvider", "Dropbox", "com.dropbox.android"),
            CloudProviderEntry("com.google.android.apps.docs.storage", "Google Drive", "com.google.android.apps.docs")
        )

        val result = filterCloudProviders(candidates, selfPackage)

        assertEquals(listOf("Dropbox", "Google Drive", "OneDrive"), result.map { it.label })
    }

    @Test
    fun `empty candidates returns empty list`() {
        val result = filterCloudProviders(emptyList(), selfPackage)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `all candidates excluded returns empty list`() {
        val candidates = listOf(
            CloudProviderEntry("com.android.externalstorage.documents", "Internal storage", "com.android.externalstorage"),
            CloudProviderEntry("com.kamneko88.comicveil.documents", "ComicVeil", selfPackage)
        )

        val result = filterCloudProviders(candidates, selfPackage)

        assertTrue(result.isEmpty())
    }
}
