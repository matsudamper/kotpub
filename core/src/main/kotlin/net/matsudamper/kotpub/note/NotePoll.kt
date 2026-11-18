package net.matsudamper.kotpub.note

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * こちらの投稿に付けた投票。
 *
 * @param options 選択肢。並べた順
 * @param multipleChoice 複数選べるか。Mastodon は `anyOf` なら複数、`oneOf` なら 1 つだけ選ばせる
 * @param endsAt 締め切り
 * @param votersCount 投票した人数。複数選べる投票では選択肢ごとの票数の合計と一致しない
 */
data class NotePoll(
    val options: List<NotePollOption>,
    val multipleChoice: Boolean,
    val endsAt: Instant,
    val votersCount: Int,
)

/**
 * @param name 選択肢の文字列。相手はこの文字列をそのまま票として送ってくる
 * @param votesCount この選択肢に入った票数
 */
data class NotePollOption(
    val name: String,
    val votesCount: Int,
)

/**
 * `Question` の `oneOf` / `anyOf` に並べる選択肢 1 つ。Mastodon はこの形でないと選択肢として読まない
 */
@Serializable
data class QuestionOption(
    @SerialName("name")
    val name: String,
    @SerialName("replies")
    val replies: QuestionOptionReplies,
) {
    @SerialName("type")
    val type: String = "Note"
}

/**
 * 選択肢への票数。Mastodon は `totalItems` だけを読む
 */
@Serializable
data class QuestionOptionReplies(
    @SerialName("totalItems")
    val totalItems: Int,
) {
    @SerialName("type")
    val type: String = "Collection"
}

/**
 * 他のサーバーの投稿に付いた投票。
 *
 * @param votesCount 送り手が数を出していなければ null
 */
data class ReceivedPoll(
    val options: List<ReceivedPollOption>,
    val multipleChoice: Boolean,
    /**
     * 送り手が付けていなければ null
     */
    val endsAt: Instant?,
    /**
     * 送り手が付けていなければ null。Mastodon 以外は出さないことがある
     */
    val votersCount: Int?,
)

data class ReceivedPollOption(
    val name: String,
    val votesCount: Int?,
)

/**
 * こちらの投稿の投票に届いた票 1 つ。Mastodon や Misskey は、選んだ選択肢の文字列を `name` に入れ、
 * 投票の投稿を `inReplyTo` で指した、本文の無い `Note` を `Create` で送ってくる。複数選んだら選択肢ごとに 1 つ届く
 *
 * @param id 票の `Note` の id。同じ票が二度届いたときの見分けに使う
 * @param voterActorId 投票したアクターの id。署名者と同じであることは確かめてある
 * @param questionId 投票した投稿の id。こちらの投稿かどうかは確かめていない
 * @param choice 選んだ選択肢の文字列
 */
data class ReceivedPollVote(
    val id: String,
    val voterActorId: String,
    val questionId: String,
    val choice: String,
)

/**
 * 届いた票の記録先。実装は利用側のアプリが用意する
 */
fun interface PollVoteStore {
    /**
     * 投票した投稿がこちらの投稿でないか、締め切りを過ぎているか、選択肢に無い票は記録しない
     */
    suspend fun vote(vote: ReceivedPollVote)
}

internal fun NotePoll.toQuestionOptions(): List<QuestionOption> =
    options.map { option -> QuestionOption(name = option.name, replies = QuestionOptionReplies(totalItems = option.votesCount)) }
