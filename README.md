# kotpub

Kotlin/JVM 向けの ActivityPub 実装。WebFinger・Actor・inbox・NodeInfo の応答、
HTTP Signature の署名と検証、相手のアクター文書の取得、アクティビティの配送を持つ。

HTTP のサーバーとクライアントには依存しない。エンドポイントは `EndpointResponse` を返し、
外向きの通信は `ActivityPubHttpClient` を受け取る。どちらの実装も使う側が用意する。

## 使い方

GitHub Packages に公開している。取得には `read:packages` を付けたトークンが要る。

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/matsudamper/kotpub")
        credentials {
            username = providers.gradleProperty("gpr.user").get()
            password = providers.gradleProperty("gpr.key").get()
        }
    }
}

dependencies {
    implementation("net.matsudamper.kotpub:activitypub:<version>")
    // テスト用のフェイク（相手のアクター、送信先など）
    testImplementation(testFixtures("net.matsudamper.kotpub:activitypub:<version>"))
}
```

## ビルド

```shell
./gradlew build
```

## 公開

`v0.1.0` のようなタグを push すると、GitHub Actions がタグのバージョンで公開する。
