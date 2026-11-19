import com.github.burrunan.s3cache.AwsS3BuildCache

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
    id("com.github.burrunan.s3-build-cache") version "1.9.11"
}

val remoteBuildCacheAccessKeyId: String? = System.getenv("S3_BUILD_CACHE_ACCESS_KEY_ID")
buildCache {
    remote<AwsS3BuildCache> {
        isEnabled = !remoteBuildCacheAccessKeyId.isNullOrBlank()
        isPush = System.getenv("CI") == "true"
        endpoint = System.getenv("S3_BUILD_CACHE_ENDPOINT").orEmpty().ifBlank { "s3.matsudamper.net" }
        forcePathStyle = true
        region = "us-east-1"
        bucket = "gradle-remote-cache"
        prefix = "matsudamper/kotpub/"
        // 自前の S3 互換サーバーなので AWS 固有のストレージクラスを指定しない
        isReducedRedundancy = false
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":core")
include(":client-ktor")
