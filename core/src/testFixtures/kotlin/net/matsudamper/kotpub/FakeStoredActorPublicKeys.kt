package net.matsudamper.kotpub

import net.matsudamper.kotpub.actor.StoredActorPublicKeys

/**
 * @param publicKeyPems 名前ごとの公開鍵 PEM。無い名前は [TestActorPrivateKey] の公開鍵を返す
 */
class FakeStoredActorPublicKeys(
    private val publicKeyPems: Map<String, String> = mapOf(),
) : StoredActorPublicKeys {
    override fun find(username: String): String {
        return publicKeyPems[username] ?: TestActorPrivateKey.value.publicKeyPem
    }
}
