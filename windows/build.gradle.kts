import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
}

val appVersion = "1.0.0"

kotlin { jvmToolchain(17) }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("uk.co.caprica:vlcj:4.8.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
}

// version visible to the app
val genDir = layout.buildDirectory.dir("generated/version")
val genVersion by tasks.registering {
    outputs.dir(genDir)
    doLast {
        val f = genDir.get().file("net/family/multiki/BuildInfo.kt").asFile
        f.parentFile.mkdirs()
        f.writeText("package net.family.multiki\n\nobject BuildInfo { const val VERSION = \"$appVersion\" }\n")
    }
}
kotlin.sourceSets["main"].kotlin.srcDir(genDir)
tasks.named("compileKotlin") { dependsOn(genVersion) }

compose.desktop {
    application {
        mainClass = "net.family.multiki.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Xmx768m")
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "ArhivVeshchaniya"
            packageVersion = appVersion
            // jpackage reads these in the system code page: Latin only
            description = "Arhiv Veshchaniya"
            vendor = "Arhiv Veshchaniya"
            includeAllModules = true
            // bundled VLC (libvlc.dll, libvlccore.dll, plugins) is put here by the build workflow
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            windows {
                menuGroup = "Arhiv Veshchaniya"
                shortcut = true
                menu = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "90a5f699-f0cf-4672-9409-078acd422e8c"
                iconFile.set(project.file("icon.ico"))
            }
        }
    }
}
