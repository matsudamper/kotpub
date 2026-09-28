package net.matsudamper.activitypub.httpsignature

import java.security.PublicKey

/**
 * `keyId` から検証用の公開鍵を引く。
 *
 * ActivityPub では相手のアクター文書を GET して `publicKey` を読むことになるが、
 * 署名の検証そのものは「鍵をどこから持ってくるか」と関係が無い。
 * ここを口にしておくと、検証のテストがネットワークなしで書ける。
 */
interface PublicKeys {
    /**
     * 引けなければ [PublicKeyLookup.Gone] か [PublicKeyLookup.Unavailable]。
     * どちらでも検証は通らないが、区別しないと「消えた相手」と
     * 「今だけ引けない相手」が同じ扱いになる
     */
    suspend fun find(keyId: String): PublicKeyLookup

    /**
     * 覚えているものを捨てて引き直す。呼んでよいのは署名の検証に失敗したときだけ。
     *
     * 相手が鍵を替えると、覚えている鍵では検証が通らなくなる。替わったことは
     * 通らない署名が届いて初めて分かるので、失敗そのものが取り直す合図になる。
     *
     * 通らない署名を投げ込めば誰でも呼ばせられる。相手のサーバーへの GET を
     * 増やす踏み台にされないよう、実装側で間隔を空けること。間隔の中で呼ばれた
     * 場合は取りに行かずに [PublicKeyLookup.Unavailable] を返してよい
     */
    suspend fun refresh(keyId: String): PublicKeyLookup
}

/**
 * [PublicKeys.find] と [PublicKeys.refresh] の結果
 */
sealed interface PublicKeyLookup {
    data class Found(
        val key: SignatureKey,
    ) : PublicKeyLookup

    /**
     * 相手のサーバーが「そのアクターはもう無い」と答えた。
     *
     * この先この `keyId` の鍵が取れることはない
     */
    data object Gone : PublicKeyLookup

    /**
     * 取りに行けなかった。相手が落ちている、応答が読めない、鍵が入っていない、など。
     *
     * 消えたのかどうかは分からないので、消えた前提の扱いをしてはいけない
     */
    data object Unavailable : PublicKeyLookup
}

/**
 * 検証に使う鍵と、その持ち主。
 *
 * @param keyId 引くのに使った `keyId`
 * @param owner この鍵を持つアクターの id。署名した相手が誰なのかはこれで決まる。
 *   アクティビティの `actor` と突き合わせて、他人になりすました投稿を弾く
 */
class SignatureKey(
    val keyId: String,
    val owner: String,
    val publicKey: PublicKey,
)
