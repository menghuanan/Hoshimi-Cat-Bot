import org.gradle.internal.os.OperatingSystem
import java.io.ByteArrayOutputStream

plugins {
    val kotlinVersion = "2.0.0"
    kotlin("jvm") version kotlinVersion
    kotlin("plugin.serialization") version kotlinVersion

    // 独立应用程序插件
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "top.bilibili"
val releaseVersion = (findProperty("releaseVersion") as String?) ?: "1.8-SNAPSHOT"
version = releaseVersion

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

// 配置主类
application {
    mainClass.set("top.bilibili.MainKt")
    applicationDefaultJvmArgs = listOf("-Dapp.version=$releaseVersion")
}

// Java 版本
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Kotlin 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

    // HTTP 客户端
    implementation("io.ktor:ktor-client-okhttp:3.0.3") {
        exclude(group = "org.slf4j")
    }
    implementation("io.ktor:ktor-client-encoding:3.0.3") {
        exclude(group = "org.slf4j")
    }
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3") {
        exclude(group = "org.slf4j")
    }

    // WebSocket 客户端（用于 NapCat）
    implementation("io.ktor:ktor-client-websockets:3.0.3") {
        exclude(group = "org.slf4j")
    }
    implementation("io.ktor:ktor-client-content-negotiation:3.0.3") {
        exclude(group = "org.slf4j")
    }
    // WebUI foundation 只引入最小 Ktor 服务端依赖，负责本地管理页启动、静态资源和占位 API。
    implementation("io.ktor:ktor-server-core:3.0.3") {
        exclude(group = "org.slf4j")
    }
    implementation("io.ktor:ktor-server-cio:3.0.3") {
        exclude(group = "org.slf4j")
    }
    // WebUI 请求体上限交给 Ktor 官方 body-limit 插件，在读取 body 前就能拒绝超限请求。
    implementation("io.ktor:ktor-server-body-limit:3.0.3") {
        exclude(group = "org.slf4j")
    }
    implementation("io.ktor:ktor-server-content-negotiation:3.0.3") {
        exclude(group = "org.slf4j")
    }

    // JSON 序列化
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // 二维码生成
    implementation("com.google.zxing:javase:3.5.0")

    // Skiko 图片绘制 (Linux x64, Windows x64)
    // 使用 0.8.15 版本
    implementation("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.8.15")
    implementation("org.jetbrains.skiko:skiko-awt-runtime-linux-x64:0.8.15")

    // 日志系统
    implementation("ch.qos.logback:logback-classic:1.4.14")
    implementation("org.slf4j:slf4j-api:2.0.9")

    // YAML 配置解析
    implementation("com.charleskorn.kaml:kaml:0.61.0")

    // 测试
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:3.0.3") {
        exclude(group = "org.slf4j")
    }
}

tasks.test {
    useJUnitPlatform()
}

val webUiFrontendDir = layout.projectDirectory.dir("webui-frontend")
val webUiFrontendNodeModulesDir = webUiFrontendDir.dir("node_modules")
val bundledReactWebUiDir = layout.projectDirectory.dir("src/main/resources/webui/react")
val npmExecutableName = if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) "npm.cmd" else "npm"

// 前端依赖安装独立成任务，保证干净 checkout 只运行 Gradle 也能复现 React 构建。
val installWebUiFrontendDependencies = tasks.register<Exec>("installWebUiFrontendDependencies") {
    description = "Installs npm dependencies for the React WebUI frontend."
    group = "build"
    workingDir = webUiFrontendDir.asFile
    commandLine(npmExecutableName, "install")
    inputs.files(
        webUiFrontendDir.file("package.json"),
        webUiFrontendDir.file("package-lock.json"),
    )
    outputs.dir(webUiFrontendNodeModulesDir)
}

