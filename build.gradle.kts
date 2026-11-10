plugins {
    // AGP 내장 Kotlin과 Compose·직렬화·KSP의 컴파일러 버전을 맞춥니다.
    id("com.android.application") version "9.0.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
    id("com.google.devtools.ksp") version "2.3.4" apply false
}

// 모듈별 생성물을 한곳에 모아 소스 폴더와 프로젝트 루트를 깨끗하게 유지합니다.
allprojects {
    val outputName = if (path == ":") "root" else path.removePrefix(":").replace(':', '/')
    layout.buildDirectory.set(rootProject.layout.projectDirectory.dir(".tools/build/$outputName"))
}

