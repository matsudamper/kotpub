# Gradle

## JDK
- プロジェクトの Java toolchain は 25。Foojay Toolchains Resolver（`settings.gradle.kts`）で不足 JDK を取得する
- Gradle daemon の JVM は `gradle/gradle-daemon-jvm.properties` に固定する。手書きせず `./gradlew updateDaemonJvm --jvm-version=25` で生成・更新する

## 実行
- Gradle の実行に `--offline` を使用しない
- ネットワークや依存関係の取得に失敗しても、`--offline` を付けて回避しない
