package net.matsudamper.kotpub.inbox

import kotlinx.serialization.json.JsonObject
import net.matsudamper.kotpub.note.ReceivedNoteJson
import net.matsudamper.kotpub.note.UnknownTypeStore

/**
 * 投稿として読めなかった object が知らない型なら、型と出どころを記録する。どちらにしても投稿としては受け取らない
 *
 * @param activityType ログに出すアクティビティの `type`
 */
internal suspend fun UnknownTypeStore.recordIfUnknownObject(
    obj: JsonObject,
    activity: JsonObject,
    actor: String,
    verifiedSignerActorId: String,
    activityType: String,
): InboxHandling {
    val unknown = ReceivedNoteJson.readUnknownObject(obj, activity)
        ?: return InboxHandling.Ignored("$activityType の object を投稿として読めない: $verifiedSignerActorId")
    if (actor != verifiedSignerActorId) {
        return InboxHandling.Ignored("$activityType の actor が署名者と違う: actor=$actor 署名者=$verifiedSignerActorId")
    }
    record(unknown.types, unknown.sourceUrl)
    return InboxHandling.Ignored("$activityType の object が知らない型: types=${unknown.types} source=${unknown.sourceUrl}")
}
