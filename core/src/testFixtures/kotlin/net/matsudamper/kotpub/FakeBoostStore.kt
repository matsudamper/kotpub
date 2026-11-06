package net.matsudamper.kotpub

import net.matsudamper.kotpub.boost.BoostStore
import net.matsudamper.kotpub.boost.BoostStore.ReceivedBoost
import net.matsudamper.kotpub.boost.BoostStore.ReceivedRemoteBoost

/**
 * SQL の振る舞いは利用側のアプリのテストが本物の DB で確かめる。
 * こちらが受け持つのは、inbox のハンドラが何を記録して何を消したかの確認。
 */
class FakeBoostStore : BoostStore {
    val rows: MutableList<ReceivedBoost> = mutableListOf()

    val remoteRows: MutableList<ReceivedRemoteBoost> = mutableListOf()

    override fun add(boost: ReceivedBoost): Boolean {
        // 一意制約と同じ判定
        val duplicated = rows.any { it.actor.actorId == boost.actor.actorId && it.notePublicId == boost.notePublicId }
        if (duplicated) return false

        rows += boost
        return true
    }

    override suspend fun saveRemote(boost: ReceivedRemoteBoost) {
        remoteRows += boost
    }

    override fun remove(
        actorUri: String,
        activityUri: String,
    ): Boolean {
        val removedLocal = rows.removeAll { it.actor.actorId == actorUri && it.activityUri == activityUri }
        val removedRemote = remoteRows.removeAll { it.actorUri == actorUri && it.activityUri == activityUri }
        return removedLocal || removedRemote
    }

    override fun removeActor(actorUri: String): Int {
        val before = rows.size + remoteRows.size
        rows.removeAll { it.actor.actorId == actorUri }
        remoteRows.removeAll { it.actorUri == actorUri }
        return before - rows.size - remoteRows.size
    }

    override fun findPublicKeyPem(actorUri: String): String? =
        rows.firstOrNull { it.actor.actorId == actorUri }?.actor?.publicKeyPem
}
