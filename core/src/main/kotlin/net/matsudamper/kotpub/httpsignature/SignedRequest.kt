package net.matsudamper.kotpub.httpsignature

import net.matsudamper.kotpub.http.RequestHeaders

/**
 * 署名を検証する対象のリクエスト。
 *
 * 検証はサーバーの実装から切り離してテストできるようにしたいので、
 * 必要なものだけを持つこの型を通す。
 *
 * @param method HTTP メソッド。署名文字列では小文字にして使う
 * @param requestTarget パスとクエリ。`/users/admin/inbox` の形。
 *   送信側が署名した文字列と 1 文字でも違うと検証は通らないので、
 *   リクエストラインのものをそのまま渡すこと
 * @param body 受信したボディのバイト列。`Digest` の突き合わせに使う
 */
class SignedRequest(
    val method: String,
    val requestTarget: String,
    val headers: RequestHeaders,
    val body: ByteArray,
)
