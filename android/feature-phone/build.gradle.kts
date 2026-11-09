plugins { id("com.android.library"); kotlin("android") }

/**
 * 手机 / 平板端。
 *
 * 与 TV 端共用 [shared] 的数据层与播放层，但页面、手势、版面全部独立：
 * 手机上是竖屏、触摸、一屏一集；TV 上是横屏、遥控器、焦点导航。
 * 两端**互不依赖**，任何跨端能力都必须沉到 `shared`。
 */
android {
    namespace = "com.hgtv.star.phone"
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
    implementation("com.google.android.exoplayer:exoplayer-ui:2.19.1")
    // 推荐流（整屏吸附）与剧场网格都用它：1.2.1 是最后一条支持 minSdk 14 的稳定线，
    // 与 Android 4.4 的底线一致。
    implementation("androidx.recyclerview:recyclerview:1.2.1")
}
