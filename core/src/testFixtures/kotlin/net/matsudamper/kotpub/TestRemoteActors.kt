package net.matsudamper.kotpub

import java.security.PublicKey
import net.matsudamper.kotpub.actor.RemoteActor
import net.matsudamper.kotpub.actor.RemoteActorLookup
import net.matsudamper.kotpub.actor.RemoteActors
import net.matsudamper.kotpub.crypto.RsaKeys
import net.matsudamper.kotpub.http.HttpStatusCodes
import net.matsudamper.kotpub.http.RemoteFetchFailure
import net.matsudamper.kotpub.httpsignature.PublicKeyLookup
import net.matsudamper.kotpub.httpsignature.SignatureKey

/**
 * 相手のアクターの取得元の差し替え。
 *
 * 本番は相手のサーバーに GET しに行くので、テストからは必ずこちらを通す。
 * 何も渡さなければ「どの keyId も取得できず、どの inbox も分からない」サーバーになる。
 *
 * @param missing 取得できない keyId をどう答えるか。null なら一時的に取得できなかった扱い。
 *   アカウントが消えた相手を作るときは [PublicKeyLookup.Gone] を渡す
 */
class TestRemoteActors(
    private val keys: Map<String, SignatureKey> = emptyMap(),
    private val actors: Map<String, RemoteActor> = emptyMap(),
    private val missing: PublicKeyLookup? = null,
) : RemoteActors {
    var findCallCount: Int = 0
        private set

    var refreshCallCount: Int = 0
        private set

    override suspend fun find(keyId: String): PublicKeyLookup {
        findCallCount++
        return keys[keyId]?.let { PublicKeyLookup.Found(it) } ?: missing(keyId)
    }

    /**
     * 取得し直しても同じ鍵を返す。鍵が入れ替わる状況は、差し替えたい側が
     * [PublicKeys][net.matsudamper.kotpub.httpsignature.PublicKeys] を
     * 自前で用意して作る
     */
    override suspend fun refresh(keyId: String): PublicKeyLookup {
        refreshCallCount++
        return keys[keyId]?.let { PublicKeyLookup.Found(it) } ?: missing(keyId)
    }

    private fun missing(keyId: String): PublicKeyLookup =
        missing ?: PublicKeyLookup.Unavailable(RemoteFetchFailure.UnsuccessfulStatus(keyId, HttpStatusCodes.SERVICE_UNAVAILABLE))

    override suspend fun findActor(actorId: String): RemoteActorLookup =
        actors[actorId]?.let { RemoteActorLookup.Found(it) }
            ?: RemoteActorLookup.Unavailable(RemoteFetchFailure.UnsuccessfulStatus(actorId, HttpStatusCodes.SERVICE_UNAVAILABLE))

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
