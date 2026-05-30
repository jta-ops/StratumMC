Stratum [![Version](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Fartifactory.papermc.io%2Fartifactory%2Funiverse%2Fio%2Fpapermc%2Fpaper%2Fpaper-api%2Fmaven-metadata.xml&strategy=highestVersion&filter=26.1*&label=version&color=%23344ceb
)](https://stratum.mc/downloads)
[![Stratum Build Status](https://img.shields.io/github/actions/workflow/status/StratumMC/Stratum/build.yml?branch=main)](https://github.com/StratumMC/Stratum/actions)
[![Discord](https://img.shields.io/discord/289587909051416579.svg?label=&logo=discord&logoColor=ffffff&color=7389D8&labelColor=6A7EC2)](https://stratum.mc/discord)
[![GitHub Sponsors](https://img.shields.io/github/sponsors/StratumMC?label=GitHub%20Sponsors)](https://github.com/sponsors/StratumMC)
[![Open Collective](https://img.shields.io/opencollective/all/stratummc?label=OpenCollective%20Sponsors)](https://opencollective.com/stratummc)
===========

The most widely used, high-performance Minecraft server that aims to fix gameplay and mechanics inconsistencies.


**Support and Project Discussion:**
- [Our forums](https://stratum.mc/forums) or [Discord](https://stratum.mc/discord)

How To (Server Admins)
------
Paperclip is a jar file that you can download and run just like a normal jar file.

Download Stratum from our [downloads page](https://stratum.mc/downloads).

Run the Paperclip jar directly from your server. Just like old times

* Documentation on using Stratum: [docs.stratum.mc](https://docs.stratum.mc)
* For a sneak peek at upcoming features, [see here](https://github.com/StratumMC/Stratum/projects)

How To (Plugin Developers)
------
* See our API [here](stratum-api)
* See upcoming, pending, and recently added API [here](https://github.com/orgs/StratumMC/projects/2/views/4)
* Stratum API javadocs here: [stratum.mc/javadocs](https://stratum.mc/javadocs/)
#### Repository (for stratum-api)
See [the docs](https://docs.stratum.mc/stratum/dev/project-setup/#adding-stratum-as-a-dependency) for more details.
##### Gradle
```kotlin
repositories {
    maven {
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}
```
##### Maven

```xml
<repository>
    <id>papermc</id>
    <url>https://repo.papermc.io/repository/maven-public/</url>
</repository>
```

```xml
<dependency>
    <groupId>io.papermc.paper</groupId>
    <artifactId>paper-api</artifactId>
    <version>[26.1.2.build,)</version>
    <scope>provided</scope>
</dependency>
```

How To (Compiling Jar From Source)
------
To compile Stratum, you need JDK 25 and an internet connection.

Clone this repo, run `./gradlew applyPatches`, then `./gradlew createPaperclipJar` from your terminal. You can find the compiled jar in the `stratum-server/build/libs` directory.

To get a full list of tasks, run `./gradlew tasks`.

How To (Pull Request)
------
See [Contributing](CONTRIBUTING.md)

Old Versions (1.21.3 and below)
------
For branches of versions 1.8-1.21.3, please see our [archive repository](https://github.com/StratumMC/Stratum-archive).

Support Us
------
First of all, thank you for considering helping out, we really appreciate that!

StratumMC has various recurring expenses, mostly related to infrastructure. Stratum uses [Open Collective](https://opencollective.com/) via the [Open Source Collective fiscal host](https://opencollective.com/opensource) to manage expenses. Open Collective allows us to be extremely transparent, so you can always see how your donations are used. You can read more about financially supporting StratumMC [on our website](https://stratum.mc/sponsors).

You can find our collective [here](https://opencollective.com/stratummc), or you can donate via GitHub Sponsors [here](https://github.com/sponsors/StratumMC), which will also go towards the collective.

Special Thanks To:
-------------

[![YourKit-Logo](https://www.yourkit.com/images/yklogo.png)](https://www.yourkit.com/)

[YourKit](https://www.yourkit.com/), makers of the outstanding java profiler, support open source projects of all kinds with their full featured [Java](https://www.yourkit.com/java/profiler) and [.NET](https://www.yourkit.com/.net/profiler) application profilers. We thank them for granting Stratum an OSS license so that we can make our software the best it can be.

[<img src="https://user-images.githubusercontent.com/21148213/121807008-8ffc6700-cc52-11eb-96a7-2f6f260f8fda.png" alt="" width="150">](https://www.jetbrains.com)

[JetBrains](https://www.jetbrains.com/), creators of the IntelliJ IDEA, supports Stratum with one of their [Open Source Licenses](https://www.jetbrains.com/opensource/). IntelliJ IDEA is the recommended IDE for working with Stratum, and most of the Stratum team uses it.

All our sponsors!  
[![Sponsor Image](https://raw.githubusercontent.com/StratumMC/stratum.mc/data/sponsors.png)](https://stratum.mc/sponsors)