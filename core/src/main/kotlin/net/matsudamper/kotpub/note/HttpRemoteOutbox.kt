package net.matsudamper.kotpub.note

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.matsudamper.kotpub.emoji.CustomEmoji
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
        val firstPage = if (collection.items() == null) document(collection["first"], actorUrl) else collection
        if (firstPage == null) return listOf()
        return pages(firstPage, actorId, actorUrl, limit, MAX_PAGES, setOf(), listOf())
            .sortedByDescending { Instant.parse(it.publishedAt) }
            .take(limit)
    }

    private suspend fun pages(
        page: JsonObject,
        actorId: String,
        actorUrl: HttpUrl,
        limit: Int,
        remainingPages: Int,
        visitedPageIds: Set<String>,
        collectedNotes: List<RemoteOutboxNote>,
    ): List<RemoteOutboxNote> {
        val pageId = page.text("id")
        if (pageId != null && pageId in visitedPageIds) return collectedNotes
        val pageItems = page.items().orEmpty().take(MAX_ITEMS_PER_PAGE)
        val notes = collectUniqueNotes(pageItems, 0, actorId, actorUrl, limit, collectedNotes)
        if (notes.size >= limit) return notes
        if (remainingPages <= 1) return notes
        val nextPage = document(page["next"], actorUrl) ?: return notes
        return pages(nextPage, actorId, actorUrl, limit, remainingPages - 1, visitedPageIds + listOfNotNull(pageId), notes)
    }

    private tailrec suspend fun collectUniqueNotes(
        items: List<JsonElement>,
        index: Int,
        actorId: String,
        actorUrl: HttpUrl,
        limit: Int,
        collectedNotes: List<RemoteOutboxNote>,
    ): List<RemoteOutboxNote> {
        if (collectedNotes.size >= limit) return collectedNotes
        val item = items.getOrNull(index) ?: return collectedNotes
        val note = readItem(item, actorId, actorUrl)
        val notes = if (note == null || collectedNotes.any { it.id == note.id }) collectedNotes else collectedNotes + note
        return collectUniqueNotes(items, index + 1, actorId, actorUrl, limit, notes)
    }

    private suspend fun readItem(item: JsonElement, actorId: String, actorUrl: HttpUrl): RemoteOutboxNote? {
        val activity = document(item, actorUrl) ?: return null
        val note = when (activity.text("type")) {
            "Create" -> {
                if (activity.text("actor") != actorId) return null
                document(activity["object"], actorUrl)
            }

            "Note" -> activity

            else -> null
        } ?: return null
        return readNote(note, actorId)
    }

    private fun readNote(note: JsonObject, actorId: String): RemoteOutboxNote? {
        if (note.text("type") != "Note" || note.text("attributedTo") != actorId) return null
        val recipients = listOf(note["to"], note["cc"]).flatMap { value -> if (value is JsonArray) value else listOfNotNull(value) }
        if (recipients.none { (it as? JsonPrimitive)?.content == PUBLIC }) return null
        val id = note.text("id") ?: return null
        val content = note.text("content") ?: return null
        val publishedAt = note.text("published") ?: return null
        if (runCatching { Instant.parse(publishedAt) }.isFailure) return null
        val received = ReceivedNoteJson.read(note) ?: return null
        return RemoteOutboxNote(
            id = id,
            contentHtml = content,
            publishedAt = publishedAt,
            summary = received.summary,
            sensitive = received.sensitive,
            attachments = received.attachments,
            emojis = received.emojis,
        )
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

    private fun JsonObject.items(): JsonArray? = (this["orderedItems"] ?: this["items"]) as? JsonArray

    private companion object {
        const val MAX_NOTES = 20
        const val MAX_PAGES = 3
        const val MAX_ITEMS_PER_PAGE = 100
        const val MAX_BODY_BYTES = 1024 * 1024
        const val PUBLIC = "https://www.w3.org/ns/activitystreams#Public"
    }
}

/**
 * @param summary 閲覧注意の注意書き。無ければ null
 * @param attachments 添付。付いた順
 * @param emojis 本文と注意書きに `:name:` で入るカスタム絵文字
 */
data class RemoteOutboxNote(
    val id: String,
    val contentHtml: String,
    val publishedAt: String,
    val summary: String?,
    val sensitive: Boolean,
    val attachments: List<ReceivedNoteAttachment>,
    val emojis: List<CustomEmoji>,
)
