package net.matsudamper.kotpub.activity

/**
 * ブーストを誰に見せるか。ブーストした投稿の公開範囲に合わせる。
 * 公開タイムラインに載せない投稿を [PUBLIC] でブーストすると、相手のサーバーの公開タイムラインに載ってしまう
 */
enum class BoostAudience {
    /**
     * 公開。to に公開を入れる
     */
    PUBLIC,

    /**
     * 公開タイムラインには載せない公開。to にフォロワー、cc に公開を入れる
     */
    UNLISTED,
}
