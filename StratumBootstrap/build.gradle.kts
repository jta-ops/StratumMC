version = "2.1.0"

dependencies {
    compileOnly(project(":paper-api"))
    compileOnly("org.apache.logging.log4j:log4j-core:2.25.2")
}

tasks.jar {
    archiveBaseName = "StratumBootstrap"
}