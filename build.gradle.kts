plugins {
    java
}

group = "dev.chestlock"
version = "1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper 26.2 向け API でビルド。26.3 でも後方互換性により動作します。
    compileOnly("io.papermc.paper:paper-api:26.2.build.135-stable")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release = 25
}

tasks.processResources {
    filteringCharset = "UTF-8"
}

tasks.jar {
    archiveBaseName = "ChestLock"
}
