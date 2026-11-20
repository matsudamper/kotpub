package net.matsudamper.kotpub.inbox

import net.matsudamper.kotpub.http.HttpUrl

/**
 * 先に届いた `Undo` の `object` を [EarlyUndoneLikes] に残してよいかの判定。
 *
 * 対象が記録に無い `Undo` は署名できる相手なら誰でも送れて、残した行は [EarlyUndoneLikes.TTL] の間消えない。
 * 受け取った値をそのまま残すと、受信できる大きさいっぱいの文字列を何度でも書き込ませられる。
 * 本物の `Like` / `EmojiReact` / `Announce` の `id` は送り主のサーバーが振った URL なので、
 * 署名者と同じホストの短い URL だけを残す。
 */
internal object EarlyUndoneActivityUri {
    /**
     * 一般的なブラウザやサーバーが扱える URL の長さの目安
     */
    private const val MAX_LENGTH = 2048

    fun isRememberable(
        actorUri: String,
        activityUri: String,
    ): Boolean {
        if (activityUri.length > MAX_LENGTH) return false
        val activityUrl = HttpUrl.parse(activityUri) ?: return false
        val actorUrl = HttpUrl.parse(actorUri) ?: return false
        return activityUrl.isSameHost(actorUrl)
    }
}
