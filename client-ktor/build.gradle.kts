plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
    alias(libs.plugins.vanniktech.maven.publish)
}

dependencies {
    api(project(":core"))

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.opentelemetry.ktor)

    testImplementation(libs.kotlin.test)
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.cio)
}

kotlin {
    jvmToolchain(25)
}

java {
    withSourcesJar()
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/matsudamper/kotpub")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
        }
        maven {
            name = "MatsudamperMaven"
            url = uri("https://maven.matsudamper.net/releases")
            credentials {
                username = providers.environmentVariable("MAVEN_MATSUDAMPER_USERNAME").orNull
                password = providers.environmentVariable("MAVEN_MATSUDAMPER_TOKEN").orNull
            }
        }
    }
}

mavenPublishing {
    coordinates(artifactId = "client-ktor")
    pom {
        name.set("kotpub")
        description.set("Ktor CIO の ActivityPubHttpClient")
        url.set("https://github.com/matsudamper/kotpub")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("matsudamper")
                name.set("matsudamper")
                url.set("https://github.com/matsudamper")
            }
        }
        scm {
            url.set("https://github.com/matsudamper/kotpub")
            connection.set("scm:git:https://github.com/matsudamper/kotpub.git")
            developerConnection.set("scm:git:ssh://git@github.com/matsudamper/kotpub.git")
        }
    }
}
