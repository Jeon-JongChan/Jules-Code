plugins {
    id("com.android.application") version "8.9.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
}

// 모듈별 생성물을 한곳에 모아 소스 폴더와 프로젝트 루트를 깨끗하게 유지합니다.
allprojects {
    val outputName = if (path == ":") "root" else path.removePrefix(":").replace(':', '/')
    layout.buildDirectory.set(rootProject.layout.projectDirectory.dir(".tools/build/$outputName"))
}

