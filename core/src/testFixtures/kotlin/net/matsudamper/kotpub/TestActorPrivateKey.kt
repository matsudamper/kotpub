package net.matsudamper.kotpub

import net.matsudamper.kotpub.actor.ActorPrivateKey
import net.matsudamper.kotpub.crypto.RsaKeys

/**
 * テストで使い回すアクターの鍵。
 *
 * 2048bit の鍵生成はテストごとに走らせると無視できない時間になるので、
 * 中身を問わないテストはこれを共有する。
 */
object TestActorPrivateKey {
    val value: ActorPrivateKey by lazy {
        ActorPrivateKey(
            privateKey = RsaKeys.generateKeyPair().private,
            origin = ActorPrivateKey.Origin.Environment,
        )
    }
}
