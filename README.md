# kotpub

Kotlin/JVM 向けの ActivityPub 実装。WebFinger・Actor・inbox・NodeInfo の応答、
HTTP Signature の署名と検証、相手のアクター文書の取得、アクティビティの配送を持つ。

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

Java 25（Gradle が toolchain / daemon JVM を解決する。詳細は AGENTS.md）。

```shell
./gradlew build
```

## 公開

Actions の Release を手動実行し、バージョン（例: `0.1.0`）を指定すると `v0.1.0` のタグと GitHub Release ができる。
タグの push で Publish が走り、同じバージョンを GitHub Packages に配信する。Latest にした場合はリリースノートが自動生成される。

`v0.1.0` のタグを直接 push しても、Publish だけは同様に動く。

main への push では `latest-SNAPSHOT` として、main の最新で上書きしながら公開する。

PR ごとにも `0.0.0-pr<番号>-<head のコミット 7 桁>` で公開する。使う側でマージ前の変更を試すときに使う。

# 注意事項

`ActivityPubHttpClient`のデフォルト実装は
NAT64 が独自のプレフィックス（Network-Specific Prefix）を使うネットワークには対応していない。
そのネットワークで動かすと SSRF を防げない。
