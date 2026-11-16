package net.matsudamper.kotpub.note

import java.util.Base64
import net.matsudamper.kotpub.entity.ActivityPubId
import net.matsudamper.kotpub.entity.PublicNoteId

/**
 * 投稿の URL。
 *
 * アクターの URL と同じく、組み立てを散らすと 1 か所だけ違う文字列になり、
 * 相手のキャッシュに残って後から直せない。組み立てはここに集約する。
 *
 * アカウントの名前をパスに入れないのは、名前が変わっても投稿の URL が
 * 変わらないようにするため。相手は受け取った id をそのまま覚えている。
 */
data class NoteUrls(
    val domain: String,
    val publicId: PublicNoteId,
) {
    /**
     * 投稿の URL。これがそのまま投稿の id になる
     */
    val noteUrl: String = "https://$domain$NOTES_PATH${publicId.value}"

    val noteId: ActivityPubId = ActivityPubId(noteUrl)

    /**
     * `Create` 自身の id。GET できる文書があると読める形にしないためフラグメントを付ける
     */
    val createId: ActivityPubId = ActivityPubId("$noteUrl#create")

    /**
     * `Delete` 自身の id。[createId] と同じ理由でフラグメントを付ける
     */
    val deleteId: ActivityPubId = ActivityPubId("$noteUrl#delete")

    /**
     * この投稿の引用を認めた `QuoteAuthorization` の id。相手は引用を確かめるときにここを取得しに来る。
     *
     * 承認は記録せず、引用した投稿の id を URL に入れて、取得されたときに組み立て直す。
     * 投稿は誰にでも引用を許しているので、記録が無くても認めた内容は変わらない。
     * 投稿を消せば取得できなくなり、相手は引用の承認が取り消されたと扱う
     *
     * @param interactingObject 引用した投稿の id
     */
    fun quoteAuthorizationUrl(interactingObject: String): String =
        "$noteUrl$QUOTE_AUTHORIZATIONS_PATH${quoteTokenEncoder.encodeToString(interactingObject.toByteArray(Charsets.UTF_8))}"

    companion object {
        private const val NOTES_PATH = "/notes/"
        private const val QUOTE_AUTHORIZATIONS_PATH = "/quote_authorizations/"

        private val quoteTokenEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

        /**
         * [quoteAuthorizationUrl] の末尾から、引用した投稿の id を読み戻す。読めなければ null
         */
        fun interactingObjectOf(quoteAuthorizationToken: String): String? =
            runCatching { Base64.getUrlDecoder().decode(quoteAuthorizationToken).toString(Charsets.UTF_8) }
                .getOrNull()
                ?.takeIf { it.startsWith("https://") }

        /**
         * こちらのドメインの投稿の URL でなければ null
         */
        fun publicIdOf(
            domain: String,
            url: String,
        ): PublicNoteId? {
            val prefix = "https://$domain$NOTES_PATH"
            if (!url.startsWith(prefix)) return null

            val publicId = url.removePrefix(prefix)
            if (publicId.isEmpty() || publicId.any { it == '/' || it == '?' || it == '#' }) return null

            return PublicNoteId(publicId)
        }
    }
}
