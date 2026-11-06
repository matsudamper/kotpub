package net.matsudamper.kotpub.inbox

import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.matsudamper.kotpub.activity.InboxActivity
import net.matsudamper.kotpub.activitypub.id
import net.matsudamper.kotpub.actor.RemoteActors
import net.matsudamper.kotpub.boost.BoostStore
import net.matsudamper.kotpub.boost.BoostStore.ReceivedBoost
import net.matsudamper.kotpub.boost.BoostStore.ReceivedRemoteBoost
import net.matsudamper.kotpub.entity.PublicNoteId
import net.matsudamper.kotpub.http.HttpUrl
import net.matsudamper.kotpub.note.NoteUrls
import net.matsudamper.kotpub.note.NoteVisibility
import net.matsudamper.kotpub.note.ReceivedNoteJson
import net.matsudamper.kotpub.note.ReceivedNoteJson.strings
import net.matsudamper.kotpub.note.RemoteNotes
import org.slf4j.LoggerFactory

/**
 * ブースト（`Announce`）を受け取る。
 *
 * こちらの投稿のブーストは押した相手を記録し、他のサーバーの投稿のブーストは
 * 投稿を取得してから [BoostStore.saveRemote] に渡す。
 *
 * `Announce` の id はブーストした相手と同じホストのものに限る。`Undo` はこの id で取り消すので、
 * 他所の id を名乗らせると、他人のブーストの取り消しと見分けが付かなくなる
 */
class AnnounceHandler(
    private val domain: String,
    private val remoteActors: RemoteActors,
    private val remoteNotes: RemoteNotes,
    private val boosts: BoostStore,
) : InboxActivityHandler {
    override val type: String = TYPE

    private val logger = LoggerFactory.getLogger(AnnounceHandler::class.java)

    override suspend fun handle(
        recipient: InboxRecipient,
        verifiedSignerActorId: String,
        activity: InboxActivity,
        rawActivityJson: JsonObject,
    ) {
        if (activity.actorId != verifiedSignerActorId) {
            logger.info("Announce の actor が署名者と違うので何もしない: ${recipient.logLabel} ← $verifiedSignerActorId")
            InboxSpan.outcome("announce.actor_mismatch")
            return
        }

        val activityUri = activity.id
        val actorUrl = HttpUrl.parseHttps(verifiedSignerActorId)
        val activityUrl = activityUri?.let { HttpUrl.parseHttps(it) }
        if (activityUri == null || actorUrl == null || activityUrl == null || !activityUrl.isSameHost(actorUrl)) {
            logger.info("Announce の id が無いかブーストした相手と別のホストなので何もしない: ${recipient.logLabel} ← $verifiedSignerActorId id=$activityUri")
            InboxSpan.outcome("announce.invalid_id")
            return
        }

        val targetUrl = activity.target?.id
        if (targetUrl == null) {
            logger.warn("Announce に object が無い: ${recipient.logLabel} ← $verifiedSignerActorId")
            InboxSpan.outcome("announce.no_object")
            return
        }

        val notePublicId = NoteUrls.publicIdOf(domain = domain, url = targetUrl)
        if (notePublicId != null) {
            addLocalBoost(recipient, verifiedSignerActorId, activityUri, notePublicId)
            return
        }

        if (HttpUrl.parse(targetUrl)?.host.equals(domain, ignoreCase = true)) {
            logger.info("Announce の対象がこちらの投稿の URL ではないので何もしない: object=$targetUrl")
            InboxSpan.outcome("announce.not_local_note")
            return
        }

        saveRemoteBoost(recipient, verifiedSignerActorId, activityUri, targetUrl, rawActivityJson)
    }

    private suspend fun addLocalBoost(
        recipient: InboxRecipient,
        actorUri: String,
        activityUri: String,
        notePublicId: PublicNoteId,
    ) {
        // 相手の公開鍵を記録できないと、相手が消えた後の `Delete` を検証できず、
        // 居ない相手のブーストが残り続ける
        val actor = remoteActors.findActor(actorUri)
        if (actor == null) {
            logger.warn("Announce の相手のアクター文書を取得できないので受け付けない: ${recipient.logLabel} ← $actorUri")
            InboxSpan.outcome("announce.actor_unavailable")
            return
        }

        val recorded = boosts.add(
            ReceivedBoost(
                notePublicId = notePublicId,
                actor = actor,
                activityUri = activityUri,
                receivedAt = Instant.now(),
            ),
        )
        InboxSpan.outcome(if (recorded) "announce.local_recorded" else "announce.local_not_recorded")
        logger.info("こちらの投稿のブーストを${if (recorded) "記録した" else "記録しなかった"}: ${recipient.logLabel} ← $actorUri 投稿=${notePublicId.value}")
    }

    private suspend fun saveRemoteBoost(
        recipient: InboxRecipient,
        actorUri: String,
        activityUri: String,
        targetUrl: String,
        rawActivityJson: JsonObject,
    ) {
        val note = remoteNotes.find(targetUrl)
        if (note == null) {
            logger.info("ブーストされた投稿を取得できないので何もしない: ${recipient.logLabel} ← $actorUri object=$targetUrl")
            InboxSpan.outcome("announce.note_unavailable")
            return
        }

        // 取得できる時点で公開されているはずだが、宛先を限った投稿を名乗る文書は広めない
        if (note.visibility != NoteVisibility.Public && note.visibility != NoteVisibility.Unlisted) {
            logger.info("公開されていない投稿のブーストなので何もしない: ${recipient.logLabel} ← $actorUri object=$targetUrl")
            InboxSpan.outcome("announce.note_not_public")
            return
        }

        val to = rawActivityJson["to"].strings()
        val cc = rawActivityJson["cc"].strings()
        boosts.saveRemote(
            ReceivedRemoteBoost(
                activityUri = activityUri,
                actorUri = actorUri,
                visibility = ReceivedNoteJson.visibilityOf(actorId = actorUri, to = to, cc = cc),
                to = to,
                cc = cc,
                published = (rawActivityJson["published"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.let { runCatching { Instant.parse(it.content) }.getOrNull() },
                note = note,
            ),
        )
        InboxSpan.outcome("announce.remote_passed")
    }

    companion object {
        const val TYPE: String = "Announce"
    }
}
