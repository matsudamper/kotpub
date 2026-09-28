package net.matsudamper.activitypub.actor

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.matsudamper.activitypub.http.HttpUrl

/**
 * 相手のアクター文書のうち、こちらが見る部分だけ。
 *
 * こちらが返す [net.matsudamper.activitypub.activitypub.Actor] を使い回さないのは、
 * あちらが「返すときに必ず入れるもの」を必須にしているため。相手の実装が
 * `following` を省略しただけで鍵が読めなくなるのは筋が悪い。
 *
 * 取りに行って読む場合（[HttpRemoteActors]）と、`Update` に丸ごと埋め込まれて
 * 届く場合（[net.matsudamper.activitypub.inbox.UpdateActorHandler]）があるので、
 * 読み取りはここに置いて両方から使う。
 */
@Serializable
internal data class RemoteActorDocument(
    @SerialName("id")
    val id: String? = null,
    @SerialName("inbox")
    val inbox: String? = null,
    @SerialName("publicKey")
    val publicKey: RemoteActorPublicKey? = null,
    @SerialName("endpoints")
    val endpoints: RemoteActorEndpoints? = null,
    /**
     * 表示に使う項目は形を決めずに受ける。ActivityStreams では文字列の場所に
     * 配列を置く実装もあり、型を決めて読むと文書ごと読めなくなる。この文書は
     * 署名の検証と配信先にも使うので、表示のための項目で全体を落とさない
     */
    @SerialName("preferredUsername")
    val preferredUsername: JsonElement? = null,
    /**
     * プロフィールの表示名。ActivityStreams の `name`
     */
    @SerialName("name")
    val displayName: JsonElement? = null,
    /**
     * 人が開くプロフィールの URL。アクター文書の URL とは別で、
     * 文字列のことも Link オブジェクトのこともある
     */
    @SerialName("url")
    val url: JsonElement? = null,
    /**
     * プロフィール画像。`{ type, url }` のことも、配列のことも、
     * URL の文字列だけのこともある
     */
    @SerialName("icon")
    val icon: JsonElement? = null,
) {
    /**
     * 一覧に出すためだけの部分。
     *
     * 名乗っていない実装があるので、読めないものは null にして先へ進む。
     * ここが欠けても配信と署名の検証には何も起きない。
     */
    fun profile(): RemoteActorProfile =
        RemoteActorProfile(
            preferredUsername = preferredUsername.firstText(),
            displayName = displayName.firstText(),
            profileUrl = url.firstHttpsUrl(),
            // inbox と違って取得先と同じホストであることは求めない。
            // 画像を別ドメインの CDN に置く実装があり、同じホストに限ると
            // そこのアイコンが軒並み出なくなる。ここから POST することはないので、
            // 他所のホストでも送信先として使われる危険は無い
            iconUrl = icon.firstHttpsUrl(),
        )
}

/**
 * `endpoints` の中身。`sharedInbox` はここにしか無い。
 *
 * 同じインスタンスに複数のフォロワーがいる場合、1 人ずつ inbox に送る代わりに
 * ここへ 1 回送れば済む。
 */
@Serializable
internal data class RemoteActorEndpoints(
    @SerialName("sharedInbox")
    val sharedInbox: String? = null,
)

@Serializable
internal data class RemoteActorPublicKey(
    @SerialName("id")
    val id: String? = null,
    @SerialName("owner")
    val owner: String? = null,
    @SerialName("publicKeyPem")
    val publicKeyPem: String,
)

/**
 * 文字列が入りうる場所から、空でない文字列を 1 つ読む。読めなければ null。
 *
 * 配列で来たら先頭の使えるものを取る。オブジェクトは読まない
 */
private fun JsonElement?.firstText(): String? =
    when (this) {
        null, JsonNull, is JsonObject -> null
        is JsonPrimitive -> content.takeIf { isString && it.isNotBlank() }
        is JsonArray -> firstNotNullOfOrNull { it.firstText() }
    }

/**
 * URL が入りうる場所から、https の URL を 1 つ読む。読めなければ null。
 *
 * ActivityStreams ではどの形も正しい。文字列で入れる実装、`{ type: Link, href }`
 * で入れる実装、複数を配列で並べる実装があり、どれを使うかは相手次第。
 * 表示にしか使わないので、読めない形は名乗っていないものとして扱う。
 *
 * https でないものは読みながら飛ばす。先頭だけ見て後で落とすと、配列の先頭に
 * 使えない値が入っているだけで、後ろにある使える URL まで捨てることになる。
 *
 * https に限るのは、画面のリンクと画像の取得元になるため。相手が書いた文字列を
 * そのまま通すと、`javascript:` のような scheme がこちらの画面の上で開かれる
 */
private fun JsonElement?.firstHttpsUrl(): String? =
    when (this) {
        null, JsonNull -> null
        is JsonPrimitive -> content.takeIf { isString && it.isHttpsUrl() }
        is JsonArray -> firstNotNullOfOrNull { it.firstHttpsUrl() }
        is JsonObject -> this["href"].firstHttpsUrl() ?: this["url"].firstHttpsUrl()
    }

private fun String.isHttpsUrl(): Boolean = HttpUrl.parseHttps(this) != null
