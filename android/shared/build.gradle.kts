plugins { id("com.android.library"); kotlin("android") }

/**
 * 跨端共享层：账号 / 会话 / 收藏 / 进度 / 封面 / 播放传输 / 弹幕渲染 / 调试面板。
 *
 * 这里**不放任何页面**，也不认识 TV 与手机各自的版面语言。
 * 依赖方向是单向的：`core ← shared ← feature-tv / feature-phone ← app`。
 */
android {
    namespace = "com.hgtv.star.shared"
    compileSdk = 36
    defaultConfig { minSdk = 19 }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    kotlinOptions {
        jvmTarget = "1.8"
        // Media3 的播放实现类全是 `@UnstableApi`，这里直接碰 `DataSource`，与 app 模块同样的处理。
        freeCompilerArgs += "-opt-in=androidx.media3.common.util.UnstableApi"
    }
    packaging {
        resources.excludes += setOf(
            "META-INF/versions/**", "META-INF/*.kotlin_module",
            "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.version",
        )
    }
}

dependencies {
    // core 是纯 JVM（网络协议 / 签名 / 解析 / 状态机），shared 把它原样转出去，
    // 两端页面都直接用到里面的类型。
    api(project(":core"))
    api("androidx.core:core:1.8.0")
    // 兼容层的 VectorDrawableCompat（API 21 以下要它才能解矢量图）。
    api("androidx.vectordrawable:vectordrawable:1.1.0")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 播放数据源（VideoDataSource）需要 media3-datasource；播放器本体由各端自己建。
    api("androidx.media3:media3-datasource:1.4.1")
    api("androidx.media3:media3-exoplayer:1.4.1")
    // 扫码登录的二维码生成。
    api("com.google.zxing:core:3.4.1")
    // MediaSession（TV 端的媒体键 / 手机的锁屏控制）都走这一层。
    api("androidx.media:media:1.6.0")

    // 公共层自己的单测。这里只放**不碰 Android 框架**的纯逻辑（颜色 / 文本压缩 /
    // 触摸判定这类），因此跑在普通 JVM 上即可，不需要 Robolectric。
    // 碰框架的那部分由两端的真机回归覆盖（见 docs/多端重构-差异矩阵与迁移.md 的验证清单）。
    testImplementation("junit:junit:4.13.2")
}
