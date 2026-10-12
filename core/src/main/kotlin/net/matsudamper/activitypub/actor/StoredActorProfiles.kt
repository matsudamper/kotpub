package net.matsudamper.activitypub.actor

/**
 * アクターのプロフィールの引き先。
 *
 * どこに保存されているかは Actor 側の関心ではないので、
 * [StoredActorNames] と同じく名前を渡して引けることだけを決めておく。
 */
interface StoredActorProfiles {
    /**
     * 名前で引く。何も設定していないアカウントは [ActorProfile.EMPTY]。
     *
     * 渡すのは [StoredActorNames] が返した保存側の綴り。
     */
    fun find(username: String): ActorProfile
}

/**
 * 管理画面から編集できるプロフィール。
 *
 * @param displayName Actor の `name` に出す表示名。未設定なら null
 * @param summary Actor の `summary` に出す説明文。プレーンテキストで持つ。未設定なら null
 */
data class ActorProfile(
    val displayName: String?,
    val summary: String?,
) {
    companion object {
        val EMPTY: ActorProfile = ActorProfile(displayName = null, summary = null)
    }
}
