package net.matsudamper.kotpub.note

import java.time.Instant
import java.util.Locale
import net.matsudamper.kotpub.emoji.CustomEmoji

interface RemoteNoteStore {
    /**
     * 保存済みの投稿の [ReceivedNote.attributedTo]。保存していなければ null。
     *
     * 受け取った側は、届いた投稿の投稿者が署名者と一致することまでしか確かめられない。
     * 同じホストの別のアクターが他人の投稿 id で Create・Update・Delete を送ると、それも通ってしまう。
     * 保存済みの投稿者と突き合わせて、他人の投稿の上書きと削除を防ぐ
     */
    suspend fun findAttributedTo(noteId: String): String?

    suspend fun save(note: ReceivedNote)

    /**
     * 投稿者が編集した投稿が届いた。保存していない投稿なら何もしない。
     *
     * 配送は順番どおりとは限らないので、[ReceivedNote.updated] が保存済みのものより古ければ捨てる
     */
    suspend fun update(note: ReceivedNote)
    suspend fun delete(actorId: String, objectId: String)
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
