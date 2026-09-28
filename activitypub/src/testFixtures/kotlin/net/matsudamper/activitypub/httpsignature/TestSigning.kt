package net.matsudamper.activitypub.httpsignature

import java.security.PrivateKey
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import net.matsudamper.activitypub.TestRemoteActor
import net.matsudamper.activitypub.crypto.RsaSignature
import net.matsudamper.activitypub.http.RequestHeaders

/**
 * テストから送信側の署名を作る。
 *
 * 相手のサーバーがやることをこちら側で再現するもの。検証の実装をそのまま
 * 呼び返すと同じ間違いを 2 回して素通りするので、[SigningString] だけは共有し、
 * ヘッダの組み立てと Base64 化はここに書く。
 */
object TestSigning {
    /** Mastodon が送ってくる並び */
    val DEFAULT_HEADER_NAMES: List<String> =
        listOf(SigningString.REQUEST_TARGET, "host", "date", "digest")

    /** `Tue, 20 Apr 2021 02:07:55 GMT` の形。日は 2 桁で埋める */
    private val HTTP_DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)

    // ZoneOffset.UTC で組み立てると zzz が GMT ではなく Z になる。
    // RFC 1123 のパーサは Z を受け付けないので、実際に送られてくる GMT に揃える
    private val GMT: ZoneId = ZoneId.of("GMT")

    fun httpDate(instant: Instant): String = HTTP_DATE.format(instant.atZone(GMT))

    /**
     * 署名済みのリクエストヘッダを作る。
     *
     * @param headerNames 署名対象にするヘッダ名。欠けた場合の挙動を確かめるために差し替えられる
     */
    fun headers(
        privateKey: PrivateKey = TestRemoteActor.keyPair.private,
        method: String = "POST",
        requestTarget: String,
        host: String,
        date: Instant,
        body: ByteArray,
        keyId: String = TestRemoteActor.KEY_ID,
        headerNames: List<String> = DEFAULT_HEADER_NAMES,
    ): Map<String, String> {
        val signed =
            buildMap {
                put("Host", host)
                put("Date", httpDate(date))
                if (body.isNotEmpty()) put("Digest", BodyDigest.of(body))
            }

        val request =
            SignedRequest(
                method = method,
                requestTarget = requestTarget,
                headers = RequestHeaders.ofSingleValues(signed),
                body = body,
            )
        val signingString = requireNotNull(SigningString.build(request, headerNames)) { "署名文字列を作れない" }
        val signature = Base64.getEncoder().encodeToString(RsaSignature.sign(privateKey, signingString.toByteArray()))

        return signed +
            mapOf(
                "Signature" to
                    """keyId="$keyId",algorithm="rsa-sha256",""" +
                    """headers="${headerNames.joinToString(" ")}",signature="$signature"""",
            )
    }
}
