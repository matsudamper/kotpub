package net.matsudamper.kotpub.note

import java.time.Instant
import java.util.Locale
import net.matsudamper.kotpub.emoji.CustomEmoji

interface RemoteNoteStore {
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
