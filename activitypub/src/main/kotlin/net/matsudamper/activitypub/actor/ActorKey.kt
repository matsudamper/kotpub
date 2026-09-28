package net.matsudamper.activitypub.actor

import java.nio.file.Path
import java.security.PrivateKey
import java.security.PublicKey
import net.matsudamper.activitypub.crypto.RsaKeys

/**
 * アクターが持つ鍵。
 *
 * 保存するのは秘密鍵だけで、公開鍵はそこから導く。Actor JSON の
 * `publicKey.publicKeyPem` に入れるのは [publicKeyPem]。
 *
 * @param origin どこから読んだ鍵か。起動ログに出すために持つ
 */
class ActorKey(
    val privateKey: PrivateKey,
    val origin: Origin,
) {
    val publicKey: PublicKey by lazy { RsaKeys.derivePublicKey(privateKey) }

    /** Actor JSON にそのまま入る X.509 SubjectPublicKeyInfo の PEM */
    val publicKeyPem: String by lazy { RsaKeys.encodeToPem(publicKey) }

    sealed interface Origin {
        /** 環境変数の PEM をそのまま使った */
        data object Environment : Origin

        /** 既にあったファイルから読んだ */
        data class LoadedFile(
            val path: Path,
        ) : Origin

        /** ファイルが無かったので生成して書き出した */
        data class GeneratedFile(
            val path: Path,
        ) : Origin
    }
}
