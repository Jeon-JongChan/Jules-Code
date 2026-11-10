plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.jules.code"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jules.code"
        // Play 자동 보호의 최소 SDK 요구사항에 맞춰 Android 7.0 이상을 지원합니다.
        minSdk = 24
        targetSdk = 36
        versionCode = 5
        versionName = "1.3.0"
        testInstrumentationRunner = "com.jules.code.PlayUpdateInstrumentation"
        base.archivesName.set("Jules Code")

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("JULES_KEYSTORE_PATH") ?: ".tools/.build/jules-release-key.jks"
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
            // 사용하지 않는 코드를 제거하고 이름을 난독화합니다.
            isMinifyEnabled = true

            // 사용하지 않는 리소스를 제거해 앱 크기를 줄입니다.
            isShrinkResources = true

            ndk {
                // 추출 가능한 네이티브 디버그 기호를 AAB에 포함합니다.
                debugSymbolLevel = "FULL"
            }
            //isMinifyEnabled = false
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
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // 앱 내 언어 전환 시 설치된 기기 언어 외 번역도 항상 사용할 수 있어야 합니다.
    bundle {
        language {
            enableSplit = false
        }
    }

    // 향후 테스트를 추가해도 테스트 전용 입력이 app/src에 섞이지 않도록 분리합니다.
    sourceSets {
        getByName("test") {
            java.setSrcDirs(listOf(rootProject.file(".tools/.test/unit/java")))
            resources.setSrcDirs(listOf(rootProject.file(".tools/.test/unit/resources")))
        }
        getByName("androidTest") {
            java.setSrcDirs(listOf(rootProject.file(".tools/.test/android/java")))
            resources.setSrcDirs(listOf(rootProject.file(".tools/.test/android/resources")))
            assets.setSrcDirs(listOf(rootProject.file(".tools/.test/android/assets")))
            res.setSrcDirs(listOf(rootProject.file(".tools/.test/android/res")))
            manifest.srcFile(rootProject.file(".tools/.test/android/AndroidManifest.xml"))
        }
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
        val vName = versionName ?: "1.3.0"
        outputs.all {
            (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)?.outputFileName =
                if (buildType.name == "release") "Jules Code-v${vName}.apk" else "Jules Code-v${vName}-${buildType.name}.apk"
        }

        val apkDirectory = layout.buildDirectory.dir("outputs/apk/$dirName")
        val distributionDirectory = rootProject.layout.projectDirectory.dir("gradle")
        assembleProvider.configure {
            // 성공한 빌드의 최종 파일만 전달하고 원본을 유지해 증분 빌드의 출력 추적을 보존합니다.
            doLast {
                project.copy {
                    from(apkDirectory) {
                        include("*.apk", "*.apk.idsig")
                    }
                    into(distributionDirectory)
                }
            }
        }
    }
}

// Play Console용 릴리스 번들과 해당 빌드의 가독화 파일을 배포 폴더에 모읍니다.
tasks.matching { it.name == "bundleRelease" }.configureEach {
    doLast {
        project.copy {
            from(layout.buildDirectory.dir("outputs/bundle/release")) {
                include("*.aab")
                rename { "Jules Code-v${android.defaultConfig.versionName}.aab" }
            }
            // R8 비활성화 시 이전 빌드의 매핑을 새 번들과 함께 복사하지 않습니다.
            if (android.buildTypes.getByName("release").isMinifyEnabled) {
                from(layout.buildDirectory.dir("outputs/mapping/release")) {
                    include("mapping.txt")
                }
            }
            into(rootProject.layout.projectDirectory.dir("gradle"))
        }
    }
}

dependencies {
    implementation("com.google.android.play:app-update:2.1.0")
    testImplementation("junit:junit:4.13.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Room (API 키, 세션/소스 캐시, 로컬 프롬프트 템플릿/스케줄 저장)
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

}
