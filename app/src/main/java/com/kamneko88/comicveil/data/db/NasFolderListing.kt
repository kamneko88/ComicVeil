package com.kamneko88.comicveil.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/**
 * 「一度開いたNASフォルダ」の印。1フォルダ1行。
 *
 * 本棚モードのNASフォルダのタイルに、通信なしで中身の表紙を出すために、
 * フォルダを開いて一覧を取得したときに中身（先頭の一部のコミック）を端末内に控えておく。
 * フォルダ内のコミックが0件でも、この行は残す（「開いたが表紙になるものは無い」を示す）。
 * 行が無いフォルダは「一度も開いていない」ため、従来のフォルダアイコンのままにする。
 */
@Entity(tableName = "nas_folder_listings")
data class NasFolderListing(
    @PrimaryKey
    val folderPath: String
)

/** 保存済みフォルダ一覧の中身1件（フォルダ直下のコミック）。positionは名前の自然順での並び */
@Entity(
    tableName = "nas_folder_listing_comics",
    primaryKeys = ["folderPath", "position"]
)
data class NasFolderListingComic(
    val folderPath: String,
    val position: Int,
    val comicNasPath: String,
    val name: String
)

/**
 * 保存・置き換え・読み出しに使うSQL文とテーブル定義。
 * Migrationと同じCREATE文を単体テスト（JVM上のSQLite）からも流せるよう、ここに集約する。
 */
internal object NasFolderListingSchema {
    val CREATE_STATEMENTS: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS `nas_folder_listings` (
            `folderPath` TEXT NOT NULL,
            PRIMARY KEY(`folderPath`)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `nas_folder_listing_comics` (
            `folderPath`   TEXT NOT NULL,
            `position`     INTEGER NOT NULL,
            `comicNasPath` TEXT NOT NULL,
            `name`         TEXT NOT NULL,
            PRIMARY KEY(`folderPath`, `position`)
        )
        """.trimIndent()
    )
}

@Dao
abstract class NasFolderListingDao {

    @Query("SELECT COUNT(*) FROM nas_folder_listings WHERE folderPath = :folderPath")
    abstract suspend fun countListing(folderPath: String): Int

    @Query("SELECT * FROM nas_folder_listing_comics WHERE folderPath = :folderPath ORDER BY position")
    abstract suspend fun getComics(folderPath: String): List<NasFolderListingComic>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertListing(listing: NasFolderListing)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertComics(comics: List<NasFolderListingComic>)

    @Query("DELETE FROM nas_folder_listings WHERE folderPath = :folderPath")
    protected abstract suspend fun deleteListing(folderPath: String)

    @Query("DELETE FROM nas_folder_listing_comics WHERE folderPath = :folderPath")
    protected abstract suspend fun deleteComics(folderPath: String)

    /** そのフォルダの保存済み一覧を丸ごと置き換える（一覧を取得するたびに呼ぶ） */
    @Transaction
    open suspend fun replaceListing(folderPath: String, comics: List<NasFolderListingComic>) {
        deleteComics(folderPath)
        deleteListing(folderPath)
        insertListing(NasFolderListing(folderPath))
        if (comics.isNotEmpty()) insertComics(comics)
    }

    /** 保存済み一覧。一度も開いていないフォルダはnull、開いたがコミックが無いフォルダは空リスト */
    suspend fun getListing(folderPath: String): List<NasFolderListingComic>? =
        if (countListing(folderPath) == 0) null else getComics(folderPath)
}
