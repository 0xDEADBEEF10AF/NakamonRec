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
    compileSdk = 36

    defaultConfig {
        applicationId = "com.android.nakamonrec"
        minSdk = 24
        targetSdk = 35
        
        // ★自動インクリメント：コミット総数をバージョンコードに使用
        versionCode = gitCommitCount
        versionName = "1.1.0"

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

// ★新機能：ビルド時に version.json を自動更新するタスク
tasks.register("updateVersionJson") {
    group = "versioning"
    description = "Updates version.json with the current versionCode and versionName"
    
    doLast {
        val vCode = android.defaultConfig.versionCode ?: 1
        val vName = android.defaultConfig.versionName ?: "1.0.0"
        val updateUrl = "https://github.com/0xDEADBEEF10AF/NakamonRec/releases"
        
        val jsonContent = """
            {
              "versionCode": $vCode,
              "versionName": "$vName",
              "updateUrl": "$updateUrl"
            }
        """.trimIndent()
        
        val versionFile = File(rootProject.projectDir, "version.json")
        versionFile.writeText(jsonContent)
        println("✅ version.json has been updated: Code $vCode, Name $vName")
    }
}

// assembleタスク（ビルド）の後に自動で実行されるように紐付け
tasks.matching { it.name.startsWith("assemble") }.configureEach {
    finalizedBy("updateVersionJson")
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
