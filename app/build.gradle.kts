plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.jules.code"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jules.code"
        minSdk = 26
        // ponytail: Android 14/15 기기에서 사이드로딩 시 targetSdk 35 제한 및 Play Protect 경고를 피하기 위해 targetSdk 34 설정 (compileSdk 35 유지)
        targetSdk = 34
        versionCode = 3
        versionName = "1.2.0"
        base.archivesName.set("Jules Code")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("JULES_KEYSTORE_PATH") ?: ".build/jules-release-key.jks"
            val ksFile = rootProject.file(ksPath)
            val ksPass = System.getenv("JULES_KEYSTORE_PASSWORD") ?: "julesrelease123"
            val kAlias = System.getenv("JULES_KEY_ALIAS") ?: "jules"
            val kPass = System.getenv("JULES_KEY_PASSWORD") ?: "julesrelease123"

            if (!ksFile.exists()) {
                ksFile.parentFile?.mkdirs()
                val javaHome = System.getProperty("java.home")
                val keytoolBin = File(javaHome, "bin/keytool.exe").takeIf { it.exists() }
                    ?: File(javaHome, "bin/keytool")
                val proc = ProcessBuilder(
                    keytoolBin.absolutePath,
                    "-genkeypair", "-v",
                    "-keystore", ksFile.absolutePath,
                    "-alias", kAlias,
                    "-keyalg", "RSA",
                    "-keysize", "2048",
                    "-validity", "10000",
                    "-storepass", ksPass,
                    "-keypass", kPass,
                    "-dname", "CN=Jules Code, OU=Mobile, O=JulesCode, L=Seoul, ST=Seoul, C=KR"
                ).redirectErrorStream(true).start()
                proc.waitFor()
            }

            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = ksPass
                keyAlias = kAlias
                keyPassword = kPass
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        val relSigning = signingConfigs.findByName("release")
        debug {
            // 검증용 앱을 기존 릴리스 앱과 별도로 설치하여 사용자 데이터를 보존합니다.
            applicationIdSuffix = ".debug"
            // 디버그/릴리즈 APK 간 교차 덮어쓰기 시 INSTALL_FAILED_UPDATE_INCOMPATIBLE 방지 및 CN=Android Debug 차단 회피
            if (relSigning?.storeFile?.exists() == true) {
                signingConfig = relSigning
            }
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (relSigning?.storeFile?.exists() == true) {
                relSigning
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    applicationVariants.all {
        val vName = versionName ?: "1.2.0"
        outputs.all {
            (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)?.outputFileName =
                if (buildType.name == "release") "Jules Code-v${vName}.apk" else "Jules Code-v${vName}-${buildType.name}.apk"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // Room (API 키, 세션/소스 캐시, 로컬 프롬프트 템플릿/스케줄 저장)
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
