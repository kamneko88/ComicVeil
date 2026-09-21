# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# =====================================================================
# T-018（R8導入）: 2026-09-20
# 圧縮・SMB系ライブラリはリフレクション/JNI/SLF4Jのサービスロードに依存する
# 箇所があり、R8で「ビルドは通るが特定の入力だけ落ちる」形で壊れやすいため、
# 初回導入は縮小効果より安全側（パッケージ丸ごとkeep）を優先する。
# 公式のR8向けconsumer-rules.pro/proguard-rules.proを配布していないライブラリ
# が多く、個別の未使用シンボル洗い出しは今回は行わない。
# =====================================================================

# --- RAR4展開（junrar） ---
-keep class com.github.junrar.** { *; }
-dontwarn com.github.junrar.**

# --- ZIP/7z展開（commons-compress）＋7z LZMA/XZ（tukaani xz） ---
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**
-keep class org.tukaani.xz.** { *; }
-dontwarn org.tukaani.xz.**
# commons-compress が実行時にクラス存在チェックで参照するが、
# ComicVeilには同梱していない任意コーデック（未使用のためビルドに含まれない）
-dontwarn org.brotli.dec.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.apache.commons.compress.harmony.**

# --- パスワード付きZIP（zip4j） ---
-keep class net.lingala.zip4j.** { *; }
-dontwarn net.lingala.zip4j.**

# --- RAR5展開（libarchive／JNIバインディング） ---
-keep class me.zhanghai.android.libarchive.** { *; }
-keepclasseswithmembernames class me.zhanghai.android.libarchive.** {
    native <methods>;
}
-dontwarn me.zhanghai.android.libarchive.**

# --- SMB接続（smbj／共有一覧取得のdcerpc）＋SLF4J（ログAPI） ---
-keep class com.hierynomus.** { *; }
-dontwarn com.hierynomus.**
-keep class com.rapid7.client.** { *; }
-dontwarn com.rapid7.client.**
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**

# --- smbjが内部で使うイベントバス（net.engio.mbassy）本体 ---
# SubscriptionFactoryがReflectiveHandlerInvocation等の実装クラスを
# 「SubscriptionContextを1引数に取るコンストラクタ」でリフレクション生成するため、
# クラス名・コンストラクタを完全に保持する必要がある。
# 未keepだと実機で「接続に失敗しました：... did not specify the necessary
# constructor h62(SubscriptionContext)」が発生し、SMB(NAS)接続が全滅する
# （2026-09-20、T-018のR8導入直後に発覚。h62はReflectiveHandlerInvocationの
# 難読化後の名前）
-keep class net.engio.mbassy.** { *; }
-dontwarn net.engio.mbassy.**

# --- 上記mbassyが任意機能として参照するJavaEEのEL API（javax.el.*）。
#     Androidには存在せず、ComicVeilはEL機能を使わないため実害なし。
#     R8のmissing_rules.txtの指示に従いdontwarnのみ ---
-dontwarn javax.el.BeanELResolver
-dontwarn javax.el.ELContext
-dontwarn javax.el.ELResolver
-dontwarn javax.el.ExpressionFactory
-dontwarn javax.el.FunctionMapper
-dontwarn javax.el.ValueExpression
-dontwarn javax.el.VariableMapper

# --- Room（KSP生成コード。通常はconsumer-rules.proで足りるが念のため明示） ---
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**
