package net.matsudamper.kotpub.activity

import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import net.matsudamper.kotpub.activitypub.LinkOrObject
import net.matsudamper.kotpub.json.AppJson

/**
 * 相手のアクターをブロックしたことを相手のサーバーに伝える。
 *
 * Mastodon などは `Block` を受けると、相手とこちらの間のフォローを両方向とも外す。
 * 解除の `Undo` には元の `Block` を埋め込む。受け取る側は埋め込まれた `Block` から、どのブロックを解いたかを探す
 */
object BlockActivities {
    fun block(id: String, actor: String, target: String): OutgoingActivity =
        OutgoingActivity(id = id, type = TYPE_BLOCK, actor = actor, target = LinkOrObject.Link(target))

    fun undo(id: String, block: OutgoingActivity): OutgoingActivity {
        require(block.type == TYPE_BLOCK)
        return OutgoingActivity(
            id = id,
            type = "Undo",
            actor = block.actor,
            target = LinkOrObject.Embedded(AppJson.encodeToJsonElement(block).jsonObject),
        )
    }

    private const val TYPE_BLOCK = "Block"
}
