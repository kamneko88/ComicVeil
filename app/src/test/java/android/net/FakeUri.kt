package android.net

/**
 * ユニットテスト用の最小限のUri実装。
 *
 * android.net.Uriの実コンストラクタはpackage-privateなため、テスト側から
 * サブクラス化するにはこのファイルをandroid.netパッケージに置く必要がある。
 * Mockito等の追加依存を避けるため、抽象メソッドをダミー実装するだけにしている
 * （テスト対象のコードはuriのnull/非nullしか見ないため、各メソッドの戻り値自体は使われない）。
 */
internal class FakeUri(private val value: String) : Uri() {
    override fun buildUpon(): Builder = throw UnsupportedOperationException()
    override fun getAuthority(): String? = null
    override fun getEncodedAuthority(): String? = null
    override fun getEncodedFragment(): String? = null
    override fun getEncodedPath(): String? = null
    override fun getEncodedQuery(): String? = null
    override fun getEncodedSchemeSpecificPart(): String? = null
    override fun getEncodedUserInfo(): String? = null
    override fun getFragment(): String? = null
    override fun getHost(): String? = null
    override fun getLastPathSegment(): String? = null
    override fun getPath(): String? = null
    override fun getPathSegments(): List<String> = emptyList()
    override fun getPort(): Int = -1
    override fun getQuery(): String? = null
    override fun getScheme(): String? = null
    override fun getSchemeSpecificPart(): String? = null
    override fun getUserInfo(): String? = null
    override fun isHierarchical(): Boolean = false
    override fun isRelative(): Boolean = false
    override fun toString(): String = value
    override fun describeContents(): Int = 0
    override fun writeToParcel(dest: android.os.Parcel, flags: Int) {}
}
