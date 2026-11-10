package net.matsudamper.kotpub.actor

import net.matsudamper.kotpub.emoji.CustomEmoji
import net.matsudamper.kotpub.httpsignature.PublicKeys

/**
 * 相手のサーバーからアクターを取得するインターフェース。
 *
 * 公開鍵も inbox も同じアクター文書の中にあり、取得する手順も
 * 安全のための決まりも共通なので 1 つのインターフェースにまとめる。
 *
 * 署名の検証だけを見る側からは [PublicKeys] として渡す。検証は
 * 「鍵をどこから持ってくるか」と関係が無いので、そちらのインターフェースは狭いままにしておく。
 */
interface RemoteActors :
    PublicKeys,
    AutoCloseable {
    /**
     * アクター文書から、配信と記録に要る部分を取得する。取得できなければ null。
     * 戻り値の actorId は取得した文書の id。id が無い、HTTPS でない、取得先とホストが違う場合は null。
     *
     * 1 回の呼び出しで全部返すのは、これらが同じ 1 つの文書から読めるため。
     * フォロワーとして記録するときに inbox と公開鍵を別々に取得すると、
     * 相手のサーバーへの GET が増えるうえ、間に鍵が入れ替わると
     * 記録した鍵と inbox が別のバージョンのものになる。
     */
    suspend fun findActor(actorId: String): RemoteActor?

    /**
     * 人が入力した URL から、それが指すアクターを [findActor] と同じ形で取得する。
     *
     * Mastodon や Misskey は、他のサーバーのユーザーを表示するプロフィールの URL へのアクター文書の要求を、
     * そのユーザーのホストのアクターへリダイレクトする。[findActor] は別のホストへ移った応答を捨てるので、
     * 移った先の URL から [findActor] で取得し直す。
     * 移った先の文書は移った先のホストを基準に確かめるので、リダイレクトしたホストが他所のアクターを名乗ることはできない
     */
    suspend fun findActorByUrl(url: String): RemoteActor? = findActor(url)
}

/**
 * 相手のアクター文書のうち、こちらが使う部分。
 *
 * @param actorId 取得した文書の id。取得先と同じホストの HTTPS URL
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
 * @param headerUrl ヘッダー画像の取得元。ActivityStreams の `image`。別ドメインの CDN を指すことがある
 * @param emojis 表示名や自己紹介に `:name:` で入るカスタム絵文字
 */
data class RemoteActorProfile(
    val preferredUsername: String?,
    val displayName: String?,
    val profileUrl: String?,
    val iconUrl: String?,
    val headerUrl: String?,
    val summary: String? = null,
    val emojis: List<CustomEmoji>,
)
