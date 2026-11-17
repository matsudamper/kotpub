package net.matsudamper.kotpub.actor

/**
 * サーバー自身を表すアクター（Mastodon の instance actor と同じもの）。
 *
 * 他のサーバーへの GET の署名に使う。アカウントの鍵で署名すると、相手からはそのアカウントが
 * 取得したように見え、そのアカウントがブロックされているだけで取得できなくなる。
 *
 * @param privateKey 署名に使う鍵。相手は [InstanceActorUrls.publicKeyId] から公開鍵を取りに来るので、
 *   入れ替えると署名済みの取得が鍵のキャッシュが切れるまで通らなくなる
 */
data class InstanceActor(
    val urls: InstanceActorUrls,
    val privateKey: ActorPrivateKey,
)

/**
 * サーバー自身のアクターの識別子と URL。Mastodon の instance actor に合わせる。
 *
 * アカウントの名前は `/users/{username}` に出るので、`/actor` と重ならない
 */
data class InstanceActorUrls(
    val domain: String,
) {
    /** 名前はドメインにする。WebFinger は `acct:{domain}@{domain}` で引ける */
    val preferredUsername: String = domain

    val acct: String = "acct:$domain@$domain"

    val actorId: String = "https://$domain$ACTOR_PATH"

    /** 署名の `keyId` */
    val publicKeyId: String = "$actorId#main-key"

    /** 宛先は共有 inbox にする。このアクター宛ての配送は無いので専用の inbox を持たない */
    val sharedInbox: String = "https://$domain${ActorUrls.SHARED_INBOX_PATH}"

    companion object {
        const val ACTOR_PATH: String = "/actor"
    }
}
