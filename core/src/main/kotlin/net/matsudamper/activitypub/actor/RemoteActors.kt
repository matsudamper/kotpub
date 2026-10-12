package net.matsudamper.activitypub.actor

import net.matsudamper.activitypub.httpsignature.PublicKeys

/**
 * 相手のサーバーのアクターを引く口。
 *
 * 公開鍵も inbox も同じアクター文書の中にあり、取りに行く手順も
 * 安全のための決まりも共通なので 1 つの口にまとめる。
 *
 * 署名の検証だけを見る側からは [PublicKeys] として渡す。検証は
 * 「鍵をどこから持ってくるか」と関係が無いので、そちらの口は狭いままにしておく。
 */
interface RemoteActors :
    PublicKeys,
    AutoCloseable {
    /**
     * アクター文書から、配信と記録に要る部分を引く。引けなければ null。
     *
     * 1 回の呼び出しで全部返すのは、これらが同じ 1 つの文書から読めるため。
     * フォロワーとして記録するときに inbox と公開鍵を別々に取りに行くと、
     * 相手のサーバーへの GET が増えるうえ、間に鍵が入れ替わると
     * 記録した鍵と inbox が別の版のものになる。
     */
    suspend fun findActor(actorId: String): RemoteActor?
}

/**
 * 相手のアクター文書のうち、こちらが使う部分。
 *
 * @param actorId 引いたアクターの id。呼び出しに使った URL がそのまま入る
 * @param inbox 配信先。https で、[actorId] と同じホストのものに限る。
 *   ここを緩めると、相手が自分のアクター文書に他所の URL を書くだけで、
 *   こちらのサーバーから任意の宛先に POST させられる
 * @param sharedInbox 同じインスタンス宛をまとめて送れる inbox。持たない実装もある。
 *   [inbox] と同じ制限がかかる
 * @param publicKeyPem 署名の検証に使う公開鍵の PEM
 * @param profile 一覧に出すためだけの部分。配信にも検証にも関わらない
 */
data class RemoteActor(
    val actorId: String,
    val inbox: String,
    val sharedInbox: String?,
    val publicKeyPem: String,
    val profile: RemoteActorProfile,
)

/**
 * 相手のアクターのうち、人に見せるためだけの部分。
 *
 * 配信にも署名の検証にも使わないので、どれも読めなければ null でよい。
 * [RemoteActor] から分けてあるのは、`Update` で入れ替わるのがここだけのため。
 * inbox と公開鍵は配信と検証に効くので、同じ経路では触らない。
 *
 * @param preferredUsername 相手の acct のうちドメインより前。ドメインは
 *   アクター文書の URL から決まるので持たない
 * @param profileUrl 人が開くプロフィールの URL。アクター文書の URL とは別物
 * @param iconUrl アイコンの取得元。別ドメインの CDN を指すことがある
 */
data class RemoteActorProfile(
    val preferredUsername: String?,
    val displayName: String?,
    val profileUrl: String?,
    val iconUrl: String?,
)
