package net.matsudamper.kotpub.inbox

import kotlinx.serialization.json.JsonObject
import net.matsudamper.kotpub.activity.InboxActivity
import net.matsudamper.kotpub.activitypub.LinkOrObject
import net.matsudamper.kotpub.activitypub.id
import net.matsudamper.kotpub.http.HttpUrl
import net.matsudamper.kotpub.note.ReceivedNoteJson
import net.matsudamper.kotpub.note.RemoteNoteStore

/**
 * `Update` はプロフィールの編集にも投稿の編集にも使われるので、`object` が何なのかで
 * 渡し先を決める。`type` でハンドラを選ぶ [InboxService] からは
 * `Update` が 1 つに見えるようにする。
 *
 * 投稿は取得せずに `object` の中身を読む。相手のサーバーのキャッシュが残っていると
 * 編集前のものが返ってくるため。ここに届くのは署名を検証できたものだけなので、
 * 署名者と同じホストにある署名者の投稿は、その相手が書いたものとして扱える。
 */
class UpdateHandler(
    private val actors: UpdateActorHandler,
    private val notes: RemoteNoteStore,
) : InboxActivityHandler {
    override val type: String = "Update"

    override suspend fun handle(
        recipient: InboxRecipient,
        verifiedSignerActorId: String,
        activity: InboxActivity,
        rawActivityJson: JsonObject,
    ): InboxHandling {
        val target = activity.target
        if (target?.id == verifiedSignerActorId) {
            return actors.handle(recipient, verifiedSignerActorId, activity, rawActivityJson)
        }
        val json = (target as? LinkOrObject.Embedded)?.json
            ?: return InboxHandling.Ignored("Update に中身が埋め込まれていない: $verifiedSignerActorId")
        val note = ReceivedNoteJson.read(json, rawActivityJson)
            ?: return InboxHandling.Ignored("Update の object を投稿として読めない: $verifiedSignerActorId")
        val actor = activity.actorId ?: return InboxHandling.Ignored("Update に actor が無い: $verifiedSignerActorId")
        if (actor != verifiedSignerActorId || note.attributedTo != actor) {
            return InboxHandling.Ignored("Update の actor か投稿者が署名者と違う: actor=$actor 投稿者=${note.attributedTo} 署名者=$verifiedSignerActorId")
        }
        val actorUrl = HttpUrl.parse(actor) ?: return InboxHandling.Ignored("Update の actor を URL として読めない: $actor")
        val noteUrl = HttpUrl.parse(note.id) ?: return InboxHandling.Ignored("投稿の id を URL として読めない: ${note.id}")
        if (!actorUrl.isSameHost(noteUrl)) return InboxHandling.Ignored("投稿が actor と別のホスト: ${note.id} actor=$actor")
        notes.update(note)
        return InboxHandling.Handled
    }
}
