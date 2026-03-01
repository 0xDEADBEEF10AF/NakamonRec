import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.android.application)
}

// Gitのコミット総数を取得する
val gitCommitCountProvider = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
}
val gitCommitCount = gitCommitCountProvider.standardOutput.asText.map { it.trim().toInt() }.getOrElse(1)

android {
    namespace = "com.android.nakamonrec"
    // ★Android 14実機インストールのために安定版の35を使用
    compileSdk = 35 

    signingConfigs {
        getByName("debug") {
            // プロジェクトルートにあるdebug.keystoreを参照
            // 注意: ターミナルで keytool コマンドを実行して作成しておく必要があります
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.android.nakamonrec"
        minSdk = 24
        targetSdk = 35
        
        versionCode = gitCommitCount
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            // リリース版にも同じデバッグ署名を適用して上書き・インストールを可能にする
            signingConfig = signingConfigs.getByName("debug")
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
    implementation(project(":opencv"))
}
