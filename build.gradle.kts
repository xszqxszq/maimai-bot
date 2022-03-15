@file:Suppress("SpellCheckingInspection")

plugins {
    val kotlinVersion = "1.6.10"
    kotlin("jvm") version kotlinVersion
    kotlin("plugin.serialization") version kotlinVersion

    id("net.mamoe.mirai-console") version "2.10.0"
}

group = "xyz.xszq"
version = "1.2"
val korlibsVersion = "2.5.2"
val ktorVersion = "1.6.7"

repositories {
    maven("https://maven.aliyun.com/repository/public")
    mavenCentral()
}

dependencies {
    implementation("com.github.doyaaaaaken:kotlin-csv-jvm:1.2.0")
    implementation("com.soywiz.korlibs.korim:korim:$korlibsVersion")
    implementation("com.soywiz.korlibs.korio:korio:$korlibsVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-serialization:$ktorVersion")
}