package net.matsudamper.kotpub.inbox

import kotlinx.serialization.json.JsonObject
import net.matsudamper.kotpub.activity.InboxActivity

/**
 * inbox が受け取ったアクティビティ 1 種類ぶんの処理。
 *
 * [InboxService] が `type` で選んで呼ぶ。種類が増えるたびに分岐を足すのではなく
 * 実装を足す形にしてあるのは、`Undo` や `Delete` のように後から種類が増えるため。
 * 対応する実装が無い種類は何もせずに 202 で返るので、
 * 未対応のアクティビティを受けても相手に再送させない。
 *
 * 呼ばれる時点で、署名の検証と「署名した鍵の持ち主とアクティビティの実行者が同じか」の
 * 確認は済んでいる。
 */
interface InboxActivityHandler {
    /** 処理するアクティビティの `type`。`"Follow"` のように文字列そのものを返す */
    val type: String

    /**
     * 中身の処理をする。
     *
     * 受け付けられない中身なら、理由を残して投げずに戻る。inbox は 202 を返して捨てる。
     * 記録先に書けないなど、こちらの都合で処理できなかったときは投げる。inbox は 503 を返し、
     * 相手に送り直してもらう。送り直されても同じ結果になる失敗で投げると、相手が諦めるまで再送が続く。
     */
    suspend fun handle(
        recipient: InboxRecipient,
        verifiedSignerActorId: String,
        activity: InboxActivity,
        rawActivityJson: JsonObject,
    )
}
