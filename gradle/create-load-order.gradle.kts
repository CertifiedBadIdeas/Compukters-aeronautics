/* Copyright 2026 Vsevolod Petrov (lazyhat). Licensed under the Apache License, Version 2.0. */

import com.electronwill.nightconfig.core.file.CommentedFileConfig

buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("com.electronwill.night-config:toml:3.8.3") }
}

// These pinned releases declare Create with ordering=NONE. Their constructors can initialize
// different Create content classes concurrently, corrupting its non-thread-safe Registrate maps.
// Use NeoForge's supported dependency ordering configuration; leave JARs and parallelism intact.
val createDependents = listOf("aeronautics", "simulated", "offroad")
fun prepareCreateLoadOrder(file: File) {
    file.parentFile.mkdirs()
    CommentedFileConfig.builder(file).preserveInsertionOrder().sync().build().use { config ->
        if (file.isFile) config.load()
        val updates = createDependents.associateWith { id ->
            val existing = when (val value = config.get<Any?>("dependencyOverrides.$id")) {
                null -> emptyList()
                is String -> listOf(value)
                is List<*> -> value.map { check(it is String) { "Invalid dependency overrides for $id" }; it }
                else -> error("Invalid dependency overrides for $id")
            }
            check("-create" !in existing) { "Conflicting -create dependency override for $id in $file" }
            if ("+create" in existing) existing else existing + "+create"
        }
        var changed = false
        updates.forEach { (id, values) ->
            val key = "dependencyOverrides.$id"
            if (config.get<Any?>(key) != values) {
                config.set<Any>(key, values)
                changed = true
            }
        }
        if (changed) config.save()
    }
}

val verifyCreateLoadOrder = tasks.register("verifyCreateLoadOrder") {
    group = "verification"
    description = "Verify Create dependency ordering and preservation of existing NeoForge configuration."
    doLast {
        val directory = layout.buildDirectory.dir("verification/create-load-order").get().asFile
        directory.mkdirs()
        val fixtures = listOf(
            "missing" to "",
            "empty" to "",
            "inline" to "maxThreads = 8\nversionCheck = false\ndependencyOverrides = { aeronautics = '+other', custom = ['-old'] }\n",
            "table" to "# user setting\nmaxThreads = 8\n[dependencyOverrides]\naeronautics = ['+other', '+create']\ncustom = ['-old']\n",
        )
        fixtures.forEach { (name, content) ->
            val file = directory.resolve("$name.toml")
            if (name == "missing") file.delete() else file.writeText(content)
            prepareCreateLoadOrder(file)
            val first = file.readText()
            prepareCreateLoadOrder(file)
            check(file.readText() == first) { "Load-order preparation is not idempotent: $name" }
            CommentedFileConfig.of(file).use { config ->
                config.load()
                createDependents.forEach { id ->
                    check("+create" in config.get<List<String>>("dependencyOverrides.$id"))
                }
                if (name in listOf("inline", "table")) {
                    check(config.get<Int>("maxThreads") == 8)
                    check(config.get<List<String>>("dependencyOverrides.custom") == listOf("-old"))
                    check(config.get<List<String>>("dependencyOverrides.aeronautics") == listOf("+other", "+create"))
                }
                if (name == "inline") check(config.get<Boolean>("versionCheck") == false)
                if (name == "table") check(config.getComment("maxThreads") == " user setting")
            }
        }
        val conflict = directory.resolve("conflict.toml")
        val original = "maxThreads = 8\n[dependencyOverrides]\nsimulated = ['-create']\n"
        conflict.writeText(original)
        check(runCatching { prepareCreateLoadOrder(conflict) }.exceptionOrNull() is IllegalStateException)
        check(conflict.readText() == original) { "Conflicting user configuration was changed" }
        logger.lifecycle("Verified Create load order: missing/empty/inline/table configs, idempotence, user settings and conflict preservation.")
    }
}
tasks.named("check") { dependsOn(verifyCreateLoadOrder) }
listOf("client", "server", "gameTestServer").forEach { environment ->
    val prepare = tasks.register("prepare${environment.replaceFirstChar(Char::uppercaseChar)}CreateLoadOrder") {
        description = "Load pinned Create addons after Create in the $environment run."
        // Runtime configuration is also written by NeoForge and can be edited by the user;
        // merge the required rules on every launch, retaining all unrelated settings/overrides.
        doLast { prepareCreateLoadOrder(file("run/$environment/config/fml.toml")) }
    }
    tasks.matching { it.name == "run${environment.replaceFirstChar(Char::uppercaseChar)}" }.configureEach { dependsOn(prepare) }
}
