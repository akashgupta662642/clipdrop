import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM: session, pairing and transfer logic behind small
// interfaces. The Android app and the JVM interop harness both run this
// exact code, so the CI interop test exercises what ships.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.android.lint)
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
        allWarningsAsErrors.set(true)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(project(":protocol"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.bouncycastle.pkix) // test certificates only
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// Virtual-time helpers such as testTimeSource are still marked experimental.
tasks.compileTestKotlin {
    compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
}
