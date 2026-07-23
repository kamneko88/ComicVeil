plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.kamneko88.comicveil"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kamneko88.comicveil"
        minSdk = 26
        targetSdk = 36
        versionCode = 57
        versionName = "0.37.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    // アイコンセット：Material Icons から Lucide（スタイリッシュな線画アイコン）へ全面移行
    implementation("com.composables:icons-lucide-cmp:2.2.1")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Coil（画像表示）
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")

    // Room（データベース）
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")

    // RAR展開
    implementation("com.github.junrar:junrar:7.5.5")

    // ZIP・7z展開（Shift-JIS対応・7z対応）
    implementation("org.apache.commons:commons-compress:1.26.2")
    // 7z の LZMA/XZ 圧縮形式に必要（commons-compress が内部で使用）
    implementation("org.tukaani:xz:1.9")

    // パスワード付きZIP対応
    implementation("net.lingala.zip4j:zip4j:2.11.5")

    // libarchive（PoC検証用：RAR5対応調査）
    implementation("me.zhanghai.android.libarchive:library:1.1.6")

    // SMB接続（NASアクセス）
    implementation("com.hierynomus:smbj:0.13.0")

    // SMBの共有フォルダ一覧取得（srvsvc/MSRPC）。smbjは上の明示指定(0.13.0)が優先される
    implementation("com.rapid7.client:dcerpc:0.12.13")

    // SAF（Storage Access Framework）でのフォルダ操作用
    implementation("androidx.documentfile:documentfile:1.0.1")

    // テスト
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
