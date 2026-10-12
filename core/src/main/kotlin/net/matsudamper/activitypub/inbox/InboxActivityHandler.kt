package net.matsudamper.activitypub.inbox

import kotlinx.serialization.json.JsonObject
import net.matsudamper.activitypub.activity.InboxActivity

/**
 * inbox が受け取ったアクティビティ 1 種類ぶんの処理。
 *
 * [InboxService] が `type` で引き当てて呼ぶ。種類が増えるたびに分岐を足すのではなく
 * 実装を足す形にしてあるのは、Phase 3 で `Undo` と `Delete` が、Phase 6 以降で
 * さらに別の種類が乗るため。引き当てられなかった種類は何もせずに 202 で返るので、
 * 未対応のアクティビティを受けても相手に再送させない。
 *
 * 呼ばれる時点で、署名の検証と「署名した鍵の持ち主とアクティビティの実行者が同じか」の
 * 確認は済んでいる。
 */
interface InboxActivityHandler {
    /** 処理するアクティビティの `type`。`"Follow"` のように綴りそのものを返す */
    val type: String

    /**
     * 中身の処理をする。
     *
     * 戻り値を持たないのは、inbox が処理の成否に関わらず 202 を返すため。
     * 失敗しても相手に伝える口が無いので、何が起きたかを残すのは実装側の責任になる。
     */
    suspend fun handle(
        recipient: InboxRecipient,
        verifiedSignerActorId: String,
        activity: InboxActivity,
        rawActivityJson: JsonObject,
    )
}
