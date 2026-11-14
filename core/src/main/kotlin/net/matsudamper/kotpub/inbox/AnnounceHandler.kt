package net.matsudamper.kotpub.inbox

import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.matsudamper.kotpub.activity.InboxActivity
import net.matsudamper.kotpub.activitypub.id
import net.matsudamper.kotpub.actor.RemoteActorLookup
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
import net.matsudamper.kotpub.note.RemoteNoteLookup
import net.matsudamper.kotpub.note.RemoteNotes

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
    private val earlyUndone: EarlyUndoneLikes,
) : InboxActivityHandler {
    override val type: String = TYPE

    override suspend fun handle(
        recipient: InboxRecipient,
        verifiedSignerActorId: String,
        activity: InboxActivity,
        rawActivityJson: JsonObject,
    ): InboxHandling {
        if (activity.actorId != verifiedSignerActorId) {
            InboxSpan.outcome("announce.actor_mismatch")
            return InboxHandling.Ignored("Announce の actor が署名者と違うので何もしない: ${recipient.logLabel} ← $verifiedSignerActorId")
        }

        val activityUri = activity.id
        val actorUrl = HttpUrl.parseHttps(verifiedSignerActorId)
        val activityUrl = activityUri?.let { HttpUrl.parseHttps(it) }
        if (activityUri == null || actorUrl == null || activityUrl == null || !activityUrl.isSameHost(actorUrl)) {
            InboxSpan.outcome("announce.invalid_id")
            return InboxHandling.Ignored("Announce の id が無いかブーストした相手と別のホストなので何もしない: ${recipient.logLabel} ← $verifiedSignerActorId id=$activityUri")
        }

        if (earlyUndone.isRemembered(actorUri = verifiedSignerActorId, activityUri = activityUri, now = Instant.now())) {
            InboxSpan.outcome("announce.undone_first")
            return InboxHandling.Ignored("先に取り消しが届いた Announce なので記録しない: ${recipient.logLabel} ← $verifiedSignerActorId id=$activityUri")
        }

        val targetUrl = activity.target?.id
        if (targetUrl == null) {
            InboxSpan.outcome("announce.no_object")
            return InboxHandling.Ignored("Announce に object が無い: ${recipient.logLabel} ← $verifiedSignerActorId")
        }

        val notePublicId = NoteUrls.publicIdOf(domain = domain, url = targetUrl)
        if (notePublicId != null) {
            return addLocalBoost(recipient, verifiedSignerActorId, activityUri, notePublicId)
        }

        if (HttpUrl.parse(targetUrl)?.host.equals(domain, ignoreCase = true)) {
            InboxSpan.outcome("announce.not_local_note")
            return InboxHandling.Ignored("Announce の対象がこちらの投稿の URL ではないので何もしない: object=$targetUrl")
        }

        return saveRemoteBoost(recipient, verifiedSignerActorId, activityUri, targetUrl, rawActivityJson)
    }

    private suspend fun addLocalBoost(
        recipient: InboxRecipient,
        actorUri: String,
        activityUri: String,
        notePublicId: PublicNoteId,
    ): InboxHandling {
        // 相手の公開鍵を記録できないと、相手が消えた後の `Delete` を検証できず、
        // 居ない相手のブーストが残り続ける
        val actor = when (val lookup = remoteActors.findActor(actorUri)) {
            is RemoteActorLookup.Found -> lookup.actor

            RemoteActorLookup.Gone, is RemoteActorLookup.Unavailable -> {
                InboxSpan.outcome("announce.actor_unavailable")
                return InboxHandling.Ignored("Announce の相手のアクター文書を取得できないので受け付けない: ${recipient.logLabel} ← $actorUri 理由=$lookup")
            }
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
        if (!recorded) {
            return InboxHandling.Ignored("こちらの投稿のブーストを記録しなかった。記録済みか投稿が無い: ${recipient.logLabel} ← $actorUri 投稿=${notePublicId.value}")
        }
        return InboxHandling.Handled
    }

    private suspend fun saveRemoteBoost(
        recipient: InboxRecipient,
        actorUri: String,
        activityUri: String,
        targetUrl: String,
        rawActivityJson: JsonObject,
    ): InboxHandling {
        val note = when (val lookup = remoteNotes.find(targetUrl)) {
            is RemoteNoteLookup.Found -> lookup.note

            is RemoteNoteLookup.NotFound -> {
                InboxSpan.outcome("announce.note_not_found")
                return InboxHandling.Ignored("ブーストされた投稿が無いか読めないので何もしない: ${recipient.logLabel} ← $actorUri object=$targetUrl 理由=${lookup.failure}")
            }

            // 捨てると相手は送り直さないので、投げて inbox から送り直してもらう
            is RemoteNoteLookup.Unavailable -> {
                InboxSpan.outcome("announce.note_unavailable")
                throw IllegalStateException("ブーストされた投稿を今は取得できない: ${recipient.logLabel} ← $actorUri object=$targetUrl 理由=${lookup.failure}")
            }
        }

        // 取得できる時点で公開されているはずだが、宛先を限った投稿を名乗る文書は広めない
        if (note.visibility != NoteVisibility.Public && note.visibility != NoteVisibility.Unlisted) {
            InboxSpan.outcome("announce.note_not_public")
            return InboxHandling.Ignored("公開されていない投稿のブーストなので何もしない: ${recipient.logLabel} ← $actorUri object=$targetUrl")
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
        return InboxHandling.Handled
    }

    companion object {
        const val TYPE: String = "Announce"
    }
}
