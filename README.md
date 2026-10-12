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

正式版（GitHub Packages）:

- `v0.1.0` のようなタグを push する
- または Actions の Publish release を手動実行し、バージョンを指定する（`v<version>` タグと GitHub Release を作り、Packages にも出す）。Latest にするかは入力で選べる（オン時はリリースノートを自動生成）

PR では `0.0.0-pr<番号>-<head のコミット 7 桁>` で試用版を出す。マージ前の変更を試すときに使う。

main への push では Packages には出さない（ビルドは Build ワークフロー）。

# 注意事項

`ActivityPubHttpClient`のデフォルト実装は
NAT64 が独自のプレフィックス（Network-Specific Prefix）を使うネットワークには対応していない。
そのネットワークで動かすと SSRF を防げない。
