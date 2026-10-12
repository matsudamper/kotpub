package net.matsudamper.activitypub.stamp

internal object StampEmoji {
    /**
     * `content` の長さに仕様上の上限は無く、本文と同じだけ入れられる。
     * 公開画面にそのまま出るので、絵文字 1 つとして扱えない長さは受け付けない
     */
    const val MAX_LENGTH: Int = 100

    /**
     * @return 絵文字が入っていなければ null
     */
    fun of(content: String?): String? = content?.trim()?.takeIf { it.isNotEmpty() }
}
