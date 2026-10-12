package net.matsudamper.activitypub

import java.security.PublicKey
import net.matsudamper.activitypub.actor.RemoteActor
import net.matsudamper.activitypub.actor.RemoteActors
import net.matsudamper.activitypub.crypto.RsaKeys
import net.matsudamper.activitypub.httpsignature.PublicKeyLookup
import net.matsudamper.activitypub.httpsignature.SignatureKey

/**
 * 相手のアクターの引き先の差し替え。
 *
 * 本番は相手のサーバーに GET しに行くので、テストからは必ずこちらを通す。
 * 何も渡さなければ「どの keyId も引けず、どの inbox も分からない」サーバーになる。
 *
 * @param missing 引けない keyId をどう答えるか。既定は取りに行けなかった扱い。
 *   アカウントが消えた相手を作るときは [PublicKeyLookup.Gone] を渡す
 */
class TestRemoteActors(
    private val keys: Map<String, SignatureKey> = emptyMap(),
    private val actors: Map<String, RemoteActor> = emptyMap(),
    private val missing: PublicKeyLookup = PublicKeyLookup.Unavailable,
) : RemoteActors {
    var findCallCount: Int = 0
        private set

    var refreshCallCount: Int = 0
        private set

    override suspend fun find(keyId: String): PublicKeyLookup {
        findCallCount++
        return keys[keyId]?.let { PublicKeyLookup.Found(it) } ?: missing
    }

    /**
     * 取り直しても同じ鍵を返す。鍵が入れ替わる状況は、差し替えたい側が
     * [PublicKeys][net.matsudamper.activitypub.httpsignature.PublicKeys] を
     * 自前で用意して作る
     */
    override suspend fun refresh(keyId: String): PublicKeyLookup {
        refreshCallCount++
        return keys[keyId]?.let { PublicKeyLookup.Found(it) } ?: missing
    }

    override suspend fun findActor(actorId: String): RemoteActor? = actors[actorId]

    override fun close() {
    }

    companion object {
        fun of(
            keyId: String,
            owner: String,
            publicKey: PublicKey,
            inbox: String? = null,
        ): TestRemoteActors =
            TestRemoteActors(
                keys = mapOf(keyId to SignatureKey(keyId = keyId, owner = owner, publicKey = publicKey)),
                actors = if (inbox == null) {
                    emptyMap()
                } else {
                    mapOf(
                        owner to RemoteActor(
                            actorId = owner,
                            inbox = inbox,
                            sharedInbox = null,
                            publicKeyPem = RsaKeys.encodeToPem(publicKey),
                            profile = TestRemoteActor.noProfile,
                        ),
                    )
                },
            )
    }
}
