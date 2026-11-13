import org.gradle.process.ExecOperations
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}

// ---------------------------------------------------------------------------
// ktlint: a CLI JAR (JavaExec) from Maven Central that does not have any dependencies on the plugin portal (in case of unstable network connections);
// The rule reads .editorconfig and checks all Kotlin source files in the composeApp directory.
// ---------------------------------------------------------------------------
val ktlintVersion = "1.5.0"
val ktlintConfig = configurations.create("ktlint") {
    isCanBeConsumed = false
    isCanBeResolved = true
    // ktlint-cli releases two variants: external and shadowed. Choose "shadowed" (for fat JARs,
    // (Includes all dependencies; JavaExec can run it directly.)
    attributes {
        attribute(Attribute.of("org.gradle.dependency.bundling", String::class.java), "shadowed")
    }
}
dependencies {
    ktlintConfig("com.pinterest.ktlint:ktlint-cli:$ktlintVersion")
}

// ---------------------------------------------------------------------------
// Unified entry point for local workflows (CI and Makefile reuse the same set of tasks, avoiding the fragmentation of workflow knowledge across three locations)
// ---------------------------------------------------------------------------

/** A multi-command task base class that supports a configuration cache: executes external commands via injection of `ExecOperations`. */
abstract class ExecTask @Inject constructor() : DefaultTask() {
    @get:Inject
    abstract val execOps: ExecOperations

    fun run(vararg cmd: String, ignoreExit: Boolean = false) {
        execOps.exec {
            commandLine(*cmd)
            isIgnoreExitValue = ignoreExit
        }
    }
}

/** Launch the local sshd test (idempotent: skip if there is a listener). Integration tests automatically skip based on port 22222 availability. */
abstract class StartTestSshdTask @Inject constructor() : ExecTask() {
    @TaskAction
    fun start() {
        // Maintain consistency with scripts/test-sshd.sh and integration tests (Termish_TEST_PORT defaults to 22222);
        // Do not hardcode the port—other services on the local machine or in CI (such as GitLab) may be using it, leading to a false positive that the port is already a listener.
        val port = System.getenv("Termish_TEST_PORT")?.toIntOrNull() ?: 22222
        val up = runCatching {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 500) }
        }.isSuccess
        if (up) {
            logger.lifecycle("sshd 已在 127.0.0.1:$port 监听，跳过")
        } else {
            run("bash", "scripts/test-sshd.sh")
        }
    }
}
tasks.register<StartTestSshdTask>("startTestSshd") {
    group = "verification"
    description = "启动本地测试 sshd（127.0.0.1:22222），已在运行则跳过"
}

/** Transport layer integration test: Automatically launch sshd and then run Android local JVM tests (based on self-detection of sshd/mosh availability). */
tasks.register("testIntegration") {
    group = "verification"
    description = "SSH/Mosh 集成测试（自动起 sshd；单测一拼跑）"
    dependsOn("startTestSshd", "screenServiceTest", ":composeApp:testDebugUnitTest")
}

project(":composeApp").tasks.configureEach {
    if (name == "testDebugUnitTest") {
        mustRunAfter(":startTestSshd")
        // Integration tests depend on the external sshd status and cannot reuse test results from when the service was previously unavailable.
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf("Tests depend on local SSH and screen servers") { true }
    }
}

/** Build and install the debug version on the emulator or device, then launch the app. */
abstract class RunDebugTask @Inject constructor() : ExecTask() {
    @TaskAction
    fun launch() {
        run("adb", "shell", "am", "force-stop", "dev.termish.app", ignoreExit = true)
        run("adb", "shell", "monkey", "-p", "dev.termish.app", "-c", "android.intent.category.LAUNCHER", "1")
    }
}
tasks.register<RunDebugTask>("runDebug") {
    group = "run"
    description = "installDebug + 启动 dev.termish.app"
    dependsOn(":composeApp:installDebug")
}

