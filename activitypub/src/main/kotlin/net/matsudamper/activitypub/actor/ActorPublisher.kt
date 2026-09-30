package net.matsudamper.activitypub.actor

import net.matsudamper.activitypub.activity.ActivityStreamsIri
import net.matsudamper.activitypub.activity.DeleteActorActivity
import net.matsudamper.activitypub.activity.UpdateActorActivity
import net.matsudamper.activitypub.crypto.UuidV7
import net.matsudamper.activitypub.entity.ActivityPubId
import net.matsudamper.activitypub.json.AppJson
import net.matsudamper.activitypub.url.WebPageUrls

/**
 * アクター情報の更新と削除を組み立てる。
 *
 * どちらも DB を触らず HTTP も出さない。保存と投函は `:backend` が repository の
 * 口で 1 トランザクションにまとめる。
 */
class ActorPublisher(
    private val actorKey: ActorKey,
    private val appearances: StoredActorAppearances,
    private val profiles: StoredActorProfiles,
    private val webPages: WebPageUrls?,
) {
    /**
     * 今のアクター情報から `Update{Actor}` を組み立てる。
     *
     * 載せるのは Actor エンドポイントと同じ引き先から組み立てた文書。呼び出し元から
     * 中身を受け取ると、更新の保存とここの組み立てで別々に同じものを作ることになり、
     * 片方だけ変わったときに相手の表示だけが食い違う。
     *
     * @return 署名対象になる JSON。宛先が何件でも同じものを送る
     */
    fun prepareUpdate(sender: ActorUrls): String = AppJson.encodeToString(
        UpdateActorActivity.serializer(),
        UpdateActorActivity(
            // 同じアクターを何度更新しても、相手の重複判定で落ちない id にする
            id = ActivityPubId("${sender.actorId}#update-${UuidV7.generate()}"),
            actor = sender.actorId,
            to = listOf(ActivityStreamsIri.PUBLIC_AUDIENCE),
            updatedActor = actorDocument(
                urls = sender,
                actorKey = actorKey,
                appearance = appearances.find(sender.username),
                profile = profiles.find(sender.username),
                webPages = webPages,
            ),
        ),
    )

    /**
     * アクターを消したことを伝える `Delete{Actor}` を組み立てる。
     *
     * 記録を消すのは呼び出し側で、投函と同じトランザクションになる。
     *
     * @return 署名対象になる JSON
     */
    fun prepareDelete(sender: ActorUrls): String = AppJson.encodeToString(
        DeleteActorActivity.serializer(),
        DeleteActorActivity(
            // 削除のたびに変える。同じ名前で作り直して再度消すと、
            // アクターの id から決めた固定の値では 2 回目が相手の重複判定で落ちる
            id = ActivityPubId("${sender.actorId}#delete-${UuidV7.generate()}"),
            actor = sender.actorId,
            to = listOf(ActivityStreamsIri.PUBLIC_AUDIENCE),
            target = sender.actorId,
        ),
    )
}
