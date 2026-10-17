package net.matsudamper.kotpub.actor

/**
 * こちらのアクターとして署名したり、自分の文書を組み立てたりするときに渡す値。
 *
 * 鍵は保存場所から引くものではなく、呼び出し元がその操作の間だけ載せて渡す。
 * 配信のたびに名前から鍵を引き直すと、呼び出し元が既に読んでいるアクターを
 * もう一度読むことになる。
 *
 * @param urls 名前から機械的に決まる識別子と URL
 * @param privateKey このアクターの秘密鍵。署名と、公開する `publicKey` の元になる
 */
data class LocalActor(
    val urls: ActorUrls,
    val privateKey: ActorPrivateKey,
)
