package net.matsudamper.kotpub.stamp

import java.time.Instant
import net.matsudamper.kotpub.actor.RemoteActor
import net.matsudamper.kotpub.entity.PublicNoteId

interface StampStore {
    fun put(stamp: ReceivedStamp): Boolean

    fun remove(
        notePublicId: PublicNoteId,
        actorUri: String,
        emoji: String,
    ): Boolean

    fun removeActor(actorUri: String): Int

    fun findPublicKeyPem(actorUri: String): String?

    /**
     * @param emoji 絵文字そのもの、またはカスタム絵文字の `:name:`
     * @param emojiImageUrl カスタム絵文字の画像 URL。Unicode の絵文字では null
     */
    data class ReceivedStamp(
        val notePublicId: PublicNoteId,
        val actor: RemoteActor,
        val emoji: String,
        val emojiImageUrl: String?,
        val receivedAt: Instant,
    )
}
