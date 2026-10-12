package net.matsudamper.activitypub.follower

import java.time.Instant
import net.matsudamper.activitypub.actor.RemoteActor
import net.matsudamper.activitypub.actor.RemoteActorProfile

/**
 * フォロワーの記録の置き先。
 *
 * どこに保存されているかはこのモジュールの関心ではないので、
 * `StoredActorNames` と同じく口だけを決めておく。実装は `:backend` が
 * repository に繋ぐ。ActivityPub 側が DB を知ると、フォローの扱いを変えるたびに
 * テーブルの都合が混ざってくる。
 *
 * こちらのアカウントは名前で指す。引き当ての正は
 * [net.matsudamper.activitypub.actor.ActorDirectory] で、その結果の綴りが渡ってくる。
 *
 * `Accept` を返す前のフォローも記録する。相手から見ると成立していないので
 * [list] と [count] と [deliveryTargets] には出さないが、送り直されたときに
 * 行を増やさないために覚えておく必要がある。
 */
interface FollowerStore {
    /**
     * `Follow` を受けたことを記録して、`Accept` の送り出しを預ける。
     *
     * 同じ相手からの記録が既にあれば行は増やさない。まだ送れていない `Accept` が
     * 残っていれば、最後に受けた `Follow` への `Accept` で置き換える。
     *
     * 記録と送り出しは一方だけが残らない。記録できなかったフォローに `Accept` を
     * 返すと、相手だけがフォローできたつもりになり、こちらには送り先が残らない。
     *
     * フォローが成立するのは `Accept` を送れたときで、[list] と [count] と
     * [deliveryTargets] に出るのもそこから。送れるまで何度送り直すかは実装側が決める。
     *
     * 消したアカウント宛は記録しない。`Follow` を処理している間に消されることがある。
     *
     * @param acceptBody 相手に返す `Accept` の JSON
     * @return 記録したら true。宛先のアカウントが消えていれば false
     */
    fun record(
        username: String,
        follower: RemoteActor,
        followActivityUri: String,
        receivedAt: Instant,
        acceptBody: String,
    ): Boolean

    /**
     * フォローを消す。
     *
     * @param followActivityUri 消す対象を元の `Follow` の id で絞る。null なら id を問わない
     * @return 消したら true
     */
    fun remove(
        username: String,
        followerActorUri: String,
        followActivityUri: String?,
    ): Boolean

    fun findFolloweeUsername(
        followerActorUri: String,
        followActivityUri: String,
    ): String?

    /**
     * こちらのアカウントのフォローを全部消す。アカウントを消すときに使う。
     * `Accept` を返せていないものも消える
     *
     * @return 消えた件数
     */
    fun removeAccount(username: String): Int

    /**
     * 相手のアクターごと消す。こちらのどのアカウントをフォローしていたかに関わらず消える。
     *
     * @return 消えたフォローの数
     */
    fun removeRemoteActor(actorUri: String): Int

    /**
     * 相手のアクターの公開鍵の PEM を返す。記録が無ければ null。
     *
     * `Follow` を受けたときに読んだもの。`Accept` を返せていない相手も対象にする。
     * 相手のサーバーから引けなくなった鍵の代わりに使う
     */
    fun findPublicKeyPem(actorUri: String): String?

    /**
     * 記録済みの相手の公開鍵を、読み直したもので置き換える。記録が無ければ何もしない。
     *
     * 相手が鍵を替えた後も [findPublicKeyPem] が最後に読めた鍵を返せるようにする。
     * `Follow` のときの鍵のままだと、消えた後の `Delete` を検証できない
     */
    fun rememberPublicKeyPem(
        actorUri: String,
        publicKeyPem: String,
    )

    /**
     * 一覧に出すプロフィールを、受け取り直したもので置き換える。記録が無ければ何もしない。
     *
     * 表示名もアイコンも相手の都合で変わる。`Follow` を受けたときのままにすると、
     * 一覧に出るのは最初に見た名前で固定される。
     */
    fun rememberProfile(
        actorUri: String,
        profile: RemoteActorProfile,
    )

    /**
     * フォロワーのアクター URL を URL 順に返す。
     *
     * 位置は件数ではなく直前のページの最後の 1 件で指す。件数で数えると、
     * 読んでいる間にフォローや解除が入るたびに位置がずれる。
     *
     * @param after この URL より後ろを返す。null なら先頭から
     */
    fun list(
        username: String,
        after: String?,
        limit: Int,
    ): List<String>

    fun count(username: String): Long

    /**
     * 投稿を配る先の inbox。`sharedInbox` があればそちらにまとまっている
     */
    fun deliveryTargets(username: String): List<String>
}
