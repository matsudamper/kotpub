import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
}

// version catalog のアクセサは allprojects の中からは引けないので、ここで取り出しておく
val ktlintVersion = libs.versions.ktlint.get()

allprojects {
    group = "net.matsudamper.kotpub"

    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    configure<KtlintExtension> {
        version.set(ktlintVersion)
    }
}
