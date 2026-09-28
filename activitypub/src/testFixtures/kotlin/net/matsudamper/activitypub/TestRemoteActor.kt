package net.matsudamper.activitypub

import java.security.KeyPair
import net.matsudamper.activitypub.actor.RemoteActor
import net.matsudamper.activitypub.actor.RemoteActorProfile
import net.matsudamper.activitypub.crypto.RsaKeys

/**
 * 署名付きのリクエストを送ってくる相手の想定。
 *
 * 2048bit の鍵生成はテストごとに走らせると無視できない時間になるので共有する。
 */
object TestRemoteActor {
    const val ACTOR_ID: String = "https://remote.example/users/alice"
    const val KEY_ID: String = "$ACTOR_ID#main-key"
    const val INBOX: String = "$ACTOR_ID/inbox"

    val keyPair: KeyPair by lazy { RsaKeys.generateKeyPair() }

    /**
     * 何も名乗っていない相手のプロフィール。
     * 一覧の表示を見ないテストは、相手が名乗ったかどうかで変わらない
     */
    val noProfile: RemoteActorProfile =
        RemoteActorProfile(
            preferredUsername = null,
            displayName = null,
            profileUrl = null,
            iconUrl = null,
        )

    val actor: RemoteActor by lazy {
        RemoteActor(
            actorId = ACTOR_ID,
            inbox = INBOX,
            sharedInbox = null,
            publicKeyPem = RsaKeys.encodeToPem(keyPair.public),
            profile = noProfile,
        )
    }

    /** この相手だけを引ける [RemoteActors][net.matsudamper.activitypub.actor.RemoteActors] */
    fun remoteActors(inbox: String? = INBOX): TestRemoteActors =
        TestRemoteActors.of(
            keyId = KEY_ID,
            owner = ACTOR_ID,
            publicKey = keyPair.public,
            inbox = inbox,
        )
}
