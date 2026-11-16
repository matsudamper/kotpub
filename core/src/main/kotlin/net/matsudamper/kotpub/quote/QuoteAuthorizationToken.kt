package net.matsudamper.kotpub.quote

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import net.matsudamper.kotpub.actor.LocalActor

/**
 * [QuoteAuthorization] の URL の末尾に入れる値。
 *
 * 承認は記録せず、引用した相手と引用した投稿をこの値に入れ、取得されたときに組み立て直す。
 * 誰でも作れる値にすると、承認していない引用やブロックした相手の引用にも承認を返すことになるので、
 * 引用された投稿の持ち主の秘密鍵から作った鍵で署名する。
 * 秘密鍵から作るのは、署名のためだけの秘密を別に持たずに済ませるため
 *
 * @param quoterActorId 引用した相手のアクター id
 * @param interactingObject 引用した投稿の id
 */
internal data class QuoteAuthorizationToken(
    val quoterActorId: String,
    val interactingObject: String,
) {
    /**
     * @param interactionTarget 引用された投稿の id。署名に含めて、別の投稿の承認に使い回させない
     */
    fun encode(
        author: LocalActor,
        interactionTarget: String,
    ): String {
        val payload = "${encoder.encodeToString(quoterActorId.toByteArray(Charsets.UTF_8))}$SEPARATOR" +
            encoder.encodeToString(interactingObject.toByteArray(Charsets.UTF_8))
        return "$payload$SEPARATOR${encoder.encodeToString(sign(author, interactionTarget, payload))}"
    }

    companion object {
        private const val SEPARATOR = '.'
        private const val MAC_ALGORITHM = "HmacSHA256"
        private const val KEY_LABEL = "kotpub quote authorization\n"

        private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

        /**
         * 署名が合わない、または読めなければ null
         */
        fun decode(
            author: LocalActor,
            interactionTarget: String,
            token: String,
        ): QuoteAuthorizationToken? {
            val parts = token.split(SEPARATOR)
            if (parts.size != 3) return null
            val (quoter, interactingObject, signature) = parts
            val decoder = Base64.getUrlDecoder()
            val actualSignature = runCatching { decoder.decode(signature) }.getOrNull() ?: return null
            val expectedSignature = sign(author, interactionTarget, "$quoter$SEPARATOR$interactingObject")
            if (!MessageDigest.isEqual(expectedSignature, actualSignature)) return null

            return runCatching {
                QuoteAuthorizationToken(
                    quoterActorId = decoder.decode(quoter).toString(Charsets.UTF_8),
                    interactingObject = decoder.decode(interactingObject).toString(Charsets.UTF_8),
                )
            }.getOrNull()
        }

        private fun sign(
            author: LocalActor,
            interactionTarget: String,
            payload: String,
        ): ByteArray {
            val mac = Mac.getInstance(MAC_ALGORITHM)
            mac.init(SecretKeySpec(signingKey(author), MAC_ALGORITHM))
            mac.update("$interactionTarget\n".toByteArray(Charsets.UTF_8))
            return mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        }

        private fun signingKey(author: LocalActor): ByteArray =
            MessageDigest.getInstance("SHA-256").run {
                update(KEY_LABEL.toByteArray(Charsets.UTF_8))
                digest(author.privateKey.privateKey.encoded)
            }
    }
}
