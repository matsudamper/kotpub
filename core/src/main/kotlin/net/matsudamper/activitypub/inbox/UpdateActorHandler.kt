package net.matsudamper.activitypub.inbox

import kotlinx.serialization.json.JsonObject
import net.matsudamper.activitypub.activity.InboxActivity
import net.matsudamper.activitypub.activitypub.LinkOrObject
import net.matsudamper.activitypub.activitypub.id
import net.matsudamper.activitypub.actor.RemoteActorDocument
import net.matsudamper.activitypub.follower.FollowerStore
import net.matsudamper.activitypub.json.AppJson
import org.slf4j.LoggerFactory

/**
 * `Update` を受けたときの処理。フォロワーの表示名とアイコンを追従させる。
 *
 * Mastodon はプロフィールを編集すると、フォロワーのサーバーに送り主自身を
 * `object` に入れた `Update` を送る。これを見ないと、一覧に出るのは
 * `Follow` を受けたときの名前で固定される。
 *
 * `Update` は投稿の編集にも使われるので、`object` が送り主自身を指しているものだけを
 * 扱う。
 *
 * 入れ替えるのは表示に使う部分だけで、inbox と公開鍵は触らない。どちらも配信と
 * 署名の検証に効くので、入れ替えるなら相手のサーバーから取り直したものを使う。
 *
 * 取りに行かずに `object` の中身を読むのは、相手のアクター文書のキャッシュが
 * 残っていると編集前のものが返ってくるため。ここに届くのは署名を検証できた
 * ものだけなので、送り主自身についての記述はその相手が書いたものとして扱える。
 */
class UpdateActorHandler(
    private val followers: FollowerStore,
) : InboxActivityHandler {
    override val type: String = "Update"

    private val logger = LoggerFactory.getLogger(UpdateActorHandler::class.java)

    override suspend fun handle(
        recipient: InboxRecipient,
        verifiedSignerActorId: String,
        activity: InboxActivity,
        rawActivityJson: JsonObject,
    ) {
        val target = activity.target
        if (target?.id != verifiedSignerActorId) {
            logger.info("Update の対象がアクター自身ではないので何もしない: object=${target?.id} 署名者=$verifiedSignerActorId")
            return
        }

        // id だけで来る実装もある。中身が無ければ入れ替えるものが無い
        val embedded = (target as? LinkOrObject.Embedded)?.json
        if (embedded == null) {
            logger.info("Update に中身が埋め込まれていないので何もしない: $verifiedSignerActorId")
            return
        }

        val document = runCatching { AppJson.decodeFromJsonElement(RemoteActorDocument.serializer(), embedded) }
            .getOrNull()
        if (document == null) {
            logger.warn("Update のアクターを読めなかった: $verifiedSignerActorId")
            return
        }

        followers.rememberProfile(actorUri = verifiedSignerActorId, profile = document.profile())
    }
}
