plugins {
    application
}

application {
    mainClass.set("mc.stratum.launcher.StratumLauncher")
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

tasks.jar {
    manifest { attributes("Main-Class" to "mc.stratum.launcher.StratumLauncher") }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
