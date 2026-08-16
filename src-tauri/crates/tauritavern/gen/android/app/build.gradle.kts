import java.util.Properties
import java.io.FileInputStream
import org.apache.tools.ant.taskdefs.condition.Os
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("rust")
}

val tauriProperties = Properties().apply {
    val propFile = file("tauri.properties")
    if (propFile.exists()) {
        propFile.inputStream().use { load(it) }
    }
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasReleaseSigningConfig = keystorePropertiesFile.exists()

val E2E_KEY_ALIAS = "tauritavern-e2e"
val E2E_KEY_PASSWORD = "tauritavern-e2e"
val e2eKeystoreFile = layout.buildDirectory.file("generated/keystore/tauritavern-e2e.jks")

// Dedicated, locally generated non-production signing identity for the E2E
// variant. Production signing stays bound to keystore.properties above.
val createE2eKeystore = tasks.register<Exec>("createE2eKeystore") {
    val keytool = if (Os.isFamily(Os.FAMILY_WINDOWS)) "keytool.exe" else "keytool"
    val keystore = e2eKeystoreFile.get().asFile

    onlyIf { !keystore.exists() }
    doFirst {
        keystore.parentFile.mkdirs()
    }
    commandLine(
        keytool,
        "-genkeypair",
        "-noprompt",
        "-alias", E2E_KEY_ALIAS,
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "10000",
        "-dname", "CN=TauriTavern E2E, OU=Testing, O=TauriTavern, C=US",
        "-keystore", keystore.absolutePath,
        "-storepass", E2E_KEY_PASSWORD,
        "-keypass", E2E_KEY_PASSWORD,
    )
    outputs.file(keystore)
}

android {
    compileSdk = 36
    namespace = "com.tauritavern.client"
    defaultConfig {
        manifestPlaceholders["usesCleartextTraffic"] = "false"
        applicationId = "com.tauritavern.client"
        minSdk = 26
        targetSdk = 36
        versionCode = tauriProperties.getProperty("tauri.android.versionCode", "1").toInt()
        versionName = tauriProperties.getProperty("tauri.android.versionName", "1.0")
    }
    signingConfigs {
        create("e2e") {
            keyAlias = E2E_KEY_ALIAS
            keyPassword = E2E_KEY_PASSWORD
            storeFile = e2eKeystoreFile.get().asFile
            storePassword = E2E_KEY_PASSWORD
        }
        if (hasReleaseSigningConfig) {
            create("release") {
                val keystoreProperties = Properties()
                keystoreProperties.load(FileInputStream(keystorePropertiesFile))

                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["password"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["password"] as String
            }
        }
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            isDebuggable = true
            isJniDebuggable = true
            isMinifyEnabled = false
            buildConfigField("boolean", "E2E_ENABLED", "false")
            packaging {
                jniLibs.keepDebugSymbols.add("*/arm64-v8a/*.so")
                jniLibs.keepDebugSymbols.add("*/armeabi-v7a/*.so")
                jniLibs.keepDebugSymbols.add("*/x86/*.so")
                jniLibs.keepDebugSymbols.add("*/x86_64/*.so")
            }
        }
        getByName("release") {
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            buildConfigField("boolean", "E2E_ENABLED", "false")
            proguardFiles(
                *fileTree(".") { include("**/*.pro") }
                    .plus(getDefaultProguardFile("proguard-android-optimize.txt"))
                    .toList().toTypedArray()
            )
        }
        create("e2e") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            applicationIdSuffix = ".e2e"
            versionNameSuffix = "-e2e"
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            isDebuggable = true
            isJniDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("e2e")
            buildConfigField("boolean", "E2E_ENABLED", "true")
        }
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        buildConfig = true
    }
}

afterEvaluate {
    // AGP signs/validates before packaging; make sure the generated E2E
    // keystore exists before any signing-related E2E task runs.
    tasks.matching { task ->
        task.name.contains("E2e", ignoreCase = true) &&
            (
                task.name.startsWith("package", ignoreCase = true) ||
                    task.name.startsWith("validate", ignoreCase = true) ||
                    task.name.startsWith("signing", ignoreCase = true)
                )
    }.configureEach {
        dependsOn(createE2eKeystore)
    }
}

gradle.taskGraph.whenReady {
    val releaseSigningRequired = allTasks.any { task ->
        task.project.path == ":app" &&
            (task.name.startsWith("package") || task.name.startsWith("bundle")) &&
            task.name.contains("Release")
    }
    if (releaseSigningRequired && !hasReleaseSigningConfig) {
        throw GradleException("Release signing requires keystore.properties next to the Android Gradle project")
    }
}

rust {
    rootDirRel = "../../../"
}

tasks.withType<KotlinCompile>().configureEach {
    exclude("**/com/tauritavern/client/generated/RustWebChromeClient.kt")
    exclude("**/com/tauritavern/client/generated/RustWebViewClient.kt")
    exclude("**/com/tauritavern/client/generated/Ipc.kt")
}

dependencies {
    implementation("androidx.core:core:1.18.0-rc01")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.0")
}

apply(from = "tauri.build.gradle.kts")
