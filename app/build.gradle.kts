plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.android.nakamonrec"
    compileSdk = 36 // ライブラリの要求に合わせて 36 に戻す

    defaultConfig {
        applicationId = "com.android.nakamonrec"
        minSdk = 24
        targetSdk = 35 // 実行環境の互換性のために 35 (Android 15) に設定
        versionCode = 1
        versionName = "1.0"

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