# kotpub

Kotlin/JVM 向けの ActivityPub 実装。WebFinger・Actor・inbox・NodeInfo の応答、
HTTP Signature の署名と検証、相手のアクター文書の取得、アクティビティの配送を持つ。

## 使い方

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("net.matsudamper.kotpub:activitypub:<version>")
    testImplementation(testFixtures("net.matsudamper.kotpub:activitypub:<version>"))
}
```

## ビルド

Java 25（Gradle が toolchain / daemon JVM を解決する。詳細は AGENTS.md）。

```shell
./gradlew build
```

## 公開

正式版:

- `v0.1.0` のようなタグを push する
- または Actions の Publish release を手動実行し、バージョンを指定する（`v<version>` タグと GitHub Release を作り、Maven Central にも出す）。Latest にするかは入力で選べる（オン時はリリースノートを自動生成）

Maven Central 公開にはリポジトリシークレット `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` と、署名用の `SIGNING_KEY_ID` / `SIGNING_PASSWORD` / `GPG_KEY_CONTENTS` が要る。

# 注意事項

`ActivityPubHttpClient`のデフォルト実装は
NAT64 が独自のプレフィックス（Network-Specific Prefix）を使うネットワークには対応していない。
そのネットワークで動かすと SSRF を防げない。
