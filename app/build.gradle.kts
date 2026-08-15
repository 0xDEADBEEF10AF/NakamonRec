import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
}

// Gitのコミット総数を取得する
val gitCommitCountProvider = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
}
val gitCommitCount = gitCommitCountProvider.standardOutput.asText.map { it.trim().toInt() }.getOrElse(1)

// 本番リリース署名情報を app/keystore.properties から読み込む (gitignore 対象)。
// ファイル不在時は release ビルドが debug 署名で署名されるため、Play 公開には使えないが、
// 開発マシンでの構文チェック等は通る。
val keystorePropsFile = file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        load(FileInputStream(keystorePropsFile))
    }
}

android {
    namespace = "com.dqw.nakamonrec"
    // Google Play の対象 API レベル要件 (2026/08/31 以降 API 36 必須) に準拠するため 36 に復帰。
    // 初期は 36 で作成 → 実機インストール互換性のため一時 35 に下げていたが、API 36 正式版化に伴い再度 36 へ。
    compileSdk = 36

    signingConfigs {
        getByName("debug") {
            // プロジェクトルートにあるdebug.keystoreを参照
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            enableV2Signing = true
            enableV3Signing = true
        }
        create("release") {
            // app/keystore.properties (gitignore) から本番署名情報を読み込む。
            // ファイル不在時は storeFile を未設定にして release ビルドを debug 署名にフォールバック。
            if (keystorePropsFile.exists()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = "com.dqw.nakamonrec"
        minSdk = 24
        targetSdk = 36

        versionCode = gitCommitCount
        versionName = "26.8.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ★APKサイズ削減：実機スマホに必要なアーキテクチャのみに絞り込む（x86系を除外）
        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            // app/keystore.properties が存在すれば release 署名、無ければ debug 署名にフォールバック
            signingConfig = if (keystorePropsFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

// APKの自動コピー＆リネーム設定
androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val type = variant.buildType ?: "unknown"
        val vName = android.defaultConfig.versionName ?: "1.0"
        val finalApkName = "NakamonRec_${vName}_${type}.apk"

        val copyTask = tasks.register("copy${variantName}Apk") {
            doLast {
                val apkDir = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK).get().asFile
                val apkFile = apkDir.walk().find { it.name.endsWith(".apk") }
                
                if (apkFile != null && apkFile.exists()) {
                    val destDir = File(rootProject.projectDir, "apks")
                    if (!destDir.exists()) destDir.mkdirs()
                    
                    val destFile = File(destDir, finalApkName)
                    apkFile.copyTo(destFile, overwrite = true)
                    
                    logger.lifecycle("✅ APK generated and copied to Project Root /apks/")
                    logger.lifecycle("📍 File: ${destFile.absolutePath}")
                }
            }
        }

        tasks.matching { it.name == "assemble$variantName" }.configureEach {
            finalizedBy(copyTask)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("com.google.android.play:app-update:2.1.0")
    implementation(project(":opencv"))
}
