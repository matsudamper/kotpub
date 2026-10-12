package net.matsudamper.activitypub.json

import kotlinx.serialization.json.Json

/**
 * このアプリケーションで使う唯一の [Json] 設定。
 *
 * ActivityPub のやり取りは相手の実装ごとに癖があるため、設定を散らすと
 * 「このエンドポイントだけ通らない」という切り分けの難しい不具合になる。
 * 送信・受信ともにここを経由させる。
 */
val AppJson: Json =
    Json {
        // ActivityPub では既定値の省略で相手側が転ぶことがあるため、既定値も出力する
        encodeDefaults = true

        // null のフィールドは出力しない。ActivityPub では「キーごと無い」が通常の表現
        explicitNulls = false

        // 受信側。相手が独自の拡張プロパティを載せてきても落ちないようにする
        ignoreUnknownKeys = true
    }
