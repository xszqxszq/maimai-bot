@file:Suppress("SpellCheckingInspection")

plugins {
    val kotlinVersion = "1.6.10"
    kotlin("jvm") version kotlinVersion
    kotlin("plugin.serialization") version kotlinVersion

    id("net.mamoe.mirai-console") version "2.10.0"
}

group = "xyz.xszq"
version = "1.0"

repositories {
    maven("https://maven.aliyun.com/repository/public")
    mavenCentral()
}

dependencies {
    implementation("com.github.doyaaaaaken:kotlin-csv-jvm:1.2.0")
    implementation("com.soywiz.korlibs.korim:korim:2.5.2")
    implementation("com.soywiz.korlibs.korio:korio:2.5.2")
    implementation("io.ktor:ktor-client-core:1.6.7")
    implementation("io.ktor:ktor-client-cio:1.6.7")
    implementation("io.ktor:ktor-client-serialization:1.6.7")
}