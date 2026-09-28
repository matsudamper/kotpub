package net.matsudamper.activitypub.actor

import java.nio.file.Path

/**
 * アクターの秘密鍵の取得元。
 *
 * 鍵はアクターの同一性そのもので、変わると相手側の署名検証が通らなくなるため、
 * どちらから読んだのかが起動ログから分かるように型で分けている。
 *
 * 元は `ServerEnv` の中に置いていたが、環境変数の読み方はアプリ側の都合で、
 * このモジュールが決めることではない。[ActorKeyLoader] が受け取る形だけをここに置き、
 * 環境変数から組み立てるのはアプリの入口の仕事にする。
 */
sealed interface ActorPrivateKey {
    /**
     * PEM を直接渡す。Kubernetes の Secret や systemd の
     * `EnvironmentFile` から入れる場合はこちら。
     */
    data class Pem(
        val pem: String,
    ) : ActorPrivateKey

    /**
     * PEM をファイルから読む。ファイルが無ければ生成して書き出す。
     * docker compose のようにボリュームを持てる場合はこちら。
     */
    data class File(
        val path: Path,
    ) : ActorPrivateKey
}