/** Native service build and resource packaging: Cross-compile on the development machine; on the remote machine, simply run the executable file. */
val agentBridgeRustBuild = tasks.register<ExecTask>("agentBridgeRustBuild") {
    group = "build"
    inputs.files(fileTree("agentBridge/rust") { exclude("target/**", ".gitignore") })
    inputs.files(fileTree("tools/service-build") { exclude("target/**", ".gitignore") })
    inputs.files("scripts/service-build.sh", "LICENSES/TermishScreen-Rust.txt")
    outputs.dir(layout.projectDirectory.dir("agentBridge/build/binaries"))
    doLast { run("sh", "scripts/service-build.sh", "build-agent") }
}
val agentBridgeBuild = tasks.register<ExecTask>("agentBridgeBuild") {
    group = "build"
    dependsOn(agentBridgeRustBuild)
    inputs.dir(layout.projectDirectory.dir("agentBridge/build/binaries"))
    inputs.files(fileTree("tools/service-build") { exclude("target/**", ".gitignore") })
    outputs.file(layout.projectDirectory.file("composeApp/build/generated/agentBridge/kotlin/dev/termish/agent/AgentBridgeAssets.kt"))
    outputs.dir(layout.projectDirectory.dir("composeApp/src/commonMain/composeResources/files/termish-agent"))
    doLast { run("sh", "scripts/service-build.sh", "pack-agent") }
}
val screenServiceRustBuild = tasks.register<ExecTask>("screenServiceRustBuild") {
    group = "build"
    inputs.property("macosSigningIdentity", providers.environmentVariable("TERMISH_SCREEN_MACOS_SIGN_IDENTITY").orElse(""))
    inputs.files(fileTree("screenService/rust") { exclude("target/**", ".gitignore") })
    inputs.dir("screenService/macos")
    inputs.dir("screenService/linux")
    inputs.files(fileTree("tools/service-build") { exclude("target/**", ".gitignore") })
    inputs.files("screenService/service.properties", "LICENSES/TermishScreen-Rust.txt", "scripts/service-build.sh")
    outputs.dir(layout.projectDirectory.dir("screenService/build/binaries"))
    doLast { run("sh", "scripts/service-build.sh", "build-screen") }
}
val screenServiceBuild = tasks.register<ExecTask>("screenServiceBuild") {
    group = "build"
    dependsOn(screenServiceRustBuild)
    inputs.files("screenService/native-install.sh", "screenService/install.sh", "screenService/service.properties")
    inputs.files(fileTree("tools/service-build") { exclude("target/**", ".gitignore") })
    inputs.dir(layout.projectDirectory.dir("screenService/build/binaries"))
    outputs.file(layout.projectDirectory.file("composeApp/build/generated/screenService/kotlin/dev/termish/screen/ScreenServiceAssets.kt"))
    outputs.dir(layout.projectDirectory.dir("composeApp/src/commonMain/composeResources/files/termish-screen"))
    doLast { run("sh", "scripts/service-build.sh", "pack-screen") }
}
project(":composeApp").tasks.configureEach {
    if ((name.startsWith("compile") && name.contains("Kotlin")) || name.startsWith("copyNonXmlValueResourcesFor") || name == "generateComposeResClass") {
        dependsOn(screenServiceBuild, agentBridgeBuild)
    }
}
tasks.register<ExecTask>("screenServiceRustTest") {
    group = "verification"
    doLast { run("sh", "scripts/service-test.sh", "screenService") }
}
tasks.register("screenServiceTest") {
    group = "verification"
    dependsOn("screenServiceRustTest")
}
tasks.register<ExecTask>("agentBridgeTest") {
    group = "verification"
    doLast { run("sh", "scripts/service-test.sh", "agentBridge") }
}

