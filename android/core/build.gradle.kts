plugins { kotlin("jvm"); application }
kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}
java { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
dependencies {
    implementation("com.squareup.okhttp3:okhttp:3.12.13")
    implementation("org.json:json:20240303")
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")
    testImplementation("junit:junit:4.13.2")
}
application { mainClass.set("com.hgtv.star.core.ProbeKt") }
