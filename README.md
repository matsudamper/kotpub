# kotpub

Kotlin/JVM 向けの ActivityPub 実装。WebFinger、Actor、inbox、NodeInfo の応答、
HTTP Signature の署名と検証、相手のアクター文書の取得、アクティビティの配送を扱う。

HTTP サーバーには依存しない。エンドポイントは `EndpointResponse` を返し、
外向きの通信は `ActivityPubHttpClient` を使う。

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

Java 25。

```shell
./gradlew build
```

## 公開

GitHub Actions が GitHub Packages へ公開する。

- タグ `v0.1.0` の push: `0.1.0`
- Actions の Publish を手動実行: 指定したバージョン
- main への push: `latest-SNAPSHOT`（main の最新で上書き）
- PR: `0.0.0-pr<番号>-<head のコミット 7 桁>`（マージ前の変更を使う側で試すとき）
