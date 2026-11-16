package net.matsudamper.kotpub.quote

import net.matsudamper.kotpub.activitypub.ActivityPubContentTypes
import net.matsudamper.kotpub.actor.ActorUrls
import net.matsudamper.kotpub.entity.PublicNoteId
import net.matsudamper.kotpub.http.EndpointResponse
import net.matsudamper.kotpub.http.HttpStatusCodes
import net.matsudamper.kotpub.note.NoteStore
import net.matsudamper.kotpub.note.NoteUrls

/**
 * 引用の承認を返す。[QuoteRequestHandler] が `Accept` の `result` に入れた id の取得先。
 *
 * 投稿が消えていれば 404 を返す。相手はそれを見て引用の承認が取り消されたと扱う
 */
class QuoteAuthorizationEndpoint(
    private val domain: String,
    private val notes: NoteStore,
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
        val interactingObject = token?.let { NoteUrls.interactingObjectOf(it) }
            ?: return notFound("引用した投稿を読めない: $token")

        val noteUrls = NoteUrls(domain = domain, publicId = note.publicId)
        return EndpointResponse.json(
            serializer = QuoteAuthorization.serializer(),
            value = QuoteAuthorization(
                id = noteUrls.quoteAuthorizationUrl(interactingObject),
                attributedTo = ActorUrls(domain = domain, username = note.username).actorId,
                interactingObject = interactingObject,
                interactionTarget = noteUrls.noteUrl,
            ),
            contentType = ActivityPubContentTypes.negotiate(accept),
        )
    }

    private fun notFound(text: String): EndpointResponse =
        EndpointResponse.text(status = HttpStatusCodes.NOT_FOUND, text = text, headers = mapOf())
}
