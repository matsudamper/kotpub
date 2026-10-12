package net.matsudamper.activitypub

import java.time.Instant
import net.matsudamper.activitypub.actor.RemoteActor
import net.matsudamper.activitypub.actor.RemoteActorProfile
import net.matsudamper.activitypub.follower.FollowerStore

/**
 * フォロワーの記録の差し替え。オンメモリで持つ。
 *
 * SQL の振る舞いは `:backend:repository` のテストが本物の SQLite で確かめる。
 * こちらが受け持つのは、inbox のハンドラや配信が何をどの順で呼んだかの確認。
 *
 * @param failOnRecord 記録に失敗する状況を作る
 * @param deletedUsernames 消えたアカウント。宛先がもう無い状況を作る
 */
class FakeFollowerStore(
    private val failOnRecord: Boolean = false,
    private val deletedUsernames: Set<String> = emptySet(),
) : FollowerStore {
    val rows: MutableList<Row> = mutableListOf()

    override fun record(
        username: String,
        follower: RemoteActor,
        followActivityUri: String,
        receivedAt: Instant,
        acceptBody: String,
    ): Boolean {
        if (failOnRecord) throw IllegalStateException("記録に失敗した想定")
        if (username in deletedUsernames) return false

        // 一意制約と同じ判定。同じ相手からの Follow が既にあれば、行は増やさず、
        // 相手の情報と最後に受けた Follow の id、預かる `Accept` を差し替える。
        // 成立したかどうかは触らない
        val index = rows.indexOfFirst { it.username == username && it.followerActorUri == follower.actorId }
        if (index >= 0) {
            rows[index] = rows[index].copy(
                inbox = follower.inbox,
                sharedInbox = follower.sharedInbox,
                publicKeyPem = follower.publicKeyPem,
                profile = follower.profile,
                followActivityUri = followActivityUri,
                acceptBody = acceptBody,
            )
            return true
        }

        rows += Row(
            username = username,
            followerActorUri = follower.actorId,
            inbox = follower.inbox,
            sharedInbox = follower.sharedInbox,
            publicKeyPem = follower.publicKeyPem,
            profile = follower.profile,
            followActivityUri = followActivityUri,
            acceptBody = acceptBody,
            accepted = false,
        )

        return true
    }

    /**
     * 預かった `Accept` が相手に届いた状況を作る。本物では配信キューがここを書く
     */
    fun markAccepted(
        username: String,
        followerActorUri: String,
    ) {
        val index = rows.indexOfFirst { it.username == username && it.followerActorUri == followerActorUri }
        if (index < 0) return

        rows[index] = rows[index].copy(accepted = true)
    }

    override fun remove(
        username: String,
        followerActorUri: String,
        followActivityUri: String?,
    ): Boolean = rows.removeAll { row ->
        row.username == username &&
            row.followerActorUri == followerActorUri &&
            (followActivityUri == null || row.followActivityUri == followActivityUri)
    }

    override fun findFolloweeUsername(
        followerActorUri: String,
        followActivityUri: String,
    ): String? = rows.firstOrNull { it.followerActorUri == followerActorUri && it.followActivityUri == followActivityUri }?.username

    override fun removeAccount(username: String): Int {
        val before = rows.size
        rows.removeAll { it.username.equals(username, ignoreCase = true) }
        return before - rows.size
    }

    override fun removeRemoteActor(actorUri: String): Int {
        val before = rows.size
        rows.removeAll { it.followerActorUri == actorUri }
        return before - rows.size
    }

    override fun findPublicKeyPem(actorUri: String): String? =
        rows.firstOrNull { it.followerActorUri == actorUri }?.publicKeyPem

    override fun rememberPublicKeyPem(
        actorUri: String,
        publicKeyPem: String,
    ) {
        rows.replaceAll { row ->
            if (row.followerActorUri == actorUri) row.copy(publicKeyPem = publicKeyPem) else row
        }
    }

    override fun rememberProfile(
        actorUri: String,
        profile: RemoteActorProfile,
    ) {
        rows.replaceAll { row ->
            if (row.followerActorUri == actorUri) row.copy(profile = profile) else row
        }
    }

    override fun list(
        username: String,
        after: String?,
        limit: Int,
    ): List<String> = rows
        .filter { it.username == username && it.accepted }
        .map { it.followerActorUri }
        .sorted()
        .filter { after == null || it > after }
        .take(limit)

    override fun count(username: String): Long = rows.count { it.username == username && it.accepted }.toLong()

    override fun deliveryTargets(username: String): List<String> = rows
        .filter { it.username == username && it.accepted }
        .map { it.sharedInbox ?: it.inbox }
        .distinct()

    data class Row(
        val username: String,
        val followerActorUri: String,
        val inbox: String,
        val sharedInbox: String?,
        val publicKeyPem: String,
        val profile: RemoteActorProfile,
        val followActivityUri: String,
        val acceptBody: String,
        val accepted: Boolean,
    )
}
