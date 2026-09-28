package net.matsudamper.activitypub.favourite

import java.time.Instant
import net.matsudamper.activitypub.actor.RemoteActor
import net.matsudamper.activitypub.entity.PublicNoteId

interface FavouriteStore {
    /**
     * 同じ相手が同じ投稿に押し直しても増えない。
     *
     * @return 記録したら true。既にあるか、投稿が無ければ false
     */
    fun add(favourite: ReceivedFavourite): Boolean

    /**
     * @param actorUri 押した相手。他人のお気に入りを消されないよう、署名の持ち主で固定する
     */
    fun removeByNote(
        notePublicId: PublicNoteId,
        actorUri: String,
    ): Boolean

    fun removeActor(actorUri: String): Int

    fun findPublicKeyPem(actorUri: String): String?

    data class ReceivedFavourite(
        val notePublicId: PublicNoteId,
        val actor: RemoteActor,
        val receivedAt: Instant,
    )
}
