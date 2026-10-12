package net.matsudamper.activitypub

import net.matsudamper.activitypub.entity.PublicNoteId
import net.matsudamper.activitypub.stamp.StampStore
import net.matsudamper.activitypub.stamp.StampStore.ReceivedStamp

/**
 * SQL の振る舞いは `:backend:repository` のテストが本物の SQLite で確かめる。
 * こちらが受け持つのは、inbox のハンドラが何を記録して何を消したかの確認。
 */
class FakeStampStore : StampStore {
    val rows: MutableList<ReceivedStamp> = mutableListOf()

    override fun put(stamp: ReceivedStamp): Boolean {
        // 一意制約に当たったら置き換えるのと同じ
        rows.removeAll { it.actor.actorId == stamp.actor.actorId && it.notePublicId == stamp.notePublicId }
        rows += stamp
        return true
    }

    override fun remove(
        notePublicId: PublicNoteId,
        actorUri: String,
        emoji: String,
    ): Boolean = rows.removeAll { it.notePublicId == notePublicId && it.actor.actorId == actorUri && it.emoji == emoji }

    override fun removeActor(actorUri: String): Int {
        val before = rows.size
        rows.removeAll { it.actor.actorId == actorUri }
        return before - rows.size
    }

    override fun findPublicKeyPem(actorUri: String): String? =
        rows.firstOrNull { it.actor.actorId == actorUri }?.actor?.publicKeyPem
}
