rootProject.name = "kotpub"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// JDK をビルド側で用意する。ツールチェインが手元に無ければ Gradle が取ってくる
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":activitypub")