// React WebUI 构建保持在独立 npm 工程内，Gradle 只负责调用稳定入口并追踪源码输入。
val buildWebUiFrontend = tasks.register<Exec>("buildWebUiFrontend") {
    description = "Builds the React WebUI frontend into bundled static resources."
    group = "build"
    dependsOn(installWebUiFrontendDependencies)
    workingDir = webUiFrontendDir.asFile
    commandLine(npmExecutableName, "run", "build")
    inputs.files(
        fileTree(webUiFrontendDir) {
            include("src/**")
            include("package.json")
            include("package-lock.json")
            include("tsconfig*.json")
            include("vite.config.ts")
            include("eslint.config.js")
            exclude("node_modules/**")
            exclude("dist/**")
        }
    )
    outputs.dir(bundledReactWebUiDir)
}

// 打包资源前先生成 React 产物；Ktor 静态路由直接以 webui/react 作为运行时 WebUI shell。
tasks.processResources {
    dependsOn(buildWebUiFrontend)
}

val skiaNativeMemoryEvidenceTest by tasks.registering(org.gradle.api.tasks.testing.Test::class) {
    description = "Runs the Skia native-memory evidence test with JVM native memory tracking enabled"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("top.bilibili.core.resource.SkiaNativeMemoryEvidenceTest")
    }
    jvmArgs("-XX:NativeMemoryTracking=summary")
    systemProperty("skia.native.memory.evidence", "true")
    shouldRunAfter(tasks.test)
}
tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach {
    manifest {
        attributes("Implementation-Version" to project.version.toString())
    }
}

