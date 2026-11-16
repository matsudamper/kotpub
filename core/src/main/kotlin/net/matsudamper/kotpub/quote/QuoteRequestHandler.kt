package net.matsudamper.kotpub.quote

import java.util.UUID
import kotlinx.serialization.json.JsonObject
import net.matsudamper.kotpub.activity.InboxActivity
import net.matsudamper.kotpub.activity.OutgoingActivity
import net.matsudamper.kotpub.activitypub.LinkOrObject
import net.matsudamper.kotpub.activitypub.id
import net.matsudamper.kotpub.actor.ActorUrls
import net.matsudamper.kotpub.actor.RemoteActorLookup
import net.matsudamper.kotpub.actor.RemoteActors
import net.matsudamper.kotpub.http.HttpUrl
import net.matsudamper.kotpub.http.RemoteFetchFailure
import net.matsudamper.kotpub.inbox.InboxActivityHandler
import net.matsudamper.kotpub.inbox.InboxHandling
import net.matsudamper.kotpub.inbox.InboxRecipient
import net.matsudamper.kotpub.inbox.InboxSpan
import net.matsudamper.kotpub.json.AppJson
import net.matsudamper.kotpub.note.NoteStore
import net.matsudamper.kotpub.note.NoteUrls

/**
 * `QuoteRequest` を受けて、引用を承認する `Accept` を返す。FEP-044f の流れ。
 *
 * Mastodon は他のサーバーの投稿を引用すると、引用された側に `QuoteRequest` を送り、
 * `Accept` が返るまで引用を保留にする。`Accept` の `result` に入れた [QuoteAuthorization] を
 * 取得して確かめられたら、相手の画面で引用が成立する。
 *
 * こちらの投稿は誰にでも引用を許しているので、こちらの投稿への要求なら全て承認する
 */
class QuoteRequestHandler(
    private val domain: String,
    private val notes: NoteStore,
    private val remoteActors: RemoteActors,
    private val approvals: QuoteApprovals,
) : InboxActivityHandler {
    override val type: String = TYPE

    override suspend fun handle(
        recipient: InboxRecipient,
        verifiedSignerActorId: String,
        activity: InboxActivity,
        rawActivityJson: JsonObject,
    ): InboxHandling {
        val targetUrl = activity.target?.id
        val notePublicId = targetUrl?.let { NoteUrls.publicIdOf(domain = domain, url = it) }
        if (notePublicId == null) {
            InboxSpan.outcome("quote_request.not_local_note")
            return InboxHandling.Ignored("QuoteRequest の対象がこちらの投稿ではない: object=$targetUrl")
        }
        val note = notes.find(notePublicId)
        if (note == null) {
            InboxSpan.outcome("quote_request.note_not_found")
            return InboxHandling.Ignored("QuoteRequest の対象の投稿が無い: object=$targetUrl")
        }

        val quotingNoteId = activity.instrument?.id
        if (quotingNoteId == null) {
            InboxSpan.outcome("quote_request.no_instrument")
            return InboxHandling.Ignored("QuoteRequest に引用した投稿が無い: ${recipient.logLabel} ← $verifiedSignerActorId")
        }
        // 他人の投稿を引用元として承認させると、その投稿がこちらの投稿を引用したことにされる
        if (!isPostedBySigner(instrument = activity.instrument, quotingNoteId = quotingNoteId, signerActorId = verifiedSignerActorId)) {
            InboxSpan.outcome("quote_request.foreign_instrument")
            return InboxHandling.Ignored("署名した相手の投稿ではないので承認しない: instrument=$quotingNoteId ← $verifiedSignerActorId")
        }

        val quoter = when (val lookup = remoteActors.findActor(verifiedSignerActorId)) {
            is RemoteActorLookup.Found -> lookup.actor

            RemoteActorLookup.Gone, is RemoteActorLookup.Unavailable -> {
                // 承認が返らないと相手の引用は保留のまま残るので、一時的な失敗なら送り直してもらう
                if (lookup is RemoteActorLookup.Unavailable && lookup.failure.isTemporary()) {
                    throw IllegalStateException("引用した相手のアクター文書を一時的に取得できない: $verifiedSignerActorId 理由=$lookup")
                }
                InboxSpan.outcome("quote_request.actor_unavailable")
                return InboxHandling.Ignored("引用した相手のアクター文書を取得できないので承認を返せない: $verifiedSignerActorId 理由=$lookup")
            }
        }

        val author = ActorUrls(domain = domain, username = note.username)
        val accept = OutgoingActivity(
            id = "${author.actorId}#accepts/quote_requests/${UUID.randomUUID()}",
            type = OutgoingActivity.TYPE_ACCEPT,
            actor = author.actorId,
            target = LinkOrObject.Embedded(rawActivityJson),
            result = NoteUrls(domain = domain, publicId = note.publicId).quoteAuthorizationUrl(quotingNoteId),
        )
        val enqueued = approvals.enqueueAccept(
            username = note.username,
            quoter = quoter,
            acceptBody = AppJson.encodeToString(OutgoingActivity.serializer(), accept),
        )

        InboxSpan.outcome(if (enqueued) "quote_request.accepted" else "quote_request.refused")
        if (!enqueued) {
            return InboxHandling.Ignored("受け付けない相手からの QuoteRequest: ${note.username} ← $verifiedSignerActorId")
        }
        return InboxHandling.Handled
    }

    private fun isPostedBySigner(
        instrument: LinkOrObject,
        quotingNoteId: String,
        signerActorId: String,
    ): Boolean {
        val noteUrl = HttpUrl.parse(quotingNoteId) ?: return false
        val signerUrl = HttpUrl.parse(signerActorId) ?: return false
        if (!noteUrl.isHttps || !noteUrl.isSameHost(signerUrl)) return false

        val attributedTo = (instrument as? LinkOrObject.Embedded)?.json?.get("attributedTo") ?: return true
        val attributedActorId = runCatching { AppJson.decodeFromJsonElement(LinkOrObject.serializer(), attributedTo).id }.getOrNull()
        return attributedActorId == signerActorId
    }

    private fun RemoteFetchFailure.isTemporary(): Boolean =
        this is RemoteFetchFailure.Throttled || this is RemoteFetchFailure.RequestFailed

    companion object {
        const val TYPE: String = "QuoteRequest"
    }
}
