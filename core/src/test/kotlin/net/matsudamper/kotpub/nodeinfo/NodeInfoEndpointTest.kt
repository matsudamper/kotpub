package net.matsudamper.kotpub.nodeinfo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import net.matsudamper.kotpub.json.AppJson

class NodeInfoEndpointTest {
    @Test
    fun `使う側が数えたユーザー数と投稿数と登録の可否を返す`() {
        val endpoint =
            NodeInfoEndpoint(
                domain = "example.com",
                software = NodeInfoSoftware(name = "example", version = "1.0.0"),
                openRegistrations = true,
                usageSource = { NodeInfoUsage(users = NodeInfoUsers(total = 3), localPosts = 42) },
            )

        val response = runBlocking { endpoint.nodeInfo() }
        val nodeInfo = AppJson.decodeFromString(NodeInfo.serializer(), response.body.decodeToString())

        assertEquals(true, nodeInfo.openRegistrations)
        assertEquals(NodeInfoUsage(users = NodeInfoUsers(total = 3), localPosts = 42), nodeInfo.usage)
    }
}
