dependencies {
    compileOnly(project(":paper-api"))
}

tasks.jar {
    archiveBaseName = "StratumBootstrap"
}