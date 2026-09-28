package net.matsudamper.activitypub.inbox

import kotlinx.serialization.json.JsonObject
import net.matsudamper.activitypub.activity.InboxActivity
import net.matsudamper.activitypub.activitypub.id
import net.matsudamper.activitypub.actor.ActorDirectory
import net.matsudamper.activitypub.actor.RemoteActors
import net.matsudamper.activitypub.favourite.FavouriteStore
import net.matsudamper.activitypub.follower.FollowerStore
import net.matsudamper.activitypub.httpsignature.HttpSignatureResult
import net.matsudamper.activitypub.httpsignature.HttpSignatureVerifier
import net.matsudamper.activitypub.httpsignature.SignedRequest
import net.matsudamper.activitypub.json.AppJson
import net.matsudamper.activitypub.stamp.StampStore
import org.slf4j.LoggerFactory

/**
 * inbox が受け取ったアクティビティを検証して、種類ごとの処理に渡す。
 *
 * HTTP からは切り離してある。ここでやるのは「受け取ったものを
 * 信用してよいか」の判断と振り分けで、HTTP の status に直すのは [InboxEndpoint] の仕事。
 * 分けておくと、サーバーを立てずに判断だけを確かめられる。
 *
 * 落とした理由は結果に載せずログにだけ出す。相手にどこで落ちたかを教えると、
 * 通る形を総当たりで探す助けになるため。ルート側が理由を知らない形にしておけば、
 * うっかり応答に混ぜることもない。
 */
