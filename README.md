# kotpub

Kotlin/JVM 向けの ActivityPub 実装。WebFinger・Actor・inbox・NodeInfo の応答、
HTTP Signature の署名と検証、WebFinger による相手のアクターの発見、相手のアクター文書の取得、アクティビティの配送を持つ。

## 次のリリース: 1.5.0

`RemoteActors.findActor` の戻り値の `actorId` は、問い合わせ URL ではなく取得した文書の `id` になる。
文書の `id` が無い、HTTPS でない、取得先とホストが違う場合は取得に失敗する。
`gradle.properties` は `latest-SNAPSHOT` を維持し、正式版は GHA の `-Pversion=1.5.0` で公開する。

## 使い方

### 正式版

```kotlin
repositories {
    maven { url = uri("https://maven.matsudamper.net/releases") }
}

dependencies {
    implementation("net.matsudamper.kotpub:core:<version>")
    // Ktor CIO の ActivityPubHttpClient
    implementation("net.matsudamper.kotpub:client-ktor:<version>")
    // テスト用のフェイク（相手のアクター、送信先など）
    testImplementation(testFixtures("net.matsudamper.kotpub:core:<version>"))
}
```

### SNAPSHOT / PR の試用版

GitHub Packages から取得する。取得には `read:packages` を付けたトークンが要る。

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
    implementation("net.matsudamper.kotpub:core:latest-SNAPSHOT")
    // PR の試用版は PR のコメントに書かれたバージョンを指定する
}
```

## リモートユーザーの公開投稿

`HttpRemoteOutbox(client).recent(actorId, limit)` で、アクターの outbox から公開投稿を取得できる。
`limit` は 1〜20 件。最大 3 ページを読み、本人の `Note` と本人による `Create` の投稿だけを返す。
他のアクターの投稿・ブースト・非公開投稿は返さない。取得先は HTTPS かつアクターと同じホストに限る。
`RemoteActorProfile.summary` には自己紹介の HTML が入るため、表示する側で安全な形式に変換する。

## ビルド

Java 25（Gradle が toolchain / daemon JVM を解決する。詳細は AGENTS.md）。

```shell
./gradlew build
```

## 公開

正式版（maven.matsudamper.net / GitHub Packages）:

- `v0.1.0` のようなタグを push する（maven.matsudamper.net と GitHub Packages に出す）
- または Actions の Publish release を手動実行し、バージョンを指定する（公開に成功した後に `v<version>` タグと GitHub Release を作る）。Latest にするかは入力で選べる（オン時はリリースノートを自動生成）

PR では `0.0.0-pr<番号>-<head のコミット 7 桁>` で試用版を出し、そのバージョンを PR にコメントする。マージ前の変更を試すときに使う。

main への push では `latest-SNAPSHOT` として、main の最新で上書きしながら公開する。

# 注意事項

`ActivityPubHttpClient`のデフォルト実装は
NAT64 が独自のプレフィックス（Network-Specific Prefix）を使うネットワークには対応していない。
そのネットワークで動かすと SSRF を防げない。
