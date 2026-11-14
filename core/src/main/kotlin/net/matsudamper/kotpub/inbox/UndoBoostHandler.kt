package net.matsudamper.kotpub.inbox

import java.time.Instant
import kotlin.time.toJavaDuration
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
    private val earlyUndone: EarlyUndoneLikes,
) {
    private val logger = LoggerFactory.getLogger(UndoBoostHandler::class.java)

    /**
     * @return ブーストの取り消しでなければ null。id だけの `object` は、記録にあって消せたときだけ null 以外
     */
    fun handle(
        recipient: InboxRecipient,
        verifiedSignerActorId: String,
        activity: InboxActivity,
    ): InboxHandling? {
        return when (val undoObject = activity.target) {
            null -> null
            is LinkOrObject.Link -> InboxHandling.Handled.takeIf { remove(recipient, verifiedSignerActorId, undoObject.href) }
            is LinkOrObject.Embedded -> removeEmbedded(recipient, verifiedSignerActorId, undoObject)
        }
    }

    private fun removeEmbedded(
        recipient: InboxRecipient,
        verifiedSignerActorId: String,
        undoObject: LinkOrObject.Embedded,
    ): InboxHandling? {
        val undoneActivity =
            runCatching { AppJson.decodeFromJsonElement(InboxActivity.serializer(), undoObject.json) }.getOrNull()
        if (undoneActivity?.type != AnnounceHandler.TYPE) return null

        val activityUri = undoneActivity.id
        if (activityUri == null) {
            InboxSpan.outcome("undo_boost.no_id")
            return InboxHandling.Ignored("取り消す Announce に id が無い: ${recipient.logLabel} ← $verifiedSignerActorId")
        }
        if (!remove(recipient, verifiedSignerActorId, activityUri)) {
            // 配送の順番は保証されないので、後から届く同じ id の Announce に備える
            earlyUndone.remember(
                actorUri = verifiedSignerActorId,
                activityUri = activityUri,
                expiresAt = Instant.now() + EarlyUndoneLikes.TTL.toJavaDuration(),
            )
            InboxSpan.outcome("undo_boost.not_recorded")
            return InboxHandling.Ignored("取り消す Announce が記録に無い: ${recipient.logLabel} ← $verifiedSignerActorId id=$activityUri")
        }
        return InboxHandling.Handled
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
