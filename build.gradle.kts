plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.summit"
version = "1.0.0"
description = "SummitChatEvents - A Spigot/Paper chat event plugin"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper API — compile-only (provided by the server at runtime)
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    processResources {
        // Inject project version into plugin.yml
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }

    // Disable the plain jar — shadowJar is the only output we want
    jar {
        enabled = false
    }

    shadowJar {
        archiveClassifier.set("")                // no "-all" suffix
        archiveBaseName.set("summitchatevents")  // → summitchatevents-1.0.0.jar
        minimize()
    }

    // Run shadowJar automatically when 'build' is invoked
    build {
        dependsOn(shadowJar)
    }
}
