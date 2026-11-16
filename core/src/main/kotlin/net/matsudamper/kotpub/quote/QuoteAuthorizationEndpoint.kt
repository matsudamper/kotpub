package net.matsudamper.kotpub.quote

import net.matsudamper.kotpub.activitypub.ActivityPubContentTypes
import net.matsudamper.kotpub.entity.PublicNoteId
import net.matsudamper.kotpub.http.EndpointResponse
import net.matsudamper.kotpub.http.HttpStatusCodes
import net.matsudamper.kotpub.note.NoteStore
import net.matsudamper.kotpub.note.NoteUrls

/**
 * 引用の承認を返す。[QuoteRequestHandler] が `Accept` の `result` に入れた id の取得先。
 *
 * 投稿が消えたとき、引用した相手をブロックしたときは 404 を返す。相手はそれを見て引用の承認が取り消されたと扱う
 */
class QuoteAuthorizationEndpoint(
    private val domain: String,
    private val notes: NoteStore,
    private val approvals: QuoteApprovals,
) {
    /**
     * `/notes/{publicId}/quote_authorizations/{token}`
     */
    suspend fun get(
        publicId: String?,
        token: String?,
        accept: String?,
    ): EndpointResponse {
        val note = publicId?.let { notes.find(PublicNoteId(it)) }
            ?: return notFound("投稿が見つからない: $publicId")
        val author = approvals.findAuthor(note.username)
            ?: return notFound("投稿の持ち主のアカウントが無い: ${note.username}")

        if (token == null) return notFound("承認の値が無い")

        val noteUrls = NoteUrls(domain = domain, publicId = note.publicId)
        val authorization = QuoteAuthorizationToken.decode(author = author, interactionTarget = noteUrls.noteUrl, token = token)
            ?: return notFound("承認の署名が合わない: $token")
        if (approvals.refuses(username = note.username, quoterActorId = authorization.quoterActorId)) {
            return notFound("引用を認めない相手への承認: ${authorization.quoterActorId}")
        }

        return EndpointResponse.json(
            serializer = QuoteAuthorization.serializer(),
            value = QuoteAuthorization(
                id = noteUrls.quoteAuthorizationUrl(token),
                attributedTo = author.urls.actorId,
                interactingObject = authorization.interactingObject,
                interactionTarget = noteUrls.noteUrl,
            ),
            contentType = ActivityPubContentTypes.negotiate(accept),
        )
    }

    private fun notFound(text: String): EndpointResponse =
        EndpointResponse.text(status = HttpStatusCodes.NOT_FOUND, text = text, headers = mapOf())
}
