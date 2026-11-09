plugins { id("com.android.library"); kotlin("android") }

/**
 * TV 端（遥控器 / 焦点导航 / 1280×720 设计画布）。
 *
 * 所有页面与控件都在这里，`app` 只负责按设备形态把用户送到 `MainActivity`。
 */
android {
    namespace = "com.hgtv.star.tv"
    compileSdk = 36
    defaultConfig { minSdk = 19 }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    kotlinOptions { jvmTarget = "1.8" }
    packaging {
        resources.excludes += setOf(
            "META-INF/versions/**", "META-INF/*.kotlin_module",
            "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.version",
        )
    }
}

dependencies {
    api(project(":shared"))
    // PlayerView 与字幕 / 控制器样式。
    implementation("com.google.android.exoplayer:exoplayer-ui:2.19.1")
}
