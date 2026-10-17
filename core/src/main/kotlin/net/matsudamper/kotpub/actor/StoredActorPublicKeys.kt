package net.matsudamper.kotpub.actor

/**
 * アクターの公開鍵の参照先。
 *
 * Actor エンドポイントが載せるのは公開鍵だけなので、秘密鍵まで読まずに済む形にする。
 * どこに保存されているかは Actor 側の関心ではないので、
 * [StoredActorNames] と同じく名前を渡して引けることだけを決めておく。
 */
interface StoredActorPublicKeys {
    /**
     * 名前で引く。返すのは Actor JSON の `publicKey.publicKeyPem` にそのまま入る
     * X.509 SubjectPublicKeyInfo の PEM（[ActorPrivateKey.publicKeyPem] と同じ形）。
     *
     * 渡すのは [StoredActorNames] が返した保存側の綴り。
     * 鍵の無いアクターは署名も検証もされないので、存在するアクターには必ず鍵がある前提
     */
    fun find(username: String): String
}
