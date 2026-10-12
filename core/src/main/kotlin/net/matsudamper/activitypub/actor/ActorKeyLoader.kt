package net.matsudamper.activitypub.actor

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import net.matsudamper.activitypub.crypto.RsaKeys

/**
 * [ActorPrivateKey] に従ってアクターの秘密鍵を用意する。
 *
 * ファイル指定で中身が無い場合だけ新しく生成する。既にあるファイルを書き換えることはしない。
 */
object ActorKeyLoader {
    fun load(
        config: ActorPrivateKey,
    ): ActorKey? =
        when (config) {
            is ActorPrivateKey.Pem -> {
                ActorKey(
                    privateKey = decode(config.pem) { "ACTOR_PRIVATE_KEY_PEM の PEM を読めなかった" },
                    origin = ActorKey.Origin.Environment,
                )
            }

            is ActorPrivateKey.File -> {
                loadFromFile(config.path.toAbsolutePath().normalize())
            }
        }

    fun create(
        file: ActorPrivateKey.File,
    ): ActorKey {
        val path = file.path.toAbsolutePath().normalize()
        val keyPair = RsaKeys.generateKeyPair()
        write(path, RsaKeys.encodeToPem(keyPair.private))
        return ActorKey(
            privateKey = keyPair.private,
            origin = ActorKey.Origin.GeneratedFile(path),
        )
    }

    private fun loadFromFile(
        path: Path,
    ): ActorKey? {
        if (Files.exists(path)) {
            return ActorKey(
                privateKey = decode(Files.readString(path)) { "$path の PEM を読めなかった" },
                origin = ActorKey.Origin.LoadedFile(path),
            )
        }

        return null
    }

    /**
     * 読めない PEM をそのまま JCA の例外で落とすと、どの設定が悪いのか分からない。
     * 鍵の中身は出さずに、どこから読んだかだけを添えて包み直す。
     */
    private fun decode(
        pem: String,
        message: () -> String,
    ) = try {
        RsaKeys.decodePrivateKeyPem(pem)
    } catch (e: Exception) {
        throw IllegalArgumentException(message(), e)
    }

    /**
     * 秘密鍵を書き出す。
     *
     * 中身を入れる前に所有者だけが読める空ファイルを作る。先に書いてから権限を変えると、
     * その隙に他のユーザーから読めてしまう。
     */
    private fun write(
        path: Path,
        pem: String,
    ) {
        Files.createDirectories(path.parent)

        if (path.fileSystem.supportedFileAttributeViews().contains("posix")) {
            val permissions =
                PosixFilePermissions.asFileAttribute(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
            Files.createFile(path, permissions)
        } else {
            Files.createFile(path)
        }

        Files.writeString(path, pem)
    }
}
