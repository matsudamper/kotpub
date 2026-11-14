package net.matsudamper.kotpub.inbox

import java.time.Instant
import kotlinx.serialization.json.JsonObject
import net.matsudamper.kotpub.activity.InboxActivity
import net.matsudamper.kotpub.activitypub.id
import net.matsudamper.kotpub.actor.RemoteActor
import net.matsudamper.kotpub.actor.RemoteActorLookup
import net.matsudamper.kotpub.actor.RemoteActors
import net.matsudamper.kotpub.entity.PublicNoteId
import net.matsudamper.kotpub.favourite.FavouriteStore
import net.matsudamper.kotpub.favourite.FavouriteStore.ReceivedFavourite
import net.matsudamper.kotpub.note.NoteUrls
import net.matsudamper.kotpub.stamp.StampEmoji
import net.matsudamper.kotpub.stamp.StampEmojiTags
import net.matsudamper.kotpub.stamp.StampStore
import net.matsudamper.kotpub.stamp.StampStore.ReceivedStamp

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

    override suspend fun handle(
        recipient: InboxRecipient,
        verifiedSignerActorId: String,
        activity: InboxActivity,
        rawActivityJson: JsonObject,
    ): InboxHandling {
        val targetUrl = activity.target?.id
        if (targetUrl == null) {
            InboxSpan.outcome("reaction.no_object")
            return InboxHandling.Ignored("$type に object が無い: ${recipient.logLabel} ← $verifiedSignerActorId")
        }

        val notePublicId = NoteUrls.publicIdOf(domain = domain, url = targetUrl)
        if (notePublicId == null) {
            InboxSpan.outcome("reaction.not_local_note")
            return InboxHandling.Ignored("$type の対象がこちらの投稿ではないので何もしない: object=$targetUrl")
        }

        val emoji = StampEmoji.of(activity.content)
        InboxSpan.set(InboxSpan.STAMP_EMOJI, emoji)
        if (emoji == null && type != LIKE_TYPE) {
            InboxSpan.outcome("reaction.no_emoji")
            return InboxHandling.Ignored("$type に絵文字が無いので何もしない: ${recipient.logLabel} ← $verifiedSignerActorId")
        }
        if (emoji != null && emoji.length > StampEmoji.MAX_LENGTH) {
            InboxSpan.outcome("reaction.emoji_too_long")
            return InboxHandling.Ignored("$type の絵文字が長すぎるので受け付けない: ${recipient.logLabel} ← $verifiedSignerActorId")
        }

        val activityUri = activity.id
        val undoneFirst = activityUri != null &&
            earlyUndoneLikes.isRemembered(actorUri = verifiedSignerActorId, activityUri = activityUri, now = Instant.now())
        if (undoneFirst) {
            InboxSpan.outcome("reaction.undone_first")
            return InboxHandling.Ignored("先に取り消しが届いた $type なので記録しない: ${recipient.logLabel} ← $verifiedSignerActorId id=$activityUri")
        }

        // 相手の公開鍵を記録できないと、相手が消えた後の `Delete` を検証できず、
        // 居ない相手のお気に入りやスタンプが公開画面に出たままになる
        val actor = when (val lookup = remoteActors.findActor(verifiedSignerActorId)) {
            is RemoteActorLookup.Found -> lookup.actor

            RemoteActorLookup.Gone, is RemoteActorLookup.Unavailable -> {
                InboxSpan.outcome("reaction.actor_unavailable")
                return InboxHandling.Ignored("$type の押し手のアクター文書を取得できないので受け付けない: ${recipient.logLabel} ← $verifiedSignerActorId 理由=$lookup")
            }
        }

        return if (emoji == null) {
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
    ): InboxHandling {
        val recorded = favourites.add(
            ReceivedFavourite(
                notePublicId = notePublicId,
                actor = actor,
                receivedAt = Instant.now(),
            ),
        )

        InboxSpan.outcome(if (recorded) "favourite.recorded" else "favourite.not_recorded")
        if (!recorded) {
            return InboxHandling.Ignored("お気に入りを記録しなかった。記録済みか投稿が無い: ${recipient.logLabel} ← ${actor.actorId} 投稿=${notePublicId.value}")
        }
        return InboxHandling.Handled
    }

    private fun putStamp(
        recipient: InboxRecipient,
        notePublicId: PublicNoteId,
        actor: RemoteActor,
        emoji: String,
        rawActivityJson: JsonObject,
    ): InboxHandling {
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
        if (!recorded) {
            return InboxHandling.Ignored("スタンプを記録しなかった。投稿が無い: ${recipient.logLabel} ← ${actor.actorId} 投稿=${notePublicId.value}")
        }
        return InboxHandling.Handled
    }

    companion object {
        const val LIKE_TYPE: String = "Like"

        const val EMOJI_REACT_TYPE: String = "EmojiReact"
    }
}