// Shadow JAR 配置 - 打包所有依赖
tasks.shadowJar {
    archiveBaseName.set("hoshimi-cat-bot")
    archiveClassifier.set("")
    archiveVersion.set(version.toString())

    manifest {
        attributes(
            "Main-Class" to "top.bilibili.MainKt",
            "Multi-Release" to "true"
        )
    }

    // 合并服务文件
    mergeServiceFiles()

    // 排除签名文件
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

val generatedDistributionScriptsDir = layout.buildDirectory.dir("generated/distribution-scripts")
val detectedRuntimeModulesFile = layout.buildDirectory.file("generated/runtime-modules/jlink-modules.txt")
val hostLinkedRuntimeImageDir = layout.buildDirectory.dir("release-platform/host-runtime-image")
val linuxLinkedRuntimeImageDir = layout.buildDirectory.dir("release-platform/linux-runtime-image")

// 统一解析当前构建机 JDK 工具路径，确保 jdeps/jlink 直接使用本次构建的 JDK 版本。
val hostOs = OperatingSystem.current()
val hostExecutableSuffix = if (hostOs.isWindows) ".exe" else ""
val configuredJavaHome = file(System.getenv("JAVA_HOME") ?: System.getProperty("java.home"))
val jdepsExecutable = configuredJavaHome.resolve("bin/jdeps$hostExecutableSuffix")
val jlinkExecutable = configuredJavaHome.resolve("bin/jlink$hostExecutableSuffix")
val linuxJdkHomeProvider = providers.gradleProperty("linuxJdkHome")
    .orElse(providers.environmentVariable("LINUX_JDK_HOME"))
val linuxRuntimeJdkHomeInput = providers.provider {
    if (hostOs.isLinux) configuredJavaHome.absolutePath else linuxJdkHomeProvider.orNull.orEmpty()
}

// 校验目标 JDK 根目录确实包含 jmods，避免把 JRE 或错误目录打进裸机发行包。
fun requireJdkJmods(jdkHome: File, targetDescription: String): File {
    val jmodsDir = jdkHome.resolve("jmods")
    require(jmodsDir.isDirectory) {
        "$targetDescription jmods directory not found: ${jmodsDir.absolutePath}. Please point to a full JDK 17."
    }
    return jmodsDir
}

// Windows 交叉打包 Linux 时只读取目标 JDK 的 jmods，并用 release 文件阻止误传 Windows/macOS JDK。
fun requireLinuxX64JdkHome(jdkHome: File): File {
    val releaseFile = jdkHome.resolve("release")
    require(releaseFile.isFile) {
        "Linux target JDK release file not found: ${releaseFile.absolutePath}. Please point linuxJdkHome to an extracted Linux x64 JDK 17 root."
    }

    val releaseText = releaseFile.readText(Charsets.UTF_8)
    require(releaseText.contains("""JAVA_VERSION="17""") || releaseText.contains("""JAVA_VERSION="17.""")) {
        "Linux target JDK must be JDK 17: ${jdkHome.absolutePath}."
    }
    require(releaseText.contains("""OS_NAME="Linux"""")) {
        "linuxJdkHome must point to a Linux JDK, but release metadata is not Linux: ${jdkHome.absolutePath}."
    }
    require(
        releaseText.contains("""OS_ARCH="x86_64"""") ||
            releaseText.contains("""OS_ARCH="amd64""")
    ) {
        "linuxJdkHome must point to a Linux x64 JDK: ${jdkHome.absolutePath}."
    }
    return jdkHome
}

// 基于主程序 class 文件与 fat jar 依赖图自动探测最小模块集合，并补齐 jdeps 无法静态识别的 TLS 模块。
val detectRuntimeModules = tasks.register("detectRuntimeModules") {
    dependsOn(tasks.shadowJar, tasks.classes)
    outputs.file(detectedRuntimeModulesFile)

    doLast {
        val shadowJarFile = tasks.shadowJar.get().archiveFile.get().asFile
        val mainClassesDir = layout.buildDirectory.dir("classes/kotlin/main").get().asFile
        val outputFile = detectedRuntimeModulesFile.get().asFile
        outputFile.parentFile.mkdirs()

        require(jdepsExecutable.isFile) {
            "jdeps executable not found: ${jdepsExecutable.absolutePath}. Please build with a full JDK 17."
        }

        val jdepsOutput = ByteArrayOutputStream()
        exec {
            executable = jdepsExecutable.absolutePath
            args(
                "--multi-release", "17",
                "--ignore-missing-deps",
                "--print-module-deps",
                "--class-path", shadowJarFile.absolutePath,
                mainClassesDir.absolutePath
            )
            standardOutput = jdepsOutput
        }

        val detectedModules = jdepsOutput.toString(Charsets.UTF_8.name())
            .trim()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // HTTPS/TLS 在运行期依赖 jdk.crypto.ec，二维码编码在精简 runtime 下还需要 jdk.charsets 补足 GB2312 支持。
        // 这两个模块都不是 jdeps 稳定可见的显式依赖，必须手动并入 jlink 运行时清单。
        val finalModules = (detectedModules + listOf("jdk.crypto.ec", "jdk.charsets"))
            .distinct()
            .sorted()
            .joinToString(",")

        outputFile.writeText(finalModules + System.lineSeparator(), Charsets.UTF_8)
        logger.lifecycle("Detected runtime modules for jlink: $finalModules")
    }
}

// 使用 jlink 构建当前平台精简运行时，供 Windows 裸机发行包内置使用，避免用户额外安装 Java。
val createHostLinkedRuntimeImage = tasks.register("createHostLinkedRuntimeImage") {
    dependsOn(detectRuntimeModules)
    inputs.file(detectedRuntimeModulesFile)
    inputs.property("targetJdkHome", configuredJavaHome.absolutePath)
    outputs.dir(hostLinkedRuntimeImageDir)

    doLast {
        val modules = detectedRuntimeModulesFile.get().asFile.readText(Charsets.UTF_8).trim()
        val runtimeOutputDir = hostLinkedRuntimeImageDir.get().asFile
        runtimeOutputDir.mkdirs()

        require(jlinkExecutable.isFile) {
            "jlink executable not found: ${jlinkExecutable.absolutePath}. Please build with a full JDK 17."
        }

        val jmodsDir = requireJdkJmods(configuredJavaHome, "Host JDK")

        delete(runtimeOutputDir)
        exec {
            executable = jlinkExecutable.absolutePath
            args(
                "--module-path", jmodsDir.absolutePath,
                "--add-modules", modules,
                "--strip-debug",
                "--no-header-files",
                "--no-man-pages",
                "--compress=2",
                "--output", runtimeOutputDir.absolutePath
            )
        }
    }
}

// 使用目标 Linux JDK 的 jmods 构建 Linux 精简运行时，允许 Windows 通过 linuxJdkHome 交叉产出 Linux tar.gz。
val createLinuxLinkedRuntimeImage = tasks.register("createLinuxLinkedRuntimeImage") {
    dependsOn(detectRuntimeModules)
    inputs.file(detectedRuntimeModulesFile)
    inputs.property("targetJdkHome", linuxRuntimeJdkHomeInput)
    outputs.dir(linuxLinkedRuntimeImageDir)

    doLast {
        val modules = detectedRuntimeModulesFile.get().asFile.readText(Charsets.UTF_8).trim()
        val runtimeOutputDir = linuxLinkedRuntimeImageDir.get().asFile
        runtimeOutputDir.mkdirs()

        require(jlinkExecutable.isFile) {
            "jlink executable not found: ${jlinkExecutable.absolutePath}. Please build with a full JDK 17."
        }

        val targetJdkHome = if (hostOs.isLinux) {
            configuredJavaHome
        } else {
            val configuredLinuxJdkHome = linuxJdkHomeProvider.orNull
            require(!configuredLinuxJdkHome.isNullOrBlank()) {
                "linuxReleaseDistTar on ${hostOs.name} requires -PlinuxJdkHome=<extracted Linux x64 JDK 17> or LINUX_JDK_HOME."
            }
            file(configuredLinuxJdkHome)
        }
        val jmodsDir = requireJdkJmods(requireLinuxX64JdkHome(targetJdkHome), "Linux target JDK")

        delete(runtimeOutputDir)
        exec {
            executable = jlinkExecutable.absolutePath
            args(
                "--module-path", jmodsDir.absolutePath,
                "--add-modules", modules,
                "--strip-debug",
                "--no-header-files",
                "--no-man-pages",
                "--compress=2",
                "--output", runtimeOutputDir.absolutePath
            )
        }
    }
}

val createDistributionStartScripts = tasks.register("createDistributionStartScripts") {
    val outputDir = generatedDistributionScriptsDir.get().asFile
    outputs.dir(outputDir)

    doLast {
        outputDir.mkdirs()

        outputDir.resolve("start.bat").writeText(
            """
            @echo off
            chcp 65001 >nul
            cd /d "%~dp0.."

            set JAVA_OPTS=-Xms64m -Xmx160m
            set JAVA_OPTS=%JAVA_OPTS% -XX:MinHeapFreeRatio=10
            set JAVA_OPTS=%JAVA_OPTS% -XX:MaxHeapFreeRatio=20
            set JAVA_OPTS=%JAVA_OPTS% -XX:G1PeriodicGCInterval=60000
            set JAVA_OPTS=%JAVA_OPTS% -XX:G1PeriodicGCSystemLoadThreshold=0
            set JAVA_OPTS=%JAVA_OPTS% -Dfile.encoding=UTF-8
            set JAVA_OPTS=%JAVA_OPTS% -Duser.timezone=Asia/Shanghai
            set JAVA_OPTS=%JAVA_OPTS% -Dskiko.renderApi=SOFTWARE
            set JAVA_OPTS=%JAVA_OPTS% -Dskiko.hardwareAcceleration=false
            set "JAVA_BIN=runtime\bin\java.exe"

            if not exist "%JAVA_BIN%" (
                echo ERROR: bundled runtime not found at "%JAVA_BIN%".
                echo Please re-download the release package or rebuild with jlink enabled.
                pause
                exit /b 1
            )

            "%JAVA_BIN%" %JAVA_OPTS% -jar lib\hoshimi-cat-bot-${version}.jar
            pause
            """.trimIndent()
        )

        outputDir.resolve("start.sh").writeText(
            """
            #!/bin/bash
            cd "${'$'}(dirname "${'$'}0")/.."

            if [ "${'$'}(uname -s)" = "Linux" ]; then
                EXISTING_LD_PRELOAD="${'$'}{LD_PRELOAD:-}"
                JEMALLOC_LIB=""

                case "${'$'}EXISTING_LD_PRELOAD" in
                    *libjemalloc.so.2*)
                        ;;
                    *)
                        find_jemalloc_lib() {
                            for candidate in \
                                /usr/lib/x86_64-linux-gnu/libjemalloc.so.2 \
                                /usr/lib/aarch64-linux-gnu/libjemalloc.so.2 \
                                /usr/lib64/libjemalloc.so.2 \
                                /usr/lib/libjemalloc.so.2
                            do
                                if [ -r "${'$'}candidate" ]; then
                                    echo "${'$'}candidate"
                                    return 0
                                fi
                            done

                            if command -v ldconfig >/dev/null 2>&1; then
                                ldconfig -p | awk '/libjemalloc\.so\.2/ { print ${'$'}NF; exit }'
                            fi
                        }

                        JEMALLOC_LIB="${'$'}(find_jemalloc_lib)"

                        if [ -z "${'$'}JEMALLOC_LIB" ] || [ ! -r "${'$'}JEMALLOC_LIB" ]; then
                            echo "libjemalloc.so.2 was not found."
                            if [ -t 0 ] && [ -t 1 ]; then
                                printf "Install jemalloc via the system package manager now? [y/N] "
                                read -r install_jemalloc_reply

                                case "${'$'}install_jemalloc_reply" in
                                    [yY]|[yY][eE][sS])
                                        if command -v apt-get >/dev/null 2>&1; then
                                            sudo apt-get update && sudo apt-get install -y libjemalloc2
                                        elif command -v dnf >/dev/null 2>&1; then
                                            sudo dnf install -y jemalloc
                                        elif command -v yum >/dev/null 2>&1; then
                                            sudo yum install -y jemalloc
                                        else
                                            echo "ERROR: No supported package manager found. Install jemalloc manually." >&2
                                            exit 1
                                        fi
                                        ;;
                                    *)
                                        echo "ERROR: jemalloc is required for Linux bare-metal startup. Install it manually and retry." >&2
                                        exit 1
                                        ;;
                                esac
                            else
                                echo "ERROR: libjemalloc.so.2 not found. Install jemalloc manually with your system package manager before starting hoshimi-cat-bot on Linux bare metal." >&2
                                exit 1
                            fi

                            JEMALLOC_LIB="${'$'}(find_jemalloc_lib)"
                            if [ -z "${'$'}JEMALLOC_LIB" ] || [ ! -r "${'$'}JEMALLOC_LIB" ]; then
                                echo "ERROR: libjemalloc.so.2 is still unavailable after installation. Install jemalloc manually and retry." >&2
                                exit 1
                            fi
                        fi

                        export LD_PRELOAD="${'$'}JEMALLOC_LIB"
                        if [ -n "${'$'}EXISTING_LD_PRELOAD" ]; then
                            export LD_PRELOAD="${'$'}JEMALLOC_LIB:${'$'}EXISTING_LD_PRELOAD"
                        fi
                        ;;
                esac

                MALLOC_CONF="${'$'}{MALLOC_CONF:-background_thread:true,dirty_decay_ms:2000,muzzy_decay_ms:2000,narenas:1,tcache:false}"
                export MALLOC_CONF
            fi

            JAVA_OPTS="-Xms64m -Xmx160m"
            JAVA_OPTS="${'$'}JAVA_OPTS -XX:MinHeapFreeRatio=10"
            JAVA_OPTS="${'$'}JAVA_OPTS -XX:MaxHeapFreeRatio=20"
            JAVA_OPTS="${'$'}JAVA_OPTS -XX:G1PeriodicGCInterval=60000"
            JAVA_OPTS="${'$'}JAVA_OPTS -XX:G1PeriodicGCSystemLoadThreshold=0"
            JAVA_OPTS="${'$'}JAVA_OPTS -Dfile.encoding=UTF-8"
            JAVA_OPTS="${'$'}JAVA_OPTS -Duser.timezone=Asia/Shanghai"
            JAVA_OPTS="${'$'}JAVA_OPTS -Dskiko.renderApi=SOFTWARE"
            JAVA_OPTS="${'$'}JAVA_OPTS -Dskiko.hardwareAcceleration=false"
            JAVA_BIN="./runtime/bin/java"

            if [ ! -x "${'$'}JAVA_BIN" ]; then
                echo "ERROR: bundled runtime not found at ${'$'}JAVA_BIN."
                echo "Please re-download the release package or rebuild with jlink enabled." >&2
                exit 1
            fi

            "${'$'}JAVA_BIN" ${'$'}JAVA_OPTS -jar lib/hoshimi-cat-bot-${version}.jar
            """.trimIndent()
        )

        outputDir.resolve("start.sh").setExecutable(true)
    }
}

val sharedReleaseContentsDir = layout.buildDirectory.dir("release-platform/shared")

// 平台发行包共享同一份 fat jar 与资源，平台专属 runtime 由各自发行包任务单独写入。
val stageSharedReleaseContents = tasks.register<Sync>("stageSharedReleaseContents") {
    dependsOn(tasks.shadowJar)
    into(sharedReleaseContentsDir)

    from(tasks.shadowJar) {
        into("lib")
    }
    from("src/main/resources") {
        into("resources")
        exclude("logback")
    }
}

// Windows 发布资产只暴露 Windows 启动入口，避免用户在发行包中误用 Linux 或 Gradle 默认脚本。
val windowsReleaseDistZip = tasks.register<Zip>("windowsReleaseDistZip") {
    group = "distribution"
    description = "Builds the Windows x64 release archive with the packaged start.bat entrypoint."
    dependsOn(stageSharedReleaseContents, createDistributionStartScripts, createHostLinkedRuntimeImage)
    onlyIf { hostOs.isWindows }

    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set("hoshimi-cat-bot-windows-x64-v${project.version}.zip")

    from(sharedReleaseContentsDir)
    // 将 jlink 生成的 Windows 精简运行时写入 runtime 目录，启动脚本仅依赖此路径。
    from(createHostLinkedRuntimeImage) {
        into("runtime")
    }
    from(createDistributionStartScripts) {
        include("start.bat")
        into("bin")
    }
}

// Linux 发布资产只暴露 Linux 启动入口；Windows 交叉打包时由 linuxJdkHome 提供目标 jmods。
val linuxReleaseDistTar = tasks.register<Tar>("linuxReleaseDistTar") {
    group = "distribution"
    description = "Builds the Linux x64 release archive with the packaged start.sh entrypoint."
    dependsOn(stageSharedReleaseContents, createDistributionStartScripts, createLinuxLinkedRuntimeImage)

    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set("hoshimi-cat-bot-linux-x64-v${project.version}.tar.gz")
    compression = Compression.GZIP

    from(sharedReleaseContentsDir)
    // 将 Linux 精简运行时写入 runtime 目录，保证 Windows 产出的 tar.gz 仍可在 Linux 裸机启动。
    from(createLinuxLinkedRuntimeImage) {
        into("runtime")
        // Windows 文件系统不保留 Linux 可执行位，tar 内必须显式修正 runtime 启动入口权限。
        eachFile {
            if (path.startsWith("runtime/bin/") || path == "runtime/lib/jexec" || path == "runtime/lib/jspawnhelper") {
                mode = 0b111101101 // 755
            }
        }
    }
    from(createDistributionStartScripts) {
        include("start.sh")
        into("bin")
        fileMode = 0b111101101 // 755
    }
}
// Distribution 配置
distributions {
    main {
        contents {
            from(tasks.shadowJar) {
                into("lib")
            }
            from("src/main/resources") {
                into("resources")
                exclude("logback")
            }
            from(createDistributionStartScripts) {
                into("bin")
                fileMode = 0b111101101 // 755
            }
        }
    }
}

// 设置 distribution 任务的重复文件处理策略
tasks.withType<Tar> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<Zip> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// 修复 startScripts 和 shadowJar 的依赖关系
tasks.named("startScripts") {
    dependsOn("shadowJar")
}

// 修复 startShadowScripts 和 jar 的依赖关系
tasks.named("startShadowScripts") {
    dependsOn("jar", "shadowJar")
}

tasks.named("distTar") {
    dependsOn(createDistributionStartScripts)
}

tasks.named("distZip") {
    dependsOn(createDistributionStartScripts)
}
