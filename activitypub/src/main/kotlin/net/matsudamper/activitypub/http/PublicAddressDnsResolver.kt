package net.matsudamper.activitypub.http

import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * 外向きの接続先を、公開アドレスに限る名前解決。
 *
 * 宛先の URL は相手が決める。署名の `keyId` や相手のアクター文書の inbox に、
 * 内部のホスト名や、内部アドレスを返す名前を書かれうる。
 * 接続に使うアドレスそのものを検査しないと、名前解決やリダイレクトの先で内部へ繋がる。
 *
 * Ktor CIO（3.6.0 以降）なら `dnsResolver` にそのまま渡せる。返したアドレスへ名前を引き直さずに繋ぐ。
 *
 * ```kotlin
 * HttpClient(CIO) {
 *     engine {
 *         dnsResolver = PublicAddressDnsResolver::resolve
 *     }
 * }
 * ```
 *
 * プロキシを設定すると名前解決はプロキシ側で行われ、ここを通らない
 */
object PublicAddressDnsResolver {
    /**
     * 解決したアドレスを、名前解決が返した順のまま IP アドレスの表記で返す。
     * 順を変えると、JVM が IPv4 を先に並べている環境で、経路の無い IPv6 から試すことになる。
     *
     * 1 つでも公開アドレスでないものがあれば、全体を [NonPublicAddressException] にする。
     * 内部アドレスを混ぜて返す名前は、正常な相手として扱わない
     *
     * @param hostname IP アドレスの表記でもよい。その場合は名前解決せず、そのアドレスを検査する
     */
    suspend fun resolve(hostname: String): List<String> {
        val addresses =
            withContext(Dispatchers.IO) {
                runInterruptible { InetAddress.getAllByName(hostname).toList() }
            }
        if (addresses.isEmpty()) throw UnknownHostException(hostname)

        val nonPublicAddresses = addresses.filterNot { PublicAddresses.isPublic(it) }
        if (nonPublicAddresses.isNotEmpty()) {
            throw NonPublicAddressException(
                "公開アドレスではない宛先には接続しない: $hostname " +
                    nonPublicAddresses.joinToString(" ") { it.hostAddress },
            )
        }
        return addresses.map { it.hostAddress }
    }
}
