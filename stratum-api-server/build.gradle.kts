plugins {
    application
}

application {
    mainClass.set("mc.stratum.api.StratumApiServer")
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    implementation("com.google.code.gson:gson:2.13.2")
}

repositories {
    mavenCentral()
}

tasks.jar {
    manifest { attributes("Main-Class" to "mc.stratum.api.StratumApiServer") }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
