package net.matsudamper.kotpub.note

/**
 * プロフィールに固定した投稿の保存先。[NoteStore] と同じく、実装は利用側のアプリが用意する
 */
fun interface FeaturedNoteStore {
    /**
     * 固定した投稿を、プロフィールの上に出す順に返す
     *
     * @param limit 返す件数の上限
     */
    fun featured(
        username: String,
        limit: Int,
    ): List<StoredNote>
}
