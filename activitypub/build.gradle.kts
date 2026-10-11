plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`

    // テスト用のフェイク（相手のアクター、送信先）を使う側のテストからも使えるようにする。
    // maven-publish はこれも別の variant として一緒に公開する
    `java-test-fixtures`
    `maven-publish`
}

dependencies {
    // HTTP のサーバーとクライアントには依存しない。エンドポイントは EndpointResponse を返し、
    // 外向きの通信は ActivityPubHttpClient を受け取る。どちらの実装も使う側が用意する
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // アクター文書の取得を span で囲み、その中の GET をぶら下げる。
    // HttpRemoteActors の公開コンストラクタが OpenTelemetry を受け取るので api にする
    api(libs.opentelemetry.api)
    // suspend の間も span を current に保つのに要る
    implementation(libs.opentelemetry.extension.kotlin)

    implementation(libs.slf4j.api)

    testImplementation(libs.kotlin.test)
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
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/matsudamper/kotpub")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
        }
    }
}
