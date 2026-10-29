plugins { kotlin("jvm"); application }
kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}
java { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
dependencies {
    // 与 app 模块同一版本：`core` 的网络层被 app 直接复用，两条版本线会在
    // `checkDebugDuplicateClasses` 上撞车。
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")
    // 状态机与调度器抽象只用 `coroutines-core`（纯 JVM），不依赖 Android。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
application { mainClass.set("com.hgtv.star.core.ProbeKt") }
