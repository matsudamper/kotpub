import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar

plugins {
    alias(libs.plugins.maven.publish)
    signing
}

mavenPublishing {
    configure(
        KotlinJvm(
            javadocJar = JavadocJar.Empty(),
            sourcesJar = SourcesJar.Sources(),
        ),
    )

    pom {
        name.set("kotpub activitypub")
        description.set("Kotlin/JVM 向けの ActivityPub 実装")
        inceptionYear.set("2025")
        url.set("https://github.com/matsudamper/kotpub")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("matsudamper")
                name.set("matsudamper")
                url.set("https://github.com/matsudamper/")
            }
        }
        scm {
            url.set("https://github.com/matsudamper/kotpub")
            connection.set("scm:git:git://github.com/matsudamper/kotpub.git")
            developerConnection.set("scm:git:ssh://git@github.com/matsudamper/kotpub.git")
        }
    }
}

signing {
    isRequired = false
}

tasks.matching { it.name == "publishToMavenCentral" }.configureEach {
    doFirst {
        val releaseVersion = version.toString()
        require(!releaseVersion.endsWith("SNAPSHOT")) {
            "Maven Central には SNAPSHOT を公開しない: $releaseVersion"
        }
        require(!releaseVersion.contains("-pr")) {
            "Maven Central には PR 用バージョンを公開しない: $releaseVersion"
        }
    }
}
