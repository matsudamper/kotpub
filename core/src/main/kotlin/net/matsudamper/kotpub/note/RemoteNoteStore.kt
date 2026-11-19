package net.matsudamper.kotpub.note

import java.time.Instant
import java.util.Locale
import net.matsudamper.kotpub.emoji.CustomEmoji

interface RemoteNoteStore {
    /**
     * 保存済みの投稿の [ReceivedNote.attributedTo]。保存していなければ null。
     *
     * 同じホストの別のアクターが他人の投稿 id で送ってきた Create・Update・Delete を見分けるのに使う
     */
    suspend fun findAttributedTo(noteId: String): String?

    /**
     * 保存済みの投稿者が [expectedAttributedTo] のときだけ保存する。null は保存していないことを表す。
     *
     * 呼び出し側は [findAttributedTo] で見た投稿者をもとに受けてよいかを決めている。
     * 決めた後に別の Create・Update・Delete が書き換えていたら、その判断はもう使えないので書かない。
     * 見比べと書き込みは 1 つの操作で行うこと（トランザクションや条件付きの UPDATE など）
     *
     * @return 保存済みの投稿者が食い違って書かなかったら false
     */
    suspend fun save(note: ReceivedNote, expectedAttributedTo: String?): Boolean

    /**
     * 投稿者が編集した投稿が届いた。保存していない投稿なら何もしない。
     *
     * 配送は順番どおりとは限らないので、[ReceivedNote.updated] が保存済みのものより古ければ捨てる。
     * 保存済みの投稿者の見比べは [save] と同じ
     *
     * @return 保存済みの投稿者が食い違って書かなかったら false
     */
    suspend fun update(note: ReceivedNote, expectedAttributedTo: String?): Boolean

    /**
     * 保存済みの投稿者の見比べは [save] と同じ
     *
     * @return 保存済みの投稿者が食い違って消さなかったら false
     */
    suspend fun delete(actorId: String, objectId: String, expectedAttributedTo: String?): Boolean
}

data class ReceivedNote(
    val id: String,
    val attributedTo: String,
    val content: String,
    /**
     * 送り手が付けていないか、言語タグとして読めなければ null
     */
    val language: Locale?,
    val published: Instant?,
    /**
     * 投稿者が最後に編集した時刻。編集していないか、送り手が付けていなければ null
     */
    val updated: Instant?,
    val to: List<String>,
    val cc: List<String>,
    val inReplyTo: String?,
    /**
     * 引用した投稿の id。引用でなければ null
     */
    val quote: String?,
    val summary: String?,
    val sensitive: Boolean,
    val visibility: NoteVisibility,
    val attachments: List<ReceivedNoteAttachment>,
    val emojis: List<CustomEmoji>,
    /**
     * `tag` の `Mention` が指すアクターの id。宛先に入っていても `Mention` が無いアカウントは含めない
     */
    val mentions: List<String>,
    /**
     * `Question` で届いた投稿の投票。投票でなければ null
     */
    val poll: ReceivedPoll?,
    /**
     * `type` が `Link` で届いた投稿のリンク先。`Note` と `Question` なら null
     */
    val linkHref: String?,
    /**
     * 添付・タグのうち、このライブラリが読み方を知らない `type` の名前。重複は除く
     */
    val unknownTypes: List<String>,
)

/**
 * @param mediaType 送り手が付けていなければ null
 * @param name 画像の説明文など。無ければ null
 */
data class ReceivedNoteAttachment(
    val url: String,
    val mediaType: String?,
    val name: String?,
)

enum class NoteVisibility {
    Public,
    Unlisted,
    Followers,
    Direct,
}
