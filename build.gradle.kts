plugins {
    java
}

group = "com.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.jar {
    from("src/main/resources") {
        include("**/*")
    }
    archiveBaseName.set("VibeCraft")
    archiveVersion.set(version.toString())
    destinationDirectory.set(file("build/libs"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
