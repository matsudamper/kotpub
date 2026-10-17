package net.matsudamper.kotpub.actor

import net.matsudamper.kotpub.activity.ActivityStreamsIri
import net.matsudamper.kotpub.activity.DeleteActorActivity
import net.matsudamper.kotpub.activity.UpdateActorActivity
import net.matsudamper.kotpub.crypto.UuidV7
import net.matsudamper.kotpub.entity.ActivityPubId
import net.matsudamper.kotpub.json.AppJson
import net.matsudamper.kotpub.url.WebPageUrls

/**
 * アクター情報の更新と削除を組み立てる。
 *
 * どちらも DB を触らず HTTP も出さない。保存と配信キューへの登録は `:backend` が repository の
 * メソッドで 1 トランザクションにまとめる。
 */
class ActorPublisher(
    private val appearances: StoredActorAppearances,
    private val profiles: StoredActorProfiles,
    private val webPages: WebPageUrls?,
) {
    /**
     * 今のアクター情報から `Update{Actor}` を組み立てる。
     *
     * 載せるのは Actor エンドポイントと同じ元データから組み立てた文書。呼び出し元から
     * 中身を受け取ると、更新の保存とここの組み立てで別々に同じものを作ることになり、
     * 片方だけ変わったときに相手の表示だけが食い違う。
     *
     * @param sender 更新するアクター。載せる `publicKey` はこの鍵から導く
     * @return 署名対象になる JSON。宛先が何件でも同じものを送る
     */
    fun prepareUpdate(sender: LocalActor): String = AppJson.encodeToString(
        UpdateActorActivity.serializer(),
        UpdateActorActivity(
            // 同じアクターを何度更新しても、相手の重複判定で落ちない id にする
            id = ActivityPubId("${sender.urls.actorId}#update-${UuidV7.generate()}"),
            actor = sender.urls.actorId,
            to = listOf(ActivityStreamsIri.PUBLIC_AUDIENCE),
            updatedActor = actorDocument(
                urls = sender.urls,
                publicKeyPem = sender.privateKey.publicKeyPem,
                appearance = appearances.find(sender.urls.username),
                profile = profiles.find(sender.urls.username),
                webPages = webPages,
            ),
        ),
    )

    /**
     * アクターを消したことを伝える `Delete{Actor}` を組み立てる。
     *
     * 記録を消すのは呼び出し側で、配信キューへの登録と同じトランザクションになる。
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
