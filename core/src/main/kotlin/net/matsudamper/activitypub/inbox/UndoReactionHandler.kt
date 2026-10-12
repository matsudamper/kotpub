package net.matsudamper.activitypub.inbox

import java.time.Instant
import kotlin.time.toJavaDuration
import net.matsudamper.activitypub.activity.InboxActivity
import net.matsudamper.activitypub.activitypub.LinkOrObject
import net.matsudamper.activitypub.activitypub.id
import net.matsudamper.activitypub.entity.PublicNoteId
import net.matsudamper.activitypub.favourite.FavouriteStore
import net.matsudamper.activitypub.json.AppJson
import net.matsudamper.activitypub.note.NoteUrls
import net.matsudamper.activitypub.stamp.StampEmoji
import net.matsudamper.activitypub.stamp.StampStore
import org.slf4j.LoggerFactory

/**
 * 受け取った `Like` / `EmojiReact` アクティビティ自身の `id` は記録していないので、
 * `object` にその `id` だけが入った `Undo` ではお気に入りもスタンプも取り消せない。
 * Mastodon も同じで、この形の取り消しはお気に入りとしては扱わない。
 */
class UndoReactionHandler(
    private val domain: String,
    private val favourites: FavouriteStore,
    private val stamps: StampStore,
    private val earlyUndoneLikes: EarlyUndoneLikes,
) {
    private val logger = LoggerFactory.getLogger(UndoReactionHandler::class.java)

    /**
     * @return お気に入りかスタンプの取り消しだったら true。フォロー解除など別の取り消しなら false
     */
    fun handle(
        recipient: InboxRecipient,
        verifiedSignerActorId: String,
        activity: InboxActivity,
    ): Boolean {
        return when (val undoObject = activity.target) {
            null -> false
            is LinkOrObject.Link -> false
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
        if (undoneActivity == null) {
            logger.warn("Undo の object を読めなかった: ${recipient.logLabel} ← $verifiedSignerActorId")
            InboxSpan.outcome("undo_reaction.unreadable_object")
            return false
        }

        val undoneType = undoneActivity.type
        if (undoneType != ReactionHandler.LIKE_TYPE && undoneType != ReactionHandler.EMOJI_REACT_TYPE) return false

        val notePublicId = undoneActivity.target?.id?.let { NoteUrls.publicIdOf(domain = domain, url = it) }
        if (notePublicId == null) {
            logger.info("取り消す $undoneType を引き当てられない: ${recipient.logLabel} ← $verifiedSignerActorId")
            InboxSpan.outcome("undo_reaction.not_local_note")
            return true
        }

        val removed = remove(
            undoneType = undoneType,
            notePublicId = notePublicId,
            actorUri = verifiedSignerActorId,
            emoji = StampEmoji.of(undoneActivity.content),
        )
        InboxSpan.outcome(if (removed) "undo_reaction.removed" else "undo_reaction.not_recorded")
        if (removed) {
            logger.info("$undoneType を取り消した: ${recipient.logLabel} ← $verifiedSignerActorId 投稿=${notePublicId.value}")
            return true
        }

        val undoneActivityUri = undoneActivity.id
        if (undoneActivityUri != null) {
            rememberEarlyUndone(actorUri = verifiedSignerActorId, activityUri = undoneActivityUri)
        }
        logger.info("取り消す $undoneType が記録に無い: ${recipient.logLabel} ← $verifiedSignerActorId 投稿=${notePublicId.value}")
        return true
    }

    private fun remove(
        undoneType: String,
        notePublicId: PublicNoteId,
        actorUri: String,
        emoji: String?,
    ): Boolean {
        return when {
            emoji != null -> {
                stamps.remove(notePublicId = notePublicId, actorUri = actorUri, emoji = emoji)
            }

            undoneType == ReactionHandler.LIKE_TYPE -> {
                favourites.removeByNote(notePublicId = notePublicId, actorUri = actorUri)
            }

            else -> false
        }
    }

    /**
     * @param activityUri `Undo` の `object` が指していた `Like` / `EmojiReact` アクティビティの `id`。
     *   後から同じ `id` のものが届いても記録しない
     */
    fun rememberEarlyUndone(
        actorUri: String,
        activityUri: String,
    ) {
        earlyUndoneLikes.remember(
            actorUri = actorUri,
            activityUri = activityUri,
            expiresAt = Instant.now() + EarlyUndoneLikes.TTL.toJavaDuration(),
        )
    }
}
