package net.matsudamper.activitypub.inbox

import java.time.Instant
import kotlinx.serialization.json.JsonObject
import net.matsudamper.activitypub.activity.InboxActivity
import net.matsudamper.activitypub.activitypub.id
import net.matsudamper.activitypub.actor.RemoteActor
import net.matsudamper.activitypub.actor.RemoteActors
import net.matsudamper.activitypub.entity.PublicNoteId
import net.matsudamper.activitypub.favourite.FavouriteStore
import net.matsudamper.activitypub.favourite.FavouriteStore.ReceivedFavourite
import net.matsudamper.activitypub.note.NoteUrls
import net.matsudamper.activitypub.stamp.StampEmoji
import net.matsudamper.activitypub.stamp.StampEmojiTags
import net.matsudamper.activitypub.stamp.StampStore
import net.matsudamper.activitypub.stamp.StampStore.ReceivedStamp
import org.slf4j.LoggerFactory

/**
 * Mastodon のお気に入りは `content` を持たない `Like` で届く。Misskey は押した
 * 絵文字を `content` に載せた `Like` を、Pleroma は同じ形の `EmojiReact` を送ってくる。
 *
 * Mastodon は絵文字付きの `Like` もお気に入りとして数え、`EmojiReact` は受け取らない。
 * ここではどちらも絵文字のスタンプとして記録する。お気に入りとして数えると、
 * 押された絵文字が公開画面に出せない。
 *
 * @param type `Like` か `EmojiReact`。どちらも対象の投稿と絵文字が同じ場所に入っている
 */
class ReactionHandler(
    override val type: String,
    private val domain: String,
    private val remoteActors: RemoteActors,
    private val favourites: FavouriteStore,
    private val stamps: StampStore,
    private val earlyUndoneLikes: EarlyUndoneLikes,
) : InboxActivityHandler {
    private val logger = LoggerFactory.getLogger(ReactionHandler::class.java)

    override suspend fun handle(
        recipient: InboxRecipient,
        verifiedSignerActorId: String,
        activity: InboxActivity,
        rawActivityJson: JsonObject,
    ) {
        val targetUrl = activity.target?.id
        if (targetUrl == null) {
            logger.warn("$type に object が無い: ${recipient.logLabel} ← $verifiedSignerActorId")
            InboxSpan.outcome("reaction.no_object")
            return
        }

        val notePublicId = NoteUrls.publicIdOf(domain = domain, url = targetUrl)
        if (notePublicId == null) {
            logger.info("$type の対象がこちらの投稿ではないので何もしない: object=$targetUrl")
            InboxSpan.outcome("reaction.not_local_note")
            return
        }

        val emoji = StampEmoji.of(activity.content)
        InboxSpan.set(InboxSpan.STAMP_EMOJI, emoji)
        if (emoji == null && type != LIKE_TYPE) {
            logger.info("$type に絵文字が無いので何もしない: ${recipient.logLabel} ← $verifiedSignerActorId")
            InboxSpan.outcome("reaction.no_emoji")
            return
        }
        if (emoji != null && emoji.length > StampEmoji.MAX_LENGTH) {
            logger.info("$type の絵文字が長すぎるので受け付けない: ${recipient.logLabel} ← $verifiedSignerActorId")
            InboxSpan.outcome("reaction.emoji_too_long")
            return
        }

        val activityUri = activity.id
        val undoneFirst = activityUri != null &&
            earlyUndoneLikes.isRemembered(actorUri = verifiedSignerActorId, activityUri = activityUri, now = Instant.now())
        if (undoneFirst) {
            logger.info("先に取り消しが届いた $type なので記録しない: ${recipient.logLabel} ← $verifiedSignerActorId id=$activityUri")
            InboxSpan.outcome("reaction.undone_first")
            return
        }

        // 相手の公開鍵を remote_actors に持てないと、相手が消えた後の `Delete` を検証できず、
        // 居ない相手のお気に入りやスタンプが公開画面に出たままになる
        val actor = remoteActors.findActor(verifiedSignerActorId)
        if (actor == null) {
            logger.warn("$type の押し手のアクター文書を引けないので受け付けない: ${recipient.logLabel} ← $verifiedSignerActorId")
            InboxSpan.outcome("reaction.actor_unavailable")
            return
        }

        if (emoji == null) {
            addFavourite(recipient = recipient, notePublicId = notePublicId, actor = actor)
        } else {
            putStamp(
                recipient = recipient,
                notePublicId = notePublicId,
                actor = actor,
                emoji = emoji,
                rawActivityJson = rawActivityJson,
            )
        }
    }

    private fun addFavourite(
        recipient: InboxRecipient,
        notePublicId: PublicNoteId,
        actor: RemoteActor,
    ) {
        val recorded = favourites.add(
            ReceivedFavourite(
                notePublicId = notePublicId,
                actor = actor,
                receivedAt = Instant.now(),
            ),
        )

        InboxSpan.outcome(if (recorded) "favourite.recorded" else "favourite.not_recorded")
        if (recorded) {
            logger.info("お気に入りを記録した: ${recipient.logLabel} ← ${actor.actorId} 投稿=${notePublicId.value}")
        } else {
            logger.info("お気に入りを記録しなかった。記録済みか投稿が無い: ${recipient.logLabel} ← ${actor.actorId} 投稿=${notePublicId.value}")
        }
    }

    private fun putStamp(
        recipient: InboxRecipient,
        notePublicId: PublicNoteId,
        actor: RemoteActor,
        emoji: String,
        rawActivityJson: JsonObject,
    ) {
        val recorded = stamps.put(
            ReceivedStamp(
                notePublicId = notePublicId,
                actor = actor,
                emoji = emoji,
                emojiImageUrl = StampEmojiTags.imageUrl(rawActivityJson = rawActivityJson, emoji = emoji),
                receivedAt = Instant.now(),
            ),
        )

        InboxSpan.outcome(if (recorded) "stamp.recorded" else "stamp.not_recorded")
        if (recorded) {
            logger.info("スタンプを記録した: ${recipient.logLabel} ← ${actor.actorId} 投稿=${notePublicId.value} 絵文字=$emoji")
        } else {
            logger.info("スタンプを記録しなかった。投稿が無い: ${recipient.logLabel} ← ${actor.actorId} 投稿=${notePublicId.value}")
        }
    }

    companion object {
        const val LIKE_TYPE: String = "Like"

        const val EMOJI_REACT_TYPE: String = "EmojiReact"
    }
}
