package net.matsudamper.kotpub.note

import java.time.Instant
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
     * `Update` 自身の id。書き換えるたびに別の id にしないと、相手は 2 度目からを重複として捨てる。
     * [createId] と同じ理由でフラグメントを付ける
     */
    fun updateId(updatedAt: Instant): ActivityPubId = ActivityPubId("$noteUrl#updates/${updatedAt.toEpochMilli()}")

    /**
     * この投稿の引用を認めた `QuoteAuthorization` の id。相手は引用を確かめるときにここを取得しに来る
     *
     * @param token 引用した相手と投稿を入れて署名した値
     */
    fun quoteAuthorizationUrl(token: String): String = "$noteUrl$QUOTE_AUTHORIZATIONS_PATH$token"

    companion object {
        private const val NOTES_PATH = "/notes/"
        private const val QUOTE_AUTHORIZATIONS_PATH = "/quote_authorizations/"

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
