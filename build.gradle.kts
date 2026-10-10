/*
 * HLE 移植（地区/歌名/歌词）—— 独立 AM++ 插件工程。
 *
 * 打包链：Kotlin 编译 → JAR → D8 → code.jar(classes.dex) → plugin.zip
 * 与 examples/basic-plugin 的 Java 版本等价，只把编译语言换成 Kotlin，并把
 * kotlin-stdlib 作为 D8 的程序输入一起打包（宿主不保证提供 Kotlin 运行时）。
 */
plugins {
    kotlin("jvm") version "2.4.10"
}

val sdk = providers.gradleProperty("amppSdk").orElse("lib/ampp-plugin-api-v1.jar")
val androidSdk = providers.gradleProperty("androidSdk")
    .orElse(providers.environmentVariable("ANDROID_HOME"))
val androidJar = listOf("android-37.0", "android-37")
    .map { file("${androidSdk.get()}/platforms/$it/android.jar") }
    .firstOrNull { it.isFile }
    ?: error("Install Android platform 37 under \$ANDROID_HOME/platforms")
val d8Jar = listOf("37.0.0", "36.0.0")
    .map { file("${androidSdk.get()}/build-tools/$it/lib/d8.jar") }
    .firstOrNull { it.isFile }
    ?: error("Install Android build-tools (with lib/d8.jar) under \$ANDROID_HOME/build-tools")

dependencies {
    // SDK 与 android.jar 只用于编译，绝不打进插件。
    compileOnly(files(sdk.get(), androidJar))
    // Kotlin 运行时：随插件一起 D8 进 code.jar（见 dex 任务的 programInputs）。
    implementation(kotlin("stdlib"))
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // Apple Music 7.0.0-beta 的宿主运行在 ART 上，Java 8 字节码由 D8 desugar。
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

val dexDir = layout.buildDirectory.dir("dex")
val dex = tasks.register<JavaExec>("dex") {
    dependsOn(tasks.jar)
    classpath = files(d8Jar)
    mainClass.set("com.android.tools.r8.D8")
    val outDir = dexDir.get().asFile
    doFirst {
        outDir.mkdirs()
        val programInputs = listOf(tasks.jar.get().archiveFile.get().asFile) +
            configurations.runtimeClasspath.get().files.toList()
        setArgs(
            listOf(
                "--release",
                "--min-api", "26",
                "--lib", androidJar.absolutePath,
                // SDK 是 classpath：绝不能变成程序输入，否则插件会重复携带 SDK 类。
                "--classpath", file(sdk.get()).absolutePath,
                "--output", outDir.absolutePath,
            ) + programInputs.map { it.absolutePath },
        )
    }
}

val codeJar = tasks.register<Zip>("codeJar") {
    dependsOn(dex)
    from(dexDir) { include("classes*.dex") }
    archiveFileName.set("code.jar")
    destinationDirectory.set(layout.buildDirectory.dir("plugin-code"))
}

tasks.register<Zip>("pluginZip") {
    dependsOn(codeJar)
    from(codeJar.flatMap { it.archiveFile })
    from("plugin.json")
    from("assets") { into("assets") }
    archiveFileName.set("hle-in-ampp.zip")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
}

tasks.register("printSdk") {
    doLast {
        println("sdk=${file(sdk.get()).absolutePath}")
        println("androidJar=${androidJar.absolutePath}")
        println("d8=${d8Jar.absolutePath}")
    }
}