class InboxService(
    private val verifier: HttpSignatureVerifier,
    private val domainBlocks: InboxDomainBlocks,
    handlers: List<InboxActivityHandler>,
) {
    private val logger = LoggerFactory.getLogger(InboxService::class.java)

    private val handlersByType: Map<String, InboxActivityHandler> =
        handlers.associateBy { it.type }.also {
            // 同じ type のハンドラを 2 つ登録しても、黙って片方が捨てられるだけで
            // 気付けない。処理されないアクティビティは相手側からは無反応に見える
            require(it.size == handlers.size) {
                "同じ type のハンドラが複数ある: ${handlers.map { handler -> handler.type }}"
            }
        }

    /**
     * @param recipient 届いた inbox。アカウントごとの inbox なら、宛先の引き当ては呼び出し側で済ませておく
     * @param request 署名の検証にかけるリクエスト。ボディは受け取り終えたものを渡す
     */
    suspend fun receive(
        recipient: InboxRecipient,
        request: SignedRequest,
    ): InboxResult {
        InboxSpan.set(InboxSpan.RECIPIENT, recipient.logLabel)
        val result = receiveVerified(recipient = recipient, request = request)
        InboxSpan.set(
            InboxSpan.RESULT,
            when (result) {
                InboxResult.Accepted -> "accepted"
                InboxResult.BadRequest -> "bad_request"
                InboxResult.Unauthorized -> "unauthorized"
            },
        )
        return result
    }

    private suspend fun receiveVerified(
        recipient: InboxRecipient,
        request: SignedRequest,
    ): InboxResult {
        // 鍵を取りに行く前に断る。検証してからだと、止めている相手のサーバーに取りに行くことになる。
        // 断っても 202 で返す。4xx や 5xx を返すと、相手は諦めるまで同じものを送り直してくる
        val claimedKeyId = verifier.claimedKeyId(request)
        if (claimedKeyId != null && blocksInboxFrom(claimedKeyId)) {
            InboxSpan.outcome("domain_blocked")
            logger.info("${recipient.logLabel} に受信を止めているドメインから届いたので捨てる: keyId=$claimedKeyId")
            return InboxResult.Accepted
        }

        val verifiedSignerActorId =
            when (val verification = verifier.verify(request)) {
                is HttpSignatureResult.Rejected -> {
                    InboxSpan.set(InboxSpan.SIGNATURE_REJECT_REASON, verification.reason)
                    // 消えたアクターからの Delete だけは、検証できないことを理由に
                    // 落とすと相手が送り直し続ける。削除の通知は本人が消えた後に届き、
                    // 鍵はもう取りに行けない。フォロワーだった相手なら記録した鍵で
                    // 検証できるが、Mastodon は面識の無いサーバーにも配るので、
                    // 記録の無い相手は通しようが無い
                    if (isSelfDelete(request.body)) {
                        logger.info("${recipient.logLabel} に検証できない Delete が届いたので受け流す。理由:${verification.reason}")
                        return InboxResult.Accepted
                    }

                    logger.warn("inbox の署名を拒否した: ${recipient.logLabel} 理由:${verification.reason}")
                    return InboxResult.Unauthorized
                }

                is HttpSignatureResult.Verified -> {
                    verification.owner
                }
            }
        InboxSpan.set(InboxSpan.SIGNER, verifiedSignerActorId)
        recordSignedRequest(verifiedSignerActorId)

        val rawActivityJson =
            runCatching { AppJson.parseToJsonElement(request.body.decodeToString()) as? JsonObject }
                .getOrNull()
        val activity =
            rawActivityJson?.let {
                runCatching { AppJson.decodeFromJsonElement(InboxActivity.serializer(), it) }.getOrNull()
            }

        if (rawActivityJson == null || activity == null) {
            logger.warn("inbox のボディを読めなかった: ${recipient.logLabel} 署名者=$verifiedSignerActorId")
            return InboxResult.BadRequest
        }

        // 署名した鍵の持ち主と、アクティビティの実行者が別なら、なりすまし。
        // 署名だけ通る形は作れるので、ここを見ないと他人の Undo を送り込める
        val actorId = activity.actorId
        if (actorId != null && actorId != verifiedSignerActorId) {
            logger.warn("inbox の actor が署名者と違う: actor=$actorId 署名者=$verifiedSignerActorId")
            return InboxResult.Unauthorized
        }

        InboxSpan.set(InboxSpan.ACTIVITY_TYPE, activity.type)
        InboxSpan.set(InboxSpan.ACTIVITY_ID, activity.id)
        InboxSpan.set(InboxSpan.ACTIVITY_OBJECT, activity.target?.id)

        logger.info(
            "inbox で受信: 宛先=${recipient.logLabel} type=${activity.type} " +
                "id=${activity.id} actor=$verifiedSignerActorId",
        )

        // 引き当てられない type は何もしない。未対応のアクティビティに 5xx を返すと
        // 相手は同じものを送り直し続けることになる
        val handler = handlersByType[activity.type]
        if (handler == null) InboxSpan.outcome("no_handler")
        val handled = runCatching {
            handler?.handle(
                recipient = recipient,
                verifiedSignerActorId = verifiedSignerActorId,
                activity = activity,
                rawActivityJson = rawActivityJson,
            )
        }

        // 処理に失敗しても 202 で返す。5xx にすると相手は同じものを送り直し続けるので、
        // こちらが書き込めない間ずっと同じ失敗を繰り返すことになる。
        // 何が起きたかはここに残っているものが唯一の手がかりになる
        handled.onFailure { failure ->
            InboxSpan.failed(failure)
            logger.warn(
                "inbox の処理に失敗した: ${recipient.logLabel} type=${activity.type} actor=$verifiedSignerActorId",
                failure,
            )
        }

        return InboxResult.Accepted
    }

    /**
     * 引けなければ止めているものとして捨てる。止めていないものとして通すと、止めている相手の
     * アクティビティが処理される。5xx を返すと相手は同じものを送り直し続けるので、落としもしない
     */
    private fun blocksInboxFrom(keyId: String): Boolean =
        runCatching { domainBlocks.blocksInboxFrom(keyId) }
            .onFailure { failure -> logger.warn("受信を止めているドメインかを引けなかったので捨てる: $keyId", failure) }
            .getOrDefault(true)

    /**
     * 受け取ったものの処理とは関係が無いので、落ちても受信は続ける
     */
    private fun recordSignedRequest(verifiedSignerActorId: String) {
        runCatching { domainBlocks.signedRequestReceived(verifiedSignerActorId) }
            .onFailure { failure ->
                logger.warn("署名付きのリクエストが届いたことを記録できなかった: $verifiedSignerActorId", failure)
            }
    }

    /**
     * 署名を検証できなかったボディが、送り主自身の削除の通知かどうか。
     *
     * 検証を通っていないので中身は信用できない。ここで見るのは
     * 「202 を返して黙らせてよいか」だけで、これを見てフォロワーを消すことはしない。
     * 消すのは [DeleteActorHandler] で、そちらには検証を通ったものしか届かない。
     */
    private fun isSelfDelete(body: ByteArray): Boolean {
        val activity = runCatching {
            AppJson.decodeFromString(InboxActivity.serializer(), body.decodeToString())
        }.getOrNull() ?: return false

        if (activity.type != DELETE_TYPE) return false

        val actorId = activity.actorId ?: return false
        return activity.target?.id == actorId
    }

    companion object {
        private const val DELETE_TYPE = "Delete"

        /**
         * 既定の組み合わせで作る。
         *
         * 署名の検証と Follow への `Accept` は ActivityPub として要るもので、
         * 使う側が選ぶものではない。組み立てを呼び出し側に書かせると
         * 「どのハンドラが要るか」がモジュールの外に漏れるので、ここに置く。
         *
         * ここから相手に送ることはしない。返すものは記録と一緒に預けて、
         * 送るのは受け取り側の都合と切り離す。
         *
         * @param remoteActors 相手のアクターの引き先。署名検証に使う公開鍵と、
         *   `Accept` の宛先になる inbox、お気に入りやスタンプを押した相手として残す鍵をここから取る
         * @param followers フォローの記録。配信先だけでなく、相手が消えて
         *   アクター文書を引けなくなったときの公開鍵の引き先にもなる
         * @param favourites お気に入りの記録。[followers] と同じく、
         *   消えた相手の公開鍵の引き先にもなる
         * @param stamps スタンプの記録。[favourites] と同じく、消えた相手の公開鍵の引き先にもなる
         * @param domainBlocks 受信を止めるドメインと、署名付きのリクエストが届いたことの記録先
         */
        fun default(
            directory: ActorDirectory,
            remoteActors: RemoteActors,
            followers: FollowerStore,
            favourites: FavouriteStore,
            stamps: StampStore,
            earlyUndoneLikes: EarlyUndoneLikes,
            domainBlocks: InboxDomainBlocks,
            domain: String,
        ): InboxService =
            InboxService(
                verifier = HttpSignatureVerifier(
                    RecordedFallbackPublicKeys(
                        remote = remoteActors,
                        followers = followers,
                        favourites = favourites,
                        stamps = stamps,
                    ),
                ),
                domainBlocks = domainBlocks,
                handlers = listOf(
                    FollowHandler(
                        directory = directory,
                        remoteActors = remoteActors,
                        followers = followers,
                    ),
                    ReactionHandler(
                        type = ReactionHandler.LIKE_TYPE,
                        domain = domain,
                        remoteActors = remoteActors,
                        favourites = favourites,
                        stamps = stamps,
                        earlyUndoneLikes = earlyUndoneLikes,
                    ),
                    ReactionHandler(
                        type = ReactionHandler.EMOJI_REACT_TYPE,
                        domain = domain,
                        remoteActors = remoteActors,
                        favourites = favourites,
                        stamps = stamps,
                        earlyUndoneLikes = earlyUndoneLikes,
                    ),
                    UndoHandler(
                        reactions = UndoReactionHandler(
                            domain = domain,
                            favourites = favourites,
                            stamps = stamps,
                            earlyUndoneLikes = earlyUndoneLikes,
                        ),
                        follows = UndoFollowHandler(directory = directory, followers = followers),
                    ),
                    UpdateActorHandler(followers),
                    DeleteActorHandler(followers = followers, favourites = favourites, stamps = stamps),
                ),
            )
    }
}

/**
 * [InboxService.receive] の結果。
 *
 * 中身の処理に失敗しても [Accepted] を返す。相手のサーバーは 5xx を見ると
 * 再送を繰り返すので、こちらの都合で溜め込ませない。
 */
sealed interface InboxResult {
    /** 署名が無い、通らない、`actor` と署名者が違う */
    data object Unauthorized : InboxResult

    /** 署名は通ったが、ボディが JSON として読めない */
    data object BadRequest : InboxResult

    /** 署名が通った。中身の処理の成否は含めない。受信を止めているドメインから届いたものも、捨ててこれを返す */
    data object Accepted : InboxResult
}
