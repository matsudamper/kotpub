package net.matsudamper.kotpub.nodeinfo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /.well-known/nodeinfo` のレスポンス。
 *
 * NodeInfo (https://nodeinfo.diaspora.software/) の入口。ドメインだけから
 * `/nodeinfo/2.1` の URL を機械的に組み立てられるが、仕様上は必ずこの discovery
 * document を経由させる決まりになっている。
 */
@Serializable
data class NodeInfoDiscovery(
    @SerialName("links")
    val links: List<NodeInfoDiscoveryLink>,
)

/**
 * @param rel NodeInfo のスキーマバージョンを表す固定 URI
 * @param href `/nodeinfo/2.1` の絶対 URL
 */
@Serializable
data class NodeInfoDiscoveryLink(
    @SerialName("rel")
    val rel: String,
    @SerialName("href")
    val href: String,
) {
    companion object {
        const val REL_2_1: String = "http://nodeinfo.diaspora.software/ns/schema/2.1"
    }
}

/**
 * `GET /nodeinfo/2.1` のレスポンス本体。
 */
@Serializable
data class NodeInfo(
    @SerialName("version")
    val version: String = "2.1",
    @SerialName("software")
    val software: NodeInfoSoftware,
    @SerialName("protocols")
    val protocols: List<String> = listOf("activitypub"),
    @SerialName("services")
    val services: NodeInfoServices = NodeInfoServices(),
    @SerialName("openRegistrations")
    val openRegistrations: Boolean = false,
    @SerialName("usage")
    val usage: NodeInfoUsage,
    @SerialName("metadata")
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * @param repository ソース公開先。NodeInfo 2.1 で software に追加された任意フィールド
 */
@Serializable
data class NodeInfoSoftware(
    @SerialName("name")
    val name: String,
    @SerialName("version")
    val version: String,
    @SerialName("repository")
    val repository: String? = null,
)

@Serializable
data class NodeInfoServices(
    @SerialName("inbound")
    val inbound: List<String> = emptyList(),
    @SerialName("outbound")
    val outbound: List<String> = emptyList(),
)

/**
 * @param localPosts このサーバーのユーザーが書いた投稿の数
 */
@Serializable
data class NodeInfoUsage(
    @SerialName("users")
    val users: NodeInfoUsers,
    @SerialName("localPosts")
    val localPosts: Long,
)

/**
 * @param total このサーバーのユーザー数
 */
@Serializable
data class NodeInfoUsers(
    @SerialName("total")
    val total: Long,
)
