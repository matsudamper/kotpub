package net.matsudamper.kotpub.inbox

import kotlinx.serialization.json.JsonObject
import net.matsudamper.kotpub.activity.InboxActivity
import net.matsudamper.kotpub.activitypub.LinkOrObject
import net.matsudamper.kotpub.http.HttpUrl
import net.matsudamper.kotpub.note.PollVoteStore
import net.matsudamper.kotpub.note.ReceivedNoteJson
import net.matsudamper.kotpub.note.ReceivedPollVote
import net.matsudamper.kotpub.note.RemoteNoteStore
import net.matsudamper.kotpub.note.UnknownTypeStore

/**
 * 投稿と、こちらの投票への票はどちらも `Create{Note}` で届く。本文の無い `Note` は票として [pollVotes] に渡す。
 * 読み方を知らない型は投稿として受け取らず、[unknownTypes] に記録する
 */
class CreateNoteHandler(
    private val notes: RemoteNoteStore,
    private val pollVotes: PollVoteStore,
    private val unknownTypes: UnknownTypeStore,
) : InboxActivityHandler {
    override val type: String = "Create"

    override suspend fun handle(
        recipient: InboxRecipient,
        verifiedSignerActorId: String,
        activity: InboxActivity,
        rawActivityJson: JsonObject,
    ): InboxHandling {
        val json = (activity.target as? LinkOrObject.Embedded)?.json
            ?: return InboxHandling.Ignored("Create に投稿が埋め込まれていない: $verifiedSignerActorId")
        val actor = activity.actorId ?: return InboxHandling.Ignored("Create に actor が無い: $verifiedSignerActorId")
        val vote = ReceivedNoteJson.readVote(json)
        if (vote != null) return handleVote(vote = vote, actor = actor, verifiedSignerActorId = verifiedSignerActorId)
        val note = ReceivedNoteJson.read(json, rawActivityJson)
            ?: return unknownTypes.recordIfUnknownObject(json, rawActivityJson, actor, verifiedSignerActorId, activityType = type)
        if (actor != verifiedSignerActorId || note.attributedTo != actor) {
            return InboxHandling.Ignored("Create の actor か投稿者が署名者と違う: actor=$actor 投稿者=${note.attributedTo} 署名者=$verifiedSignerActorId")
        }
        val actorUrl = HttpUrl.parse(actor) ?: return InboxHandling.Ignored("Create の actor を URL として読めない: $actor")
        val noteUrl = HttpUrl.parse(note.id) ?: return InboxHandling.Ignored("投稿の id を URL として読めない: ${note.id}")
        if (!actorUrl.isSameHost(noteUrl)) return InboxHandling.Ignored("投稿が actor と別のホスト: ${note.id} actor=$actor")
        val savedAuthor = notes.findAttributedTo(note.id)
        if (savedAuthor != null && savedAuthor != actor) return InboxHandling.Ignored("保存済みの投稿の投稿者が署名者と違う: ${note.id} 投稿者=$savedAuthor 署名者=$actor")
        notes.save(note)
        if (note.unknownTypes.isNotEmpty()) unknownTypes.record(note.unknownTypes, note.id)
        return InboxHandling.Handled
    }

    private suspend fun handleVote(
        vote: ReceivedPollVote,
        actor: String,
        verifiedSignerActorId: String,
    ): InboxHandling {
        if (actor != verifiedSignerActorId || vote.voterActorId != actor) {
            return InboxHandling.Ignored("票の actor か投票者が署名者と違う: actor=$actor 投票者=${vote.voterActorId} 署名者=$verifiedSignerActorId")
        }
        val actorUrl = HttpUrl.parse(actor) ?: return InboxHandling.Ignored("Create の actor を URL として読めない: $actor")
        val voteUrl = HttpUrl.parse(vote.id) ?: return InboxHandling.Ignored("票の id を URL として読めない: ${vote.id}")
        if (!actorUrl.isSameHost(voteUrl)) return InboxHandling.Ignored("票が actor と別のホスト: ${vote.id} actor=$actor")
        pollVotes.vote(vote)
        return InboxHandling.Handled
    }
}
