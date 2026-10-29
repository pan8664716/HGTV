plugins {
    id("com.android.application") version "8.11.0" apply false
    kotlin("android") version "2.1.20" apply false
    kotlin("jvm") version "2.1.20" apply false
}

/**
 * 构建环境自检。
 *
 * Gradle 8.14 只支持到 JDK 23，而本机默认的 `java` 可能更新（实测 JDK 26）。
 * 那种情况下 Gradle 自己抛的异常只有一行 `26.0.2`——既不说哪个组件不支持，也不说怎么办，
 * 排查成本远高于这一句检查。
 *
 * 不做 `jvmToolchain(17)`：toolchain 在没装 JDK 17 的机器上会去自动下载，
 * 对离线构建是新的失败点。这里只做**快速失败 + 明确指引**，构建仍然用调用方的 JDK。
 */
run {
    val major = JavaVersion.current().majorVersion.toIntOrNull() ?: 0
    if (major < 17 || major > 23) {
        throw GradleException(
            "需要 JDK 17–23，当前是 ${JavaVersion.current()}（$major）。\n" +
                "macOS Homebrew 安装的 JDK 17 可用：\n" +
                "  JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home ./gradlew ...\n" +
                "或在项目根目录执行 `echo 17 > .java-version` 并用 jenv / asdf 切换。"
        )
    }
}
