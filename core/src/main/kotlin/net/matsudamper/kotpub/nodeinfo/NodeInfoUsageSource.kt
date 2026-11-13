package net.matsudamper.kotpub.nodeinfo

/**
 * NodeInfo に載せるユーザー数・投稿数の取得元。使う側のアプリケーションが DB などから数える
 */
fun interface NodeInfoUsageSource {
    suspend fun usage(): NodeInfoUsage
}
