import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

// Built into the terminal: monospaced font (composeResources/font): Set the package name for the Res generation class
compose.resources {
    packageOfResClass = "dev.macodes.generated.resources"
}

kotlin {
    compilerOptions {
        // expect/actual class declarations are intentional KMP seams in this project.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(layout.buildDirectory.dir("generated/screenService/kotlin"))
            kotlin.srcDir(layout.buildDirectory.dir("generated/agentBridge/kotlin"))
        }
        val androidMain by getting
        val androidUnitTest by getting

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.multiplatform.settings)
            implementation(libs.multiplatform.settings.noarg)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime)
            implementation(libs.markdown.renderer)
            implementation(libs.markdown.renderer.code)
            implementation(libs.markdown.renderer.m3)
        }

        androidMain.dependencies {
            implementation(libs.sshj)
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.documentfile)
            implementation(libs.bouncycastle.prov)
            implementation(libs.bouncycastle.pkix)
            implementation(libs.okhttp)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.ui)
            implementation(libs.androidsvg)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        androidUnitTest.dependencies {
            implementation(libs.junit)
            implementation(libs.multiplatform.settings.test)
            implementation(libs.robolectric)
            implementation(libs.slf4j.nop)
            // Dispatchers.setMain + StandardTestDispatcher: TerminalController’s
            // Output consumption loops are pinned to Dispatchers.Main; testing requires a controllable main dispatcher.
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "dev.macodes.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    signingConfigs {
        // Release signature source (by priority):
        //   1. Process environment variables (CI: Direct injection via GitHub Secrets, zero files in the cloud)
        //   2. Project root .env (gitignore, standard frontend location) + project root macodes-release.jks (*.jks files are already ignored)
        //   3. keystore.properties (backward compatibility)
        // If the jks file does not exist, decode it from ANDROID_KEYSTORE_BASE64 and place it in build/signing/.
        // If none of these are present, skip the signature; this does not affect debug builds.
        val fileEnv = mutableMapOf<String, String>()
        val envFile = rootProject.file(".env")
        if (envFile.exists()) {
            envFile.readLines().forEach { line ->
                val t = line.trim()
                if (t.isNotEmpty() && !t.startsWith("#") && '=' in t) {
                    fileEnv[t.substringBefore('=').trim()] = t.substringAfter('=').trim()
                }
            }
        }
        val props = Properties().apply {
            val f = rootProject.file("keystore.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        fun secret(envKey: String, propKey: String): String? =
            System.getenv(envKey)?.takeIf { it.isNotEmpty() } ?: fileEnv[envKey] ?: props.getProperty(propKey)
        create("release") {
            var jks = file(secret("ANDROID_KEYSTORE_FILE", "storeFile") ?: "macodes-release.jks")
            // The published releases were signed with termish-release.jks; keep the old name as a fallback so existing keys still work.
            if (!jks.isFile) jks = rootProject.file("macodes-release.jks")
            if (!jks.isFile) jks = rootProject.file("termish-release.jks")
            val b64 = secret("ANDROID_KEYSTORE_BASE64", "keystoreBase64")
            if (!jks.isFile && b64 != null) {
                val out = layout.buildDirectory.dir("signing").get().asFile.apply { mkdirs() }
                    .resolve("macodes-release.jks")
                if (!out.isFile) out.writeBytes(Base64.getDecoder().decode(b64))
                jks = out
            }
            storeFile = jks
            storePassword = secret("ANDROID_KEYSTORE_PASSWORD", "storePassword")
            keyAlias = secret("ANDROID_KEY_ALIAS", "keyAlias")
            keyPassword = secret("ANDROID_KEY_PASSWORD", "keyPassword")
        }
    }

    defaultConfig {
        applicationId = "dev.macodes.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 52
        versionName = "1.8.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/**"
            excludes += "/META-INF/*.MF"
            excludes += "/META-INF/*.SF"
            excludes += "/META-INF/*.RSA"
            excludes += "/META-INF/DEPENDENCIES"
            // SSH uses only BC’s classic key/certificate algorithms; Picnic’s post-quantum parameter resources are approximately 1.2 MB,
            // R8 does not automatically remove JAR resources; explicitly excluding them can reduce the APK size without affecting SSH.
            excludes += "/org/bouncycastle/pqc/crypto/picnic/*.properties"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")?.takeIf {
                it.storeFile?.isFile == true && it.storePassword != null && it.keyAlias != null && it.keyPassword != null
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    debugImplementation(compose.uiTooling)
}
