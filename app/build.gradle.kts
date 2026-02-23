plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.android.nakamonrec"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.android.nakamonrec"
        minSdk = 24
        targetSdk = 36
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

// APK出力ファイル名のカスタマイズ (最新のAGP 9.0+ 対応)
// 非推奨の AppExtension を使わず、androidComponents API を使用します
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val type = variant.buildType ?: "unknown"
            val vName = android.defaultConfig.versionName ?: "1.0"
            if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                output.outputFileName.set("NakamonRec_${vName}_${type}.apk")
            }
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