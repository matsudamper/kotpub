package net.matsudamper.activitypub

import net.matsudamper.activitypub.entity.PublicNoteId
import net.matsudamper.activitypub.url.WebPageUrls

/**
 * テストで渡す画面の URL。
 *
 * 本物の綴りは `:backend` が決めるので、ここでは同じ形の別物を返す。
 * ActivityPub の `id` とは別のパスになっていることだけを見る。
 */
object TestWebPageUrls : WebPageUrls {
    override fun profile(username: String): String = "https://${TestLocalActor.DOMAIN}/@$username"

    override fun note(
        username: String,
        publicId: PublicNoteId,
    ): String = "${profile(username)}/${publicId.value}"
}
