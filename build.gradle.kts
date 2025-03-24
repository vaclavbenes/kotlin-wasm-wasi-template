@file:OptIn(ExperimentalWasmDsl::class)

import org.gradle.internal.os.OperatingSystem
import de.undercouch.gradle.tasks.download.Download
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsExec
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest
import org.jetbrains.kotlin.gradle.testing.internal.KotlinTestReport
import java.nio.file.Files
import java.util.Locale

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.undercouchDownload) apply false
}

repositories {
    mavenCentral()
}

kotlin {
    wasmWasi {
        nodejs()
        binaries.executable()
    }

    sourceSets {
        val wasmWasiTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

// Uncomment following block to turn off using the Exception Handling proposal.
// Note, with this option, the compiler will generate `unreachable` instruction instead of throw, 
// and a Wasm module will stop execution in this case.
//
// tasks.withType<org.jetbrains.kotlin.gradle.dsl.KotlinJsCompile>().configureEach {
//     compilerOptions.freeCompilerArgs.addAll(listOf("-Xwasm-use-traps-instead-of-exceptions"))
// }

// Uncomment following block to force using the final version of the Exception Handling proposal.
// Note, the new opcodes are not supported yet in WAMR and Node.js
//
// tasks.withType<org.jetbrains.kotlin.gradle.dsl.KotlinJsCompile>().configureEach {
//     compilerOptions.freeCompilerArgs.addAll(listOf("-Xwasm-use-new-exception-proposal"))
// }

enum class OsName { WINDOWS, MAC, LINUX, UNKNOWN }
enum class OsArch { X86_32, X86_64, ARM64, UNKNOWN }
data class OsType(val name: OsName, val arch: OsArch)

val currentOsType = run {
    val gradleOs = OperatingSystem.current()
    val osName = when {
        gradleOs.isMacOsX -> OsName.MAC
        gradleOs.isWindows -> OsName.WINDOWS
        gradleOs.isLinux -> OsName.LINUX
        else -> OsName.UNKNOWN
    }

    val osArch = when (providers.systemProperty("sun.arch.data.model").get()) {
        "32" -> OsArch.X86_32
        "64" -> when (providers.systemProperty("os.arch").get().lowercase(Locale.getDefault())) {
            "aarch64" -> OsArch.ARM64
            else -> OsArch.X86_64
        }
        else -> OsArch.UNKNOWN
    }

    OsType(osName, osArch)
}

// Deno tasks
val unzipDeno = run {
    val denoVersion = "1.38.3"
    val denoDirectory = "https://github.com/denoland/deno/releases/download/v$denoVersion"
    val denoSuffix = when (currentOsType) {
        OsType(OsName.LINUX, OsArch.X86_64) -> "x86_64-unknown-linux-gnu"
        OsType(OsName.MAC, OsArch.X86_64) -> "x86_64-apple-darwin"
        OsType(OsName.MAC, OsArch.ARM64) -> "aarch64-apple-darwin"
        else -> return@run null
    }
    val denoLocation = "$denoDirectory/deno-$denoSuffix.zip"

    val downloadedTools = File(layout.buildDirectory.asFile.get(), "tools")

    val downloadDeno = tasks.register("denoDownload", Download::class) {
        src(denoLocation)
        dest(File(downloadedTools, "deno-$denoVersion-$denoSuffix.zip"))
        overwrite(false)
    }

    tasks.register("denoUnzip", Copy::class) {
        dependsOn(downloadDeno)
        from(zipTree(downloadDeno.get().dest))
        val unpackedDir = File(downloadedTools, "deno-$denoVersion-$denoSuffix")
        into(unpackedDir)
    }
}

fun getDenoExecutableText(wasmFileName: String): String = """
import Context from "https://deno.land/std@0.201.0/wasi/snapshot_preview1.ts";

const context = new Context({
  args: Deno.args,
  env: Deno.env.toObject(),
});

const binary = await Deno.readFile("./$wasmFileName");
const module = await WebAssembly.compile(binary);
const wasmInstance = await WebAssembly.instantiate(module, {
  "wasi_snapshot_preview1": context.exports,
});

context.initialize(wasmInstance);
wasmInstance.exports.startUnitTests?.();
"""

fun Project.createDenoExecutableFile(
    taskName: String,
    wasmFileName: Provider<String>,
    outputDirectory: Provider<File>,
    resultFileName: String,
): TaskProvider<Task> = tasks.register(taskName, Task::class) {
    val denoMjs = outputDirectory.map { it.resolve(resultFileName) }
    inputs.property("wasmFileName", wasmFileName)
    outputs.file(denoMjs)

    doFirst {
        denoMjs.get().writeText(getDenoExecutableText(wasmFileName.get()))
    }
}

fun Project.createDenoExec(
    nodeMjsFile: RegularFileProperty,
    taskName: String,
    taskGroup: String?
): TaskProvider<Exec> {
    val denoFileName = "runUnitTestsDeno.mjs"

    val outputDirectory = nodeMjsFile.map { it.asFile.parentFile }
    val wasmFileName = nodeMjsFile.map { "${it.asFile.nameWithoutExtension}.wasm" }

    val denoFileTask = createDenoExecutableFile(
        taskName = "${taskName}CreateDenoFile",
        wasmFileName = wasmFileName,
        outputDirectory = outputDirectory,
        resultFileName = denoFileName
    )

    return tasks.register(taskName, Exec::class) {
        if (unzipDeno != null) {
            dependsOn(unzipDeno)
        }
        dependsOn(denoFileTask)

        taskGroup?.let {
            group = it
        }

        description = "Executes tests with Deno"

        val newArgs = mutableListOf<String>()

        executable = when (currentOsType.name) {
            OsName.WINDOWS -> "deno.exe"
            else -> unzipDeno?.let { File(unzipDeno.get().destinationDir, "deno").absolutePath } ?: "deno"
        }

        newArgs.add("run")
        newArgs.add("--allow-read")
        newArgs.add("--allow-env")

        newArgs.add(denoFileName)

        args(newArgs)
        doFirst {
            workingDir(outputDirectory)
        }
    }
}


tasks.withType<KotlinJsTest>().all {
    val denoExecTask = createDenoExec(
        inputFileProperty,
        name.replace("Node", "Deno"),
        "deno"
    )

    denoExecTask.configure {
        dependsOn (
            project.provider { this@all.taskDependencies }
        )
    }

    tasks.withType<KotlinTestReport> {
        dependsOn(denoExecTask)
    }
}

// WasmEdge tasks
val wasmEdgeVersion = "0.14.0"

val wasmEdgeInnerSuffix = when (currentOsType.name) {
    OsName.LINUX -> "Linux"
    OsName.MAC -> "Darwin"
    OsName.WINDOWS -> "Windows"
    else -> error("unsupported os type $currentOsType")
}

val unzipWasmEdge = run {
    val wasmEdgeDirectory = "https://github.com/WasmEdge/WasmEdge/releases/download/$wasmEdgeVersion"
    val wasmEdgeSuffix = when (currentOsType) {
        OsType(OsName.LINUX, OsArch.X86_64) -> "manylinux_2_28_x86_64.tar.gz"
        OsType(OsName.MAC, OsArch.X86_64) -> "darwin_x86_64.tar.gz"
        OsType(OsName.MAC, OsArch.ARM64) -> "darwin_arm64.tar.gz"
        OsType(OsName.WINDOWS, OsArch.X86_32),
        OsType(OsName.WINDOWS, OsArch.X86_64) -> "windows.zip"
        else -> error("unsupported os type $currentOsType")
    }

    val artifactName = "WasmEdge-$wasmEdgeVersion-$wasmEdgeSuffix"
    val wasmEdgeLocation = "$wasmEdgeDirectory/$artifactName"

    val downloadedTools = File(layout.buildDirectory.asFile.get(), "tools")

    val downloadWasmEdge = tasks.register("wasmEdgeDownload", Download::class) {
        src(wasmEdgeLocation)
        dest(File(downloadedTools, artifactName))
        overwrite(false)
    }

    tasks.register("wasmEdgeUnzip", Copy::class) {
        dependsOn(downloadWasmEdge)

        val archive = downloadWasmEdge.get().dest

        val subfolder = "WasmEdge-$wasmEdgeVersion-$wasmEdgeInnerSuffix"

        from(if (archive.extension == "zip") zipTree(archive) else tarTree(archive))

        val currentOsTypeForConfigurationCache = currentOsType.name

        val unzipDirectory = downloadedTools.resolve(subfolder)

        into(unzipDirectory)

        doLast {
            if (currentOsTypeForConfigurationCache !in setOf(OsName.MAC, OsName.LINUX)) return@doLast

            val libDirectory = unzipDirectory.resolve(subfolder).toPath()
                .resolve(if (currentOsTypeForConfigurationCache == OsName.MAC) "lib" else "lib64")

            val targets = if (currentOsTypeForConfigurationCache == OsName.MAC)
                listOf("libwasmedge.0.1.0.dylib", "libwasmedge.0.1.0.tbd")
            else listOf("libwasmedge.so.0.1.0")

            targets.forEach {
                val target = libDirectory.resolve(it)
                val firstLink = libDirectory.resolve(it.replace("0.1.0", "0")).also(Files::deleteIfExists)
                val secondLink = libDirectory.resolve(it.replace(".0.1.0", "")).also(Files::deleteIfExists)

                Files.createSymbolicLink(firstLink, target)
                Files.createSymbolicLink(secondLink, target)
            }
        }
    }
}

fun Project.createWasmEdgeExec(
    nodeMjsFile: RegularFileProperty,
    taskName: String,
    taskGroup: String?,
    startFunction: String
): TaskProvider<Exec> {
    val outputDirectory = nodeMjsFile.map { it.asFile.parentFile }
    val wasmFileName = nodeMjsFile.map { "${it.asFile.nameWithoutExtension}.wasm" }

    return tasks.register(taskName, Exec::class) {
        dependsOn(unzipWasmEdge)
        inputs.property("wasmFileName", wasmFileName)

        taskGroup?.let { group = it }

        description = "Executes tests with WasmEdge"

        val wasmEdgeDirectory = unzipWasmEdge.get().destinationDir.resolve("WasmEdge-$wasmEdgeVersion-$wasmEdgeInnerSuffix")

        executable = wasmEdgeDirectory.resolve("bin/wasmedge").absolutePath

        doFirst {
            val newArgs = mutableListOf<String>()

            newArgs.add("--enable-gc")
            newArgs.add("--enable-exception-handling")

            newArgs.add(wasmFileName.get())
            newArgs.add(startFunction)

            args(newArgs)
            workingDir(outputDirectory)
        }
    }
}

tasks.withType<KotlinJsTest>().all {
    val wasmEdgeRunTask = createWasmEdgeExec(
        inputFileProperty,
        name.replace("Node", "WasmEdge"),
        group,
        "startUnitTests"
    )

    wasmEdgeRunTask.configure {
        dependsOn (
            project.provider { this@all.taskDependencies }
        )
    }

    tasks.withType<KotlinTestReport> {
        dependsOn(wasmEdgeRunTask)
    }
}

tasks.withType<NodeJsExec>().all {
     val wasmEdgeRunTask = createWasmEdgeExec(
        inputFileProperty,
        name.replace("Node", "WasmEdge"),
        "wasmEdge",
        "dummy"
    )

    wasmEdgeRunTask.configure {
        dependsOn (
            project.provider { this@all.taskDependencies }
        )
    }
}

val wamrVersion = "2.2.0"
val wamrDirectoryName = "iwasm-gc-eh-$wamrVersion"

val unzipWAMR = run {
    val wamrDirectory = "https://github.com/bytecodealliance/wasm-micro-runtime/releases/download/WAMR-$wamrVersion"
    val wamrSuffix = when (currentOsType) {
        OsType(OsName.LINUX, OsArch.X86_64) -> "x86_64-ubuntu-20.04"
        OsType(OsName.MAC, OsArch.X86_64),
        OsType(OsName.MAC, OsArch.ARM64) -> "x86_64-macos-13"
        OsType(OsName.WINDOWS, OsArch.X86_32),
        OsType(OsName.WINDOWS, OsArch.X86_64) -> "x86_64-windows-latest"
        else -> error("unsupported os type $currentOsType")
    }

    val wamrArtifactiName = "$wamrDirectoryName-$wamrSuffix.tar.gz"
    val wamrLocation = "$wamrDirectory/$wamrArtifactiName"

    val downloadedTools = File(layout.buildDirectory.asFile.get(), "tools")

    val downloadWamr = tasks.register("wamrDownload", Download::class) {
        src(wamrLocation)
        dest(File(downloadedTools, wamrArtifactiName))
        overwrite(false)
    }

    tasks.register("wamrUnzip", Copy::class) {
        dependsOn(downloadWamr)
        from(tarTree(downloadWamr.get().dest))
        into(downloadedTools.resolve(wamrDirectoryName) )
    }
}


tasks.register<Exec>("runWamr") {
    group = "wamr"
    dependsOn(unzipWAMR)

    // Get the location of the wasm file from the build directory
    val wasmFile = project.layout.buildDirectory
        .file("compileSync/wasmWasi/main/productionExecutable/optimized/kotlin-wasm-wasi-example-wasm-wasi.wasm")

    doFirst {
        val wamrBinary = if (currentOsType.name == OsName.WINDOWS) {
            "${unzipWAMR.get().destinationDir}/iwasm.exe"
        } else {
            "${unzipWAMR.get().destinationDir}/iwasm"
        }

        val out = commandLine(
            wamrBinary,
            "--heap-size=${10.Mb}", // 10MB heap (adjust as needed)
            wasmFile.get().asFile.absolutePath
        )
        println(out)
    }
}

val Int.Bytes: Int get() = this
val Int.Kb: Int get() = this * 1024
val Int.Mb: Int get() = this * 1024 * 1024
val Int.Gb: Int get() = this * 1024 * 1024 * 1024
