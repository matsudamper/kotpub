package net.matsudamper.activitypub

import net.matsudamper.activitypub.entity.PublicNoteId
import net.matsudamper.activitypub.favourite.FavouriteStore
import net.matsudamper.activitypub.favourite.FavouriteStore.ReceivedFavourite

/**
 * SQL の振る舞いは `:backend:repository` のテストが本物の SQLite で確かめる。
 * こちらが受け持つのは、inbox のハンドラが何を記録して何を消したかの確認。
 */
class FakeFavouriteStore : FavouriteStore {
    val rows: MutableList<ReceivedFavourite> = mutableListOf()

    override fun add(favourite: ReceivedFavourite): Boolean {
        // 一意制約と同じ判定
        val duplicated = rows.any {
            it.actor.actorId == favourite.actor.actorId && it.notePublicId == favourite.notePublicId
        }
        if (duplicated) return false

        rows += favourite
        return true
    }

    override fun removeByNote(
        notePublicId: PublicNoteId,
        actorUri: String,
    ): Boolean = rows.removeAll { it.notePublicId == notePublicId && it.actor.actorId == actorUri }

    override fun removeActor(actorUri: String): Int {
        val before = rows.size
        rows.removeAll { it.actor.actorId == actorUri }
        return before - rows.size
    }

    override fun findPublicKeyPem(actorUri: String): String? =
        rows.firstOrNull { it.actor.actorId == actorUri }?.actor?.publicKeyPem
}
