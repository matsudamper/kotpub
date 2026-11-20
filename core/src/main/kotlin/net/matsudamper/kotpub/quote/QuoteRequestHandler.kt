package net.matsudamper.kotpub.quote

import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.matsudamper.kotpub.activity.InboxActivity
import net.matsudamper.kotpub.activity.OutgoingActivity
import net.matsudamper.kotpub.activitypub.LinkOrObject
import net.matsudamper.kotpub.activitypub.id
import net.matsudamper.kotpub.actor.RemoteActorLookup
import net.matsudamper.kotpub.actor.RemoteActors
import net.matsudamper.kotpub.http.HttpStatusCodes
import net.matsudamper.kotpub.http.RemoteFetchFailure
import net.matsudamper.kotpub.inbox.InboxActivityHandler
import net.matsudamper.kotpub.inbox.InboxHandling
import net.matsudamper.kotpub.inbox.InboxRecipient
import net.matsudamper.kotpub.inbox.InboxSpan
import net.matsudamper.kotpub.json.AppJson
import net.matsudamper.kotpub.note.NoteStore
import net.matsudamper.kotpub.note.NoteUrls
import net.matsudamper.kotpub.note.RemoteNoteLookup
import net.matsudamper.kotpub.note.RemoteNotes

/**
 * `QuoteRequest` を受けて、引用を承認する `Accept` を返す。FEP-044f の流れ。
 *
 * Mastodon は他のサーバーの投稿を引用すると、引用された側に `QuoteRequest` を送り、
 * `Accept` が返るまで引用を保留にする。`Accept` の `result` に入れた [QuoteAuthorization] を
 * 取得して確かめられたら、相手の画面で引用が成立する。
 *
 * こちらの投稿は誰にでも引用を許しているので、ブロックした相手でなければ全て承認する
 */
class QuoteRequestHandler(
    private val domain: String,
    private val notes: NoteStore,
    private val remoteActors: RemoteActors,
    private val remoteNotes: RemoteNotes,
    private val approvals: QuoteApprovals,
) : InboxActivityHandler {
    override val type: String = TYPE

    override suspend fun handle(
        recipient: InboxRecipient,
        verifiedSignerActorId: String,
        activity: InboxActivity,
        rawActivityJson: JsonObject,
    ): InboxHandling {
        val quoteRequestId = activity.id
        if (quoteRequestId == null) {
            InboxSpan.outcome("quote_request.no_id")
            return InboxHandling.Ignored("QuoteRequest に id が無いので、承認しても相手が照合できない: ${recipient.logLabel} ← $verifiedSignerActorId")
        }
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
        // 他人の投稿を引用元として承認させると、その投稿がこちらの投稿を引用したことにされる。
        // 要求に埋め込まれた attributedTo は要求した側が書けるので、引用元の投稿を取得して投稿者を確かめる
        when (val lookup = remoteNotes.find(quotingNoteId)) {
            is RemoteNoteLookup.Found -> {
                if (lookup.note.attributedTo != verifiedSignerActorId) {
                    InboxSpan.outcome("quote_request.foreign_instrument")
                    return InboxHandling.Ignored("署名した相手の投稿ではないので承認しない: instrument=$quotingNoteId ← $verifiedSignerActorId")
                }
            }

            is RemoteNoteLookup.NotFound -> {
                InboxSpan.outcome("quote_request.instrument_not_found")
                return InboxHandling.Ignored("引用した投稿を取得できないので承認しない: instrument=$quotingNoteId 理由=${lookup.failure}")
            }

            is RemoteNoteLookup.Unavailable -> {
                throw IllegalStateException("引用した投稿を一時的に取得できない: instrument=$quotingNoteId 理由=${lookup.failure}")
            }
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

        if (approvals.refuses(username = note.username, quoterActorId = verifiedSignerActorId)) {
            InboxSpan.outcome("quote_request.refused")
            return InboxHandling.Ignored("引用を認めない相手からの QuoteRequest: ${note.username} ← $verifiedSignerActorId")
        }
        val author = approvals.findAuthor(note.username)
        if (author == null) {
            InboxSpan.outcome("quote_request.author_not_found")
            return InboxHandling.Ignored("引用された投稿の持ち主のアカウントが無い: ${note.username}")
        }

        val noteUrls = NoteUrls(domain = domain, publicId = note.publicId)
        val token = QuoteAuthorizationToken(quoterActorId = quoter.actorId, interactingObject = quotingNoteId)
            .encode(author = author, interactionTarget = noteUrls.noteUrl)
        val accept = OutgoingActivity(
            id = "${author.urls.actorId}#accepts/quote_requests/${UUID.randomUUID()}",
            type = OutgoingActivity.TYPE_ACCEPT,
            actor = author.urls.actorId,
            target = LinkOrObject.Embedded(
                quoteRequestForAccept(
                    quoteRequestId = quoteRequestId,
                    quoterActorId = verifiedSignerActorId,
                    quotedNoteUrl = noteUrls.noteUrl,
                    quotingNoteId = quotingNoteId,
                ),
            ),
            result = noteUrls.quoteAuthorizationUrl(token),
        )
        approvals.enqueueAccept(
            username = note.username,
            quoter = quoter,
            acceptBody = AppJson.encodeToString(OutgoingActivity.serializer(), accept),
        )

        InboxSpan.outcome("quote_request.accepted")
        return InboxHandling.Handled
    }

    /**
     * `Accept` に埋める `QuoteRequest`。
     *
     * 受け取った JSON をそのまま埋めると、相手が書いた中身が配信キューの行と送る本文に載り、
     * 相手は自分宛ての `Accept` を好きなだけ大きくできる。
     * Mastodon は `Accept` の `object` の id で保留中の引用を探すので、照合に要る項目だけを入れる
     */
    private fun quoteRequestForAccept(
        quoteRequestId: String,
        quoterActorId: String,
        quotedNoteUrl: String,
        quotingNoteId: String,
    ): JsonObject = JsonObject(
        mapOf(
            "id" to JsonPrimitive(quoteRequestId),
            "type" to JsonPrimitive(TYPE),
            "actor" to JsonPrimitive(quoterActorId),
            "object" to JsonPrimitive(quotedNoteUrl),
            "instrument" to JsonPrimitive(quotingNoteId),
        ),
    )

    private fun RemoteFetchFailure.isTemporary(): Boolean =
        when (this) {
            is RemoteFetchFailure.Throttled, is RemoteFetchFailure.RequestFailed -> true
            is RemoteFetchFailure.UnsuccessfulStatus -> status == HttpStatusCodes.TOO_MANY_REQUESTS || status >= HttpStatusCodes.INTERNAL_SERVER_ERROR
            else -> false
        }

    companion object {
        const val TYPE: String = "QuoteRequest"
    }
}
