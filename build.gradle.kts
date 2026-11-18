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
    // MariaDB（MySQL 互換）ドライバ。jar に同梱します。
    implementation("org.mariadb.jdbc:mariadb-java-client:3.4.1")
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
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // JDBC ドライバを jar に同梱する
    from({
        configurations.runtimeClasspath.get().filter { it.exists() }.map { if (it.isDirectory) it else zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/INDEX.LIST", "module-info.class")
}
