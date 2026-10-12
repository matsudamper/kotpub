package net.matsudamper.activitypub.actor

/**
 * アクターのユーザー名の決まり。
 *
 * 保存する名前と、リクエストのパスや WebFinger の `acct:` から来る名前は、
 * 同じ規則で検証する。URL のパスと `acct:` の両方に入るので、区切り文字が混ざると
 * 別のものを指してしまう。
 */
object ActorUsernameUtil {
    /**
     * Mastodon のローカルアカウントの上限に合わせる。長い名前は相手側で扱えない
     */
    const val MAX_LENGTH: Int = 30

    /**
     * 名前の無いアクターは引けない
     */
    const val MIN_LENGTH: Int = 1

    fun isValid(username: String): Boolean =
        username.length in MIN_LENGTH..MAX_LENGTH && unusableCharacters(username).isEmpty()

    /**
     * 使えない文字を重複なく返す。全部使えるなら空。
     *
     * 使えるかどうかは置き場所で変わる。`.` と `-` は名前の間にしか置けず、先頭と末尾は
     * 英数字か `_` だけ。同じ文字でも場所によって結果が変わるので、文字の集合ではなく
     * 1 文字ずつ位置と合わせて見る。
     */
    fun unusableCharacters(username: String): List<Char> =
        username
            .filterIndexed { index, character ->
                // 1 文字の名前は先頭であり末尾でもある
                val edge = index == 0 || index == username.length - 1
                !isUsable(character, edge = edge)
            }.toSet()
            .toList()

    private fun isUsable(
        character: Char,
        edge: Boolean,
    ): Boolean {
        // Kotlin の isLetterOrDigit は Unicode の文字を通すので、範囲で見る
        val alphanumeric = character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9'

        return when {
            alphanumeric || character == '_' -> true
            edge -> false
            else -> character == '.' || character == '-'
        }
    }
}
