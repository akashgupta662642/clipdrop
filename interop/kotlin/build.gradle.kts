import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The phone side of the CI interop test: the Android :engine module on a plain JVM, with a
// PKCS#12 identity standing in for the AndroidKeyStore. See interop/README.md.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
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
    implementation(project(":engine"))
    implementation(libs.bouncycastle.pkix) // creates the harness's self-signed certificate
}

application {
    mainClass.set("io.github.akashgupta662642.clipdrop.interop.MainKt")
    applicationName = "clipdrop-interop"
}
