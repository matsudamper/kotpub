package net.matsudamper.activitypub.note

import net.matsudamper.activitypub.entity.ActivityPubId
import net.matsudamper.activitypub.entity.PublicNoteId

/**
 * 投稿の URL。
 *
 * アクターの URL と同じく、綴りを散らすと 1 か所だけ違う形になり、
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

    companion object {
        private const val NOTES_PATH = "/notes/"

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
