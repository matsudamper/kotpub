package net.matsudamper.kotpub.note

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.matsudamper.kotpub.http.ActivityPubHttpClient
import net.matsudamper.kotpub.http.HttpUrl

class HttpRemoteOutbox(
    private val client: ActivityPubHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun recent(actorId: String, limit: Int): List<RemoteOutboxNote> {
        require(limit in 1..MAX_NOTES)
        val actorUrl = HttpUrl.parseHttps(actorId) ?: return listOf()
        val actor = fetch(actorId, actorUrl) ?: error("アクターを取得できませんでした")
        check(actor.text("id") == actorId) { "アクターの ID が一致しません" }
        val outbox = actor["outbox"] ?: return listOf()
        val collection = document(outbox, actorUrl) ?: error("outbox を取得できませんでした")
        return pages(collection, actorId, actorUrl, limit, MAX_PAGES, setOf())
            .distinctBy { it.id }
            .sortedByDescending { Instant.parse(it.publishedAt) }
            .take(limit)
    }

    private suspend fun pages(
        collection: JsonObject,
        actorId: String,
        actorUrl: HttpUrl,
        limit: Int,
        remainingPages: Int,
        visited: Set<String>,
    ): List<RemoteOutboxNote> {
        if (remainingPages == 0) return listOf()
        val pageId = collection.text("id")
        if (pageId != null && pageId in visited) return listOf()
        val items = (collection["orderedItems"] ?: collection["items"]) as? JsonArray
        val notes = items.orEmpty().take(MAX_NOTES).mapNotNull { item ->
            val activity = document(item, actorUrl) ?: return@mapNotNull null
            val note = when (activity.text("type")) {
                "Create" -> {
                    if (activity.text("actor") != actorId) return@mapNotNull null
                    document(activity["object"], actorUrl)
                }

                "Note" -> activity

                else -> null
            } ?: return@mapNotNull null
            readNote(note, actorId)
        }
        if (notes.size >= limit) return notes.take(limit)
        val next = if (items == null) collection["first"] else collection["next"]
        val nextPage = document(next, actorUrl) ?: return notes
        return notes + pages(nextPage, actorId, actorUrl, limit - notes.size, remainingPages - 1, visited + listOfNotNull(pageId))
    }

    private fun readNote(note: JsonObject, actorId: String): RemoteOutboxNote? {
        if (note.text("type") != "Note" || note.text("attributedTo") != actorId) return null
        val recipients = listOf(note["to"], note["cc"]).flatMap { value -> if (value is JsonArray) value else listOfNotNull(value) }
        if (recipients.none { (it as? JsonPrimitive)?.content == PUBLIC }) return null
        val id = note.text("id") ?: return null
        val content = note.text("content") ?: return null
        val publishedAt = note.text("published") ?: return null
        if (runCatching { Instant.parse(publishedAt) }.isFailure) return null
        return RemoteOutboxNote(id, content, publishedAt)
    }

    private suspend fun document(value: JsonElement?, actorUrl: HttpUrl): JsonObject? = when (value) {
        is JsonObject -> value
        is JsonPrimitive -> fetch(value.content, actorUrl)
        else -> null
    }

    private suspend fun fetch(url: String, actorUrl: HttpUrl): JsonObject? {
        val target = HttpUrl.parseHttps(url) ?: return null
        if (!target.isSameHost(actorUrl)) return null
        return try {
            val response = client.get(
                url = url,
                headers = mapOf("Accept" to "application/activity+json"),
                maxBodyBytes = MAX_BODY_BYTES,
                readsBody = { status, finalUrl -> status == 200 && HttpUrl.parseHttps(finalUrl)?.isSameHost(actorUrl) == true },
            )
            if (response.status != 200 || HttpUrl.parseHttps(response.finalUrl)?.isSameHost(actorUrl) != true) return null
            val body = response.body?.getOrNull() ?: return null
            if (body.size > MAX_BODY_BYTES) return null
            json.parseToJsonElement(body.decodeToString()) as? JsonObject
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.content

    private companion object {
        const val MAX_NOTES = 20
        const val MAX_PAGES = 3
        const val MAX_BODY_BYTES = 1024 * 1024
        const val PUBLIC = "https://www.w3.org/ns/activitystreams#Public"
    }
}

data class RemoteOutboxNote(
    val id: String,
    val contentHtml: String,
    val publishedAt: String,
)
