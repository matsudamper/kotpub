package net.matsudamper.kotpub.inbox

import kotlinx.serialization.json.JsonObject
import net.matsudamper.kotpub.activity.InboxActivity
import net.matsudamper.kotpub.activitypub.LinkOrObject

/**
 * `Undo` は取り消せるものが 1 つではないので、`object` が何だったのかで
 * 渡し先を決める。`type` でハンドラを選ぶ [InboxService] からは
 * `Undo` が 1 つに見えるようにする。
 */
class UndoHandler(
    private val reactions: UndoReactionHandler,
    private val boosts: UndoBoostHandler,
    private val follows: UndoFollowHandler,
) : InboxActivityHandler {
    override val type: String = "Undo"

    override suspend fun handle(
        recipient: InboxRecipient,
        verifiedSignerActorId: String,
        activity: InboxActivity,
        rawActivityJson: JsonObject,
    ): InboxHandling {
        reactions.handle(
            recipient = recipient,
            verifiedSignerActorId = verifiedSignerActorId,
            activity = activity,
        )?.let { return it }

        boosts.handle(
            recipient = recipient,
            verifiedSignerActorId = verifiedSignerActorId,
            activity = activity,
        )?.let { return it }

        follows.handle(
            recipient = recipient,
            verifiedSignerActorId = verifiedSignerActorId,
            activity = activity,
        )?.let { return it }

        // Mastodon の handle_reference と同じく、object が id だけの Undo が何にも
        // 当たらなければ、まだ届いていない Like / EmojiReact の取り消しとみなしてその id を覚える
        val undoObject = activity.target
        if (undoObject is LinkOrObject.Link) {
            reactions.rememberEarlyUndone(actorUri = verifiedSignerActorId, activityUri = undoObject.href)
            return InboxHandling.Ignored("Undo の対象が記録に無いので、後から届く Like / EmojiReact の取り消しとして覚えた: ${undoObject.href}")
        }
        return InboxHandling.Ignored("Undo の対象を取り消せなかった: ${recipient.logLabel} ← $verifiedSignerActorId")
    }
}
