package net.matsudamper.activitypub.note

import java.time.Instant
import kotlinx.coroutines.sync.Semaphore
import net.matsudamper.activitypub.activity.CreateNoteActivity
import net.matsudamper.activitypub.actor.ActorUrls
import net.matsudamper.activitypub.delivery.ActivityDelivery
import net.matsudamper.activitypub.delivery.DeliveryResult
import net.matsudamper.activitypub.entity.PublicNoteId
import net.matsudamper.activitypub.json.AppJson
import net.matsudamper.activitypub.url.WebPageUrls
import org.slf4j.LoggerFactory

/**
 * フォローが成立した相手にだけ、フォローより前の投稿を配る。
 *
 * Mastodon も Misskey も、フォローを受け付けたときに過去の `Create` を送り直さない。
 * 相手が `outbox` を遡って取り込むことも保証されていないので、何もしないと
 * フォロー直後のタイムラインは空になり、次の新着が出るまで何も見えない。
 *
 * 送るのは保存済みの投稿から組み立て直した元と同じ `Create{Note}` で、
 * `Create.id` と `Note.id` と `published` は初回配信と一致する。相手はこの id で
 * 重複を判断するので、既に持っている投稿を二重に並べずに済む。
 *
 * 古い順に送る。相手は受け取り順ではなく `published` で並べるが、
 * 途中で失敗したときに新しい方から欠けるより、古い方から欠ける方が読める形になる。
 */
class FollowBackfillPublisher(
    private val notes: NoteStore,
    private val delivery: ActivityDelivery,
    private val webPages: WebPageUrls?,
) {
    private val logger = LoggerFactory.getLogger(FollowBackfillPublisher::class.java)

    /**
     * 同時に走る配信の数。
     *
     * 署名を作れる相手なら、別々のアクターから `Follow` を並べて送るだけで
     * この配信を好きなだけ起こせる。空きが無ければ配らない。待ち行列にすると、
     * 送りつけられた分がそのまま溜まって同じことになる
     */
    private val running = Semaphore(permits = MAX_CONCURRENT_BACKFILLS)

    /**
     * @param inbox 新しいフォロワーの inbox。`sharedInbox` は使わない。
     *   まとめて送ると、同じサーバーの他のフォロワーにも同じ投稿が届く
     * @param publishedBefore フォローが成立した時刻。これ以降の投稿は通常の配信で
     *   届くので送らない。含めると、その分だけフォロー前の投稿が上限から押し出される
     */
    suspend fun deliverRecentNotes(
        sender: ActorUrls,
        inbox: String,
        publishedBefore: Instant,
    ) {
        if (!running.tryAcquire()) {
            // 配れなくてもフォローは成立している。次の新着からは普通に届く
            logger.warn("過去の投稿を配る余裕が無いので諦めた: ${sender.acct} → $inbox")
            return
        }

        try {
            deliver(sender = sender, inbox = inbox, publishedBefore = publishedBefore)
        } finally {
            running.release()
        }
    }

    private suspend fun deliver(
        sender: ActorUrls,
        inbox: String,
        publishedBefore: Instant,
    ) {
        // 上限で切ってから絞ると、成立後に作られた投稿の分だけ送る件数が減る。
        // 一覧の位置として渡して、絞ってから 5 件を取る。id は空にする。
        // 同じ時刻の投稿は id の降順に並ぶので、空の id より後ろには何も無い
        val recentNotes = notes.list(
            username = sender.username,
            after = NotePosition(publishedAt = publishedBefore, publicId = PublicNoteId("")),
            limit = BACKFILL_LIMIT,
        )
        if (recentNotes.isEmpty()) return

        var delivered = 0
        recentNotes.reversed().forEach { note ->
            // 送っている間に管理画面から消されることがある。消えた投稿を送ると、
            // 相手には `Delete` の後から `Create` が届いて消したものが戻る
            if (notes.find(note.publicId) == null) return@forEach

            val body = AppJson.encodeToString(
                CreateNoteActivity.serializer(),
                CreateNoteActivityFactory.create(sender = sender, note = note, webPages = webPages),
            ).toByteArray()

            when (val result = delivery.deliver(inbox = inbox, sender = sender, body = body)) {
                is DeliveryResult.Delivered -> delivered++

                is DeliveryResult.Failed -> {
                    // 再送はしない。届かなくてもフォロー自体は成立しているので、
                    // 次の新着からは普通に届く
                    logger.warn("過去の投稿を配れなかった: ${sender.acct} → $inbox ${note.publicId} ${result.reason}")
                }
            }
        }

        logger.info("フォローされたので過去の投稿を配った: ${sender.acct} → $inbox 宛先=${recentNotes.size} 成功=$delivered")
    }

    private companion object {
        /**
         * 送る件数の上限。
         *
         * 全件送ると、記事の多いフィードでは 1 回のフォローで数百通が同じ相手に飛び、
         * 相手のインスタンスから見ると連投と変わらない。フォロー直後に
         * タイムラインが埋まる程度の件数にする
         */
        const val BACKFILL_LIMIT: Int = 5

        /**
         * 同時に走らせる数。通常のフォローは重ならないので、
         * 並べて送られたときに外向きの HTTP が際限なく増えないことだけを見る
         */
        const val MAX_CONCURRENT_BACKFILLS: Int = 2
    }
}
