/*
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.util.Properties
import java.util.zip.ZipFile

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("dev.architectury.loom")
    id("architectury-plugin")
    id("ru.lazyhat.compukters.addon")
    id("org.jmailen.kotlinter")
}
group = "ru.lazyhat.compukters"
version = providers.gradleProperty("addonVersion").get()
base { archivesName.set("compukters-aeronautics-1.21.1-neoforge") }
val sdk = Properties().apply {
    file(gradle.extra["compuktersSdkDirectory"].toString()).resolve("sdk.properties").inputStream().use(::load)
}
val sdkVersion = sdk.getProperty("sdkVersion")
val targetLine = sdk.getProperty("modVersion").split('.').take(2).joinToString(".")
base { archivesName.set("compukters-aeronautics-1.21.1-neoforge-$targetLine") }
kotlin { jvmToolchain(21) }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
architectury { minecraft = "1.21.1"; platformSetupLoomIde(); neoForge() }
repositories {
    mavenCentral()
    maven("https://maven.architectury.dev/")
    maven("https://maven.fabricmc.net/")
    maven("https://maven.neoforged.net/releases/")
    maven("https://api.modrinth.com/maven")
    maven("https://maven.createmod.net")
    maven("https://maven.ithundxr.dev/snapshots")
}
apply(from = "gradle/sable-libraries.gradle.kts")
apply(from = "gradle/aeronautics-libraries.gradle.kts")
apply(from = "gradle/workspace-sdk.gradle.kts")
compuktersAddon { register("aeronautics", "1.0") }

val gameTest by sourceSets.creating
kotlin.target.compilations.named(gameTest.name) { associateWith(kotlin.target.compilations.getByName("main")) }
configurations[gameTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[gameTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
gameTest.compileClasspath += sourceSets.main.get().compileClasspath + sourceSets.main.get().output
gameTest.runtimeClasspath += sourceSets.main.get().runtimeClasspath + sourceSets.main.get().output
dependencies {
    minecraft("net.minecraft:minecraft:1.21.1")
    mappings(loom.officialMojangMappings())
    neoForge("net.neoforged:neoforge:21.1.252")
    modImplementation("com.simibubi.create:create-1.21.1:6.0.10-280:slim") { isTransitive = false }
    modImplementation("net.createmod.ponder:ponder-neoforge:1.0.82+mc1.21.1")
    modCompileOnly("dev.engine-room.flywheel:flywheel-neoforge-api-1.21.1:1.0.6")
    modRuntimeOnly("dev.engine-room.flywheel:flywheel-neoforge-1.21.1:1.0.6")
    modImplementation("com.tterrag.registrate:Registrate:MC1.21-1.3.0+67")
    modImplementation(files(configurations.named("aeronauticsNestedMods")))
    modImplementation("maven.modrinth:create-aeronautics:44pLdPGg") { isTransitive = false }
    modImplementation("maven.modrinth:sable:fg9dTRz9") { isTransitive = false }
    compileOnly(files(configurations.named("sableCompanion")))
    modRuntimeOnly(files(configurations.named("sableNestedMods")))
    forgeRuntimeLibrary(files(configurations.named("sableRuntimeLibraries")))
    compileOnly("ru.lazyhat.compukters:compukters-addon-api:$sdkVersion")
    compileOnly("ru.lazyhat.compukters:compukters-addon-neoforge-1.21.1:$sdkVersion")
    val workspace = file(providers.gradleProperty("compuktersWorkspace").get())
    val development = files(workspace.resolve("modules/minecraft/v1_21_1/v1_21_1-neoforge/build/devlibs/compukters-development-mod.jar"))
        .builtBy(gradle.includedBuild("Compukters").task(":v1_21_1-neoforge:developmentModJar"))
    runtimeOnly(development)
    add(gameTest.implementationConfigurationName, development)
    listOf("org.jetbrains.kotlin:kotlin-stdlib:2.4.10", "io.github.oshai:kotlin-logging-jvm:8.0.4",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0", "org.tukaani:xz:1.12"
    ).forEach { forgeRuntimeLibrary(it) { isTransitive = false } }
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
loom {
    mods { maybeCreate("compukters_aeronautics").sourceSet("main") }
    runs {
        named("client") { runDir("run/client") }
        named("server") { runDir("run/server") }
        register("gameTestServer") {
            server(); source(gameTest); environment("gametestserver"); forgeTemplate("gameTestServer")
            runDir("run/gameTestServer")
            property("neoforge.enabledGameTestNamespaces", "minecraft")
            mods { maybeCreate("compukters_aeronautics").apply { sourceSet("main"); sourceSet(gameTest.name) } }
        }
    }
}
tasks.test { useJUnitPlatform() }
val modBuildVersion = sdk.getProperty("modBuildVersion")
val upperBound = sdk.getProperty("modVersion").split('.').let { "${it[0]}.${it[1].toInt() + 1}.0" }
tasks.processResources {
    inputs.property("compuktersRange", "[$modBuildVersion,$upperBound)")
    inputs.property("addonVersion", project.version)
    filesMatching("META-INF/neoforge.mods.toml") { expand("addon_version" to project.version, "compukters_range" to "[$modBuildVersion,$upperBound)") }
}
tasks.jar {
    archiveClassifier.set("dev")
    from(files("LICENSE.md", "NOTICE")) { into("META-INF") }
}
tasks.register<Jar>("developmentModJar") {
    manifest.attributes["Fabric-Loom-Remap"] = "false"
    archiveFileName.set("compukters-aeronautics-development-mod.jar")
    destinationDirectory.set(layout.buildDirectory.dir("devlibs"))
    from(sourceSets.main.get().output)
}
tasks.register("verifyAddonJar") {
    val archive = tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar")
    dependsOn(archive)
    inputs.file(archive.flatMap { it.archiveFile })
    doLast {
        ZipFile(archive.get().archiveFile.get().asFile).use { zip ->
            check(zip.getEntry("META-INF/compukters/addons/aeronautics.cagb") != null)
            check(zip.getEntry("compukters_aeronautics.mixins.json") != null)
            check(zip.getEntry("META-INF/LICENSE.md") != null && zip.getEntry("META-INF/NOTICE") != null)
            check(zip.getEntry("ru/lazyhat/compukters/integration/aeronautics/mixin/SwivelBearingMixin.class") != null)
            check(zip.entries().asSequence().none { it.name.startsWith("net/minecraft/") || it.name.startsWith("dev/simulated_team/") })
        }
    }
}
tasks.named("check") { dependsOn(gameTest.classesTaskName, "verifyAddonJar") }
tasks.configureEach { if (name == "runGameTestServer") dependsOn(gameTest.classesTaskName) }

apply(from = "gradle/create-load-order.gradle.kts")

tasks.withType<org.jmailen.gradle.kotlinter.tasks.ConfigurableKtLintTask>().configureEach {
    exclude { it.file.path.contains("build/generated") }
}
