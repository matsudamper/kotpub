package net.matsudamper.kotpub.inbox

import net.matsudamper.kotpub.activity.InboxActivity
import net.matsudamper.kotpub.activitypub.LinkOrObject
import net.matsudamper.kotpub.boost.BoostStore
import net.matsudamper.kotpub.json.AppJson
import org.slf4j.LoggerFactory

/**
 * ブーストの取り消し。`Announce` の id を記録しているので、`object` が id だけでも取り消せる
 */
class UndoBoostHandler(
    private val boosts: BoostStore,
) {
    private val logger = LoggerFactory.getLogger(UndoBoostHandler::class.java)

    /**
     * @return ブーストの取り消しだったら true。id だけの `object` は、記録にあって消せたときだけ true
     */
    fun handle(
        recipient: InboxRecipient,
        verifiedSignerActorId: String,
        activity: InboxActivity,
    ): Boolean {
        return when (val undoObject = activity.target) {
            null -> false
            is LinkOrObject.Link -> remove(recipient, verifiedSignerActorId, undoObject.href)
            is LinkOrObject.Embedded -> removeEmbedded(recipient, verifiedSignerActorId, undoObject)
        }
    }

    private fun removeEmbedded(
        recipient: InboxRecipient,
        verifiedSignerActorId: String,
        undoObject: LinkOrObject.Embedded,
    ): Boolean {
        val undoneActivity =
            runCatching { AppJson.decodeFromJsonElement(InboxActivity.serializer(), undoObject.json) }.getOrNull()
        if (undoneActivity?.type != AnnounceHandler.TYPE) return false

        val activityUri = undoneActivity.id
        if (activityUri == null) {
            logger.info("取り消す Announce に id が無い: ${recipient.logLabel} ← $verifiedSignerActorId")
            InboxSpan.outcome("undo_boost.no_id")
            return true
        }
        if (!remove(recipient, verifiedSignerActorId, activityUri)) {
            logger.info("取り消す Announce が記録に無い: ${recipient.logLabel} ← $verifiedSignerActorId id=$activityUri")
            InboxSpan.outcome("undo_boost.not_recorded")
        }
        return true
    }

    private fun remove(
        recipient: InboxRecipient,
        verifiedSignerActorId: String,
        activityUri: String,
    ): Boolean {
        val removed = boosts.remove(actorUri = verifiedSignerActorId, activityUri = activityUri)
        if (removed) {
            logger.info("ブーストを取り消した: ${recipient.logLabel} ← $verifiedSignerActorId id=$activityUri")
            InboxSpan.outcome("undo_boost.removed")
        }
        return removed
    }
}