/** Uninstall and reinstall: Resolves conflicts with old signatures or versions on the device (INSTALL_FAILED_UPDATE_INCOMPATIBLE). */
abstract class ReinstallDebugTask @Inject constructor() : ExecTask() {
    @TaskAction
    fun reinstall() {
        run("adb", "uninstall", "dev.termish.app", ignoreExit = true)
        run("adb", "install", "-r", "composeApp/build/outputs/apk/debug/composeApp-debug.apk")
        run("adb", "shell", "monkey", "-p", "dev.termish.app", "-c", "android.intent.category.LAUNCHER", "1")
    }
}
tasks.register<ReinstallDebugTask>("reinstallDebug") {
    group = "run"
    description = "卸载 dev.termish.app 后重新安装并启动"
    dependsOn(":composeApp:assembleDebug")
}

/** Verify that the signature secrets are ready (.env or process environment variables) for pre-release checks on the local machine. */
abstract class CheckSigningSecretsTask @Inject constructor() : DefaultTask() {
    @get:Internal
    abstract val envFilePath: org.gradle.api.provider.Property<String>

    @get:Internal
    abstract val rootPath: org.gradle.api.provider.Property<String>

    @TaskAction
    fun check() {
        val root = java.io.File(rootPath.get())
        val fileEnv = mutableMapOf<String, String>()
        java.io.File(envFilePath.get()).takeIf { it.isFile }?.readLines()?.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && '=' in trimmed) {
                fileEnv[trimmed.substringBefore('=').trim()] = trimmed.substringAfter('=').trim()
            }
        }
        val legacy = java.util.Properties().apply {
            root.resolve("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
        }
        fun secret(envKey: String, legacyKey: String): String? =
            System.getenv(envKey)?.takeIf { it.isNotBlank() }
                ?: fileEnv[envKey]?.takeIf { it.isNotBlank() }
                ?: legacy.getProperty(legacyKey)?.takeIf { it.isNotBlank() }

        val configuredPath = secret("ANDROID_KEYSTORE_FILE", "storeFile")
        val keyFiles =
            buildList {
                if (configuredPath != null) {
                    val configured = java.io.File(configuredPath)
                    add(if (configured.isAbsolute) configured else root.resolve(configuredPath))
                    add(root.resolve("composeApp").resolve(configuredPath))
                }
                add(root.resolve("termish-release.jks"))
            }
        val hasKeyMaterial = keyFiles.any { it.isFile } || secret("ANDROID_KEYSTORE_BASE64", "keystoreBase64") != null
        val missing =
            buildList {
                if (!hasKeyMaterial) add("ANDROID_KEYSTORE_FILE 或 ANDROID_KEYSTORE_BASE64")
                if (secret("ANDROID_KEYSTORE_PASSWORD", "storePassword") == null) add("ANDROID_KEYSTORE_PASSWORD")
                if (secret("ANDROID_KEY_ALIAS", "keyAlias") == null) add("ANDROID_KEY_ALIAS")
                if (secret("ANDROID_KEY_PASSWORD", "keyPassword") == null) add("ANDROID_KEY_PASSWORD")
            }
        check(missing.isEmpty()) {
            "release 签名配置不完整，缺少：${missing.joinToString()}。请补齐项目根 .env 或 CI Secrets"
        }
        logger.lifecycle("release 签名机密已完整配置")
    }
}
tasks.register<CheckSigningSecretsTask>("checkSigningSecrets") {
    group = "verification"
    description = "检查 release 签名机密（项目根 .env 或 ANDROID_KEYSTORE_* 环境变量）"
    envFilePath.set(layout.projectDirectory.file(".env").asFile.absolutePath)
    rootPath.set(layout.projectDirectory.asFile.absolutePath)
}

/** ktlint checking/format (rules read from .editorconfig, applied to all Kotlin source files in composeApp). */
fun ktlintTask(name: String, group: String, format: Boolean) =
    tasks.register<JavaExec>(name) {
        this.group = group
        classpath = ktlintConfig
        mainClass.set("com.pinterest.ktlint.Main")
        if (format) args("--format")
        args("--relative", "composeApp/src")
        // configuration cache: Parameters are fixed; no additional configuration is required.
        outputs.upToDateWhen { false }
    }

ktlintTask("ktlintCheck", "verification", format = false)
ktlintTask("ktlintFormat", "formatting", format = true)
