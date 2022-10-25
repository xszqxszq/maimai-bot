@file:Suppress("SpellCheckingInspection")

plugins {
    val kotlinVersion = "1.6.10"
    kotlin("jvm") version kotlinVersion
    kotlin("plugin.serialization") version kotlinVersion

    id("net.mamoe.mirai-console") version "2.13.0-RC2"
}

group = "xyz.xszq"
version = "1.3.2"
val korlibsVersion = "2.7.0"
val ktorVersion = "1.6.8"

repositories {
// maven("https://maven.aliyun.com/repository/public")
    mavenCentral()
}

dependencies {
    implementation("com.github.doyaaaaaken:kotlin-csv-jvm:1.3.0")
    implementation("com.github.houbb:opencc4j:1.7.2")
    implementation("com.soywiz.korlibs.korim:korim:$korlibsVersion")
    implementation("com.soywiz.korlibs.korio:korio:$korlibsVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-serialization:$ktorVersion")
    implementation("net.mamoe.yamlkt:yamlkt:0.10.2")
    implementation("xyz.xszq:mirai-multi-account:1.1.2")
}