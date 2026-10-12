package net.matsudamper.activitypub.inbox

import net.matsudamper.activitypub.crypto.RsaKeys
import net.matsudamper.activitypub.favourite.FavouriteStore
import net.matsudamper.activitypub.follower.FollowerStore
import net.matsudamper.activitypub.httpsignature.PublicKeyLookup
import net.matsudamper.activitypub.httpsignature.PublicKeys
import net.matsudamper.activitypub.httpsignature.SignatureKey
import net.matsudamper.activitypub.stamp.StampStore
import org.slf4j.LoggerFactory

/**
 * 相手が消えて引けなくなった公開鍵を、こちらの記録から引く。
 *
 * ActivityPub で鍵を配る手段はアクター文書しかないので、相手が消えると鍵は
 * どこからも取れなくなる。アカウント削除の `Delete` は本人が消えた後に届くため、
 * 取りに行くだけでは署名を検証できない。フォローとお気に入りとスタンプのどれを受けたときも
 * 相手のアクターを `remote_actors` に記録し、公開鍵もその行に 1 つ持っているので、
 * 記録のある相手ならそれで検証できる。
 * フォロワーだけを見ると、お気に入りやスタンプしか押していない相手の `Delete` を検証できず、
 * 消えた相手のお気に入りやスタンプを消せないまま残すことになる。
 *
 * 記録した鍵を使うのは、相手のサーバーが「もう無い」と答えたときだけにする。
 * 取りに行けなかっただけの場合にも使うと、相手が鍵を替えた後に一時的な障害が
 * 起きている間だけ、失効したはずの古い鍵で署名が通る。その鍵を持っている者は
 * 本人の `Delete` を装ってフォローを消させられる。
 *
 * 引けたときは記録を新しくする。`Follow` を受けたときの鍵のままにしておくと、
 * 相手が鍵を替えてから消えた場合に、記録の鍵では検証できない。
 */
class RecordedFallbackPublicKeys(
    private val remote: PublicKeys,
    private val followers: FollowerStore,
    private val favourites: FavouriteStore,
    private val stamps: StampStore,
) : PublicKeys {
    private val logger = LoggerFactory.getLogger(RecordedFallbackPublicKeys::class.java)

    override suspend fun find(keyId: String): PublicKeyLookup = handle(keyId, remote.find(keyId))

    override suspend fun refresh(keyId: String): PublicKeyLookup = handle(keyId, remote.refresh(keyId))

    private fun handle(
        keyId: String,
        lookup: PublicKeyLookup,
    ): PublicKeyLookup =
        when (lookup) {
            is PublicKeyLookup.Found -> {
                // 記録できなくても、引けた鍵での検証はできる。ここで例外を上げると
                // 書き込めない間、検証を通るはずのアクティビティまで落ちる
                runCatching {
                    followers.rememberPublicKeyPem(
                        // 引き当てるのは keyId の名乗りではなく、検証で決まった持ち主
                        actorUri = lookup.key.owner,
                        publicKeyPem = RsaKeys.encodeToPem(lookup.key.publicKey),
                    )
                }.onFailure { failure ->
                    logger.warn("公開鍵の記録を新しくできなかった: ${lookup.key.owner}", failure)
                }
                lookup
            }

            PublicKeyLookup.Gone -> findRecorded(keyId) ?: PublicKeyLookup.Gone

            PublicKeyLookup.Unavailable -> PublicKeyLookup.Unavailable
        }

    private fun findRecorded(keyId: String): PublicKeyLookup.Found? {
        // `keyId` はアクター id にフラグメントを付けたもの。記録はアクター id で引く
        val actorUri = keyId.substringBefore('#')

        val publicKey =
            (
                followers.findPublicKeyPem(actorUri)
                    ?: favourites.findPublicKeyPem(actorUri)
                    ?: stamps.findPublicKeyPem(actorUri)
                )
                ?.let { runCatching { RsaKeys.decodePublicKeyPem(it) }.getOrNull() }
                ?: return null

        // 持ち主はこちらの記録で決める。相手の文書を取れていない以上、
        // keyId の名乗りを裏付けるものは記録した行しか無い
        return PublicKeyLookup.Found(
            SignatureKey(keyId = keyId, owner = actorUri, publicKey = publicKey),
        )
    }
}
