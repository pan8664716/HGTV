import java.util.Properties

plugins { id("com.android.application"); kotlin("android") }
val signingProperties = Properties().apply { val config = rootProject.file("keystore.properties"); if (config.exists()) config.inputStream().use { load(it) } }
fun signingValue(env: String, key: String): String? = System.getenv(env) ?: signingProperties.getProperty(key)

android {
    namespace = "com.hgtv.star"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.hgtv.star"
        minSdk = 19
        // 钉在 29：这是**传统存储模式**（`requestLegacyExternalStorage`）还能生效的最高版本，
        // 而传统模式下一个 `WRITE_EXTERNAL_STORAGE` 就等于整个 `/sdcard` 的读写权——
        // 会话镜像因此可以写自建的 `/sdcard/.hgtv/`（卸载不删），重装后直接恢复登录。
        // 30 起该开关被忽略，10 及以上就只能写自己的 `Android/data`（卸载必删）。
        // 代价：Google Play 要求 34+，本应用是自签分发、不进 Play，不受影响。
        targetSdk = 29
        multiDexEnabled = true
        versionCode = System.getenv("HGTV_VERSION_CODE")?.toInt() ?: 65
        versionName = System.getenv("HGTV_VERSION_NAME") ?: "1.2.8"
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        create("delivery") {
            storeFile = signingValue("HGTV_KEYSTORE", "storeFile")?.let { file(it) }
            storePassword = signingValue("HGTV_STORE_PASSWORD", "storePassword")
            keyAlias = signingValue("HGTV_KEY_ALIAS", "keyAlias")
            keyPassword = signingValue("HGTV_KEY_PASSWORD", "keyPassword")
        }
    }
    buildTypes {
        // 两个构建类型都要钉主 dex：minSdk 19 时 Dalvik 在 MultiDex.install 之前只能看到 classes.dex，
        // 被 R8 改名/裁剪过的启动链路一旦落到第二个 dex，4.4 上会直接 NoClassDefFoundError。
        // debug 也用发布签名：这样 `adb install -r` 能在「线上正式包」和「本地调试包」之间
        // 直接覆盖安装，不必先卸载（卸载会清掉本机记录、收藏、登录态，验证时要重新走一遍）。
        // 代价：debug 包与正式包共用同一个签名密钥，不能再靠签名区分渠道；本项目自签分发，
        // 没有别的渠道，因此没有影响。密钥缺失时（例如没配 keystore.properties 的机器）
        // 回落到默认 debug 签名，构建不会因此失败。
        getByName("debug") {
            multiDexKeepProguard = file("multidex-keep.pro")
            signingConfig = runCatching { signingConfigs.getByName("delivery") }
                .getOrNull()?.takeIf { it.storeFile?.exists() == true && it.storePassword != null && it.keyAlias != null }
                ?: signingConfigs.getByName("debug")
        }
        getByName("release") { multiDexKeepProguard = file("multidex-keep.pro"); signingConfig = signingConfigs.getByName("delivery"); isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    kotlinOptions {
        jvmTarget = "1.8"
        // Media3 把「播放器实现类」全部标了 `@UnstableApi`（`DefaultRenderersFactory` /
        // `MediaCodecSelector` / `DefaultLoadControl` / `ProgressiveMediaSource` /
        // `AnalyticsListener` …）。本应用必须直接碰这些类（自定义渲染器、自定义解码器筛选、
        // 自定义 DataSource），因此按官方推荐在模块级统一 opt-in，而不是给每个类加注解。
        freeCompilerArgs += "-opt-in=androidx.media3.common.util.UnstableApi"
    }
    lint {
        // targetSdk 钉在 29 是刻意的（见上面的注释：传统存储模式要它才能写 /sdcard/.hgtv/）。
        // `ExpiredTargetSdkVersion` 只在「上架 Google Play」这一前提下成立，本应用自签分发、
        // 不进 Play；不关掉它，`assembleRelease` 的 lintVitalRelease 会直接中断打包。
        disable += "ExpiredTargetSdkVersion"
    }
    packaging {
        // 协程与 OkHttp 4 都会带进来一批 `META-INF/versions/**` 与 license 文件；
        // 前者是 Java 9 多版本 jar 的入口，AGP 已经按 minSdk 选好，重复打包只会触发
        // `checkDuplicateClasses`；后者纯文本，对运行时无用。
        resources.excludes += setOf(
            "META-INF/versions/**",
            "META-INF/*.kotlin_module",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/*.version",
        )
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation("androidx.core:core:1.8.0")
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("androidx.vectordrawable:vectordrawable:1.1.0")
    implementation("androidx.media:media:1.6.0")
    implementation("com.google.zxing:core:3.4.1")
    // OkHttp 4.12：3.12.13 已停止维护，而它在 h2 读线程上的越界崩溃（见
    // `PlaybackTransport` 的类注释）是 3.x 才有的问题。4.x 是 Kotlin 实现，
    // API 兼容，`minSdk 19` 可用。
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(project(":core")) { exclude(group = "org.json") }
    // 播放内核：ExoPlayer 2.x 已停止维护，迁移到 Media3。
    //
    // **版本被 minSdk 19 钉死在 1.4.1**：Media3 从 1.5.0 起要求 `minSdk 21`，
    // 升上去等于放弃 Android 4.4（本应用的主力机型）。1.4.1 是最后一个支持 19 的版本，
    // 也是目前能同时满足「不再 EOL」与「4.4 可用」的唯一选择。
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource:1.4.1")
    // 协程：全站的线程模型（见 `architecture/AppDispatchers.kt`）。
    // 1.8.1 是最后一条仍兼容 Kotlin 1.9/2.0 混合工具链的稳定线，且不引入原生库。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // ART 基线 profile 的落盘者（见 `src/main/baseline-prof.txt`）。
    //
    // 作用链：AGP 把 `baseline-prof.txt` 编成 APK 里的 `assets/dexopt/baseline.prof`，
    // 这个库在**首次启动后**把它写进本应用的 `cur` profile 目录；系统随后的
    // `bg-dexopt`（这台盒子上 `pm.dexopt.bg-dexopt=speed-profile`）就会按它做 AOT，
    // 之后每次冷启动都是编译过的代码——否则 `speed-profile` 遇到空 profile 什么都不编，
    // 应用永远跑在解释执行 + JIT 上（实测 `Displayed +3s380` vs 编译过 `+837ms`）。
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    // 友盟（U-App 统计 + U-APM）**已整体移除**：依赖、efs 插桩插件、初始化与全部调用点都已删除。
    // 包内不再有任何第三方采集 SDK，也没有随之带进来的原生库。
}
