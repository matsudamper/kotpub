package net.matsudamper.kotpub.actor

import net.matsudamper.kotpub.activitypub.ActivityPubContentTypes
import net.matsudamper.kotpub.activitypub.Actor
import net.matsudamper.kotpub.activitypub.ActorPublicKey
import net.matsudamper.kotpub.activitypub.InstanceActorDocument
import net.matsudamper.kotpub.http.EndpointResponse

/**
 * サーバー自身のアクターのエンドポイント。署名した GET を受けた相手が公開鍵を取りに来る
 */
class InstanceActorEndpoint(
    private val actor: InstanceActor,
) {
    /**
     * `/actor`
     *
     * @param accept `Accept` ヘッダ。見ずに application/json で返すとアクターとして認識されない
     */
    fun get(accept: String?): EndpointResponse =
        EndpointResponse.json(
            serializer = InstanceActorDocument.serializer(),
            value = instanceActorDocument(actor),
            contentType = ActivityPubContentTypes.negotiate(accept),
        )
}

internal fun instanceActorDocument(actor: InstanceActor): InstanceActorDocument {
    val urls = actor.urls
    return InstanceActorDocument(
        id = urls.actorId,
        preferredUsername = urls.preferredUsername,
        name = urls.domain,
        inbox = urls.sharedInbox,
        endpoints = Actor.Endpoints(sharedInbox = urls.sharedInbox),
        publicKey = ActorPublicKey(
            id = urls.publicKeyId,
            owner = urls.actorId,
            publicKeyPem = actor.privateKey.publicKeyPem,
        ),
    )
}
