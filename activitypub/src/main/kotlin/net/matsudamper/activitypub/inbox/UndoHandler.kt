package net.matsudamper.activitypub.inbox

import kotlinx.serialization.json.JsonObject
import net.matsudamper.activitypub.activity.InboxActivity
import net.matsudamper.activitypub.activitypub.LinkOrObject

/**
 * `Undo` は取り消せるものが 1 つではないので、`object` が何だったのかで
 * 渡し先を決める。`type` でハンドラを引き当てる [InboxService] からは
 * `Undo` が 1 つに見えるようにする。
 */
class UndoHandler(
    private val reactions: UndoReactionHandler,
    private val follows: UndoFollowHandler,
) : InboxActivityHandler {
    override val type: String = "Undo"

    override suspend fun handle(
        recipient: InboxRecipient,
        verifiedSignerActorId: String,
        activity: InboxActivity,
        rawActivityJson: JsonObject,
    ) {
        val undoneReaction = reactions.handle(
            recipient = recipient,
            verifiedSignerActorId = verifiedSignerActorId,
            activity = activity,
        )
        if (undoneReaction) return

        val unfollowed = follows.handle(
            recipient = recipient,
            verifiedSignerActorId = verifiedSignerActorId,
            activity = activity,
        )
        if (unfollowed) return

        // Mastodon の handle_reference と同じく、object が id だけの Undo が何にも
        // 当たらなければ、まだ届いていない Like / EmojiReact の取り消しとみなしてその id を覚える
        val undoObject = activity.target
        if (undoObject is LinkOrObject.Link) {
            reactions.rememberEarlyUndone(actorUri = verifiedSignerActorId, activityUri = undoObject.href)
        }
    }
}
