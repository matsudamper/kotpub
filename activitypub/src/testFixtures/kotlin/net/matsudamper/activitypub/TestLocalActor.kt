package net.matsudamper.activitypub

import net.matsudamper.activitypub.actor.ActorAppearance
import net.matsudamper.activitypub.actor.ActorDirectory
import net.matsudamper.activitypub.actor.ActorLink
import net.matsudamper.activitypub.actor.ActorProfile
import net.matsudamper.activitypub.actor.ActorUrls

/**
 * テストで配信側に立つ、こちらのアクター。
 *
 * ルーティングのテストはどれもアクターを 1 つ必要とするので、綴りをここに集める。
 * 相手側は [TestRemoteActor]。
 */
object TestLocalActor {
    const val DOMAIN: String = "example.com"
    const val USERNAME: String = "admin"

    /**
     * 別のアカウント。引き当ての対象が複数ある経路を見るときに使う。
     */
    const val STORED_USERNAME: String = "feed1"

    val urls: ActorUrls = ActorUrls(domain = DOMAIN, username = USERNAME)

    val directory: ActorDirectory = ActorDirectory(
        domain = DOMAIN,
        stored = FakeStoredActorNames(storedUserNames = listOf(USERNAME, STORED_USERNAME)),
    )

    const val ICON_VERSION: String = "fedcba9876543210"
    const val HEADER_VERSION: String = "0123456789abcdef"

    /**
     * [STORED_USERNAME] だけがリンクと画像を持つ。持たないアカウントとの差を見るため
     */
    val appearances: FakeStoredActorAppearances = FakeStoredActorAppearances(
        appearances = mapOf(
            STORED_USERNAME to ActorAppearance(
                links = listOf(
                    ActorLink(name = "サイト", url = "https://feed1.example.org/"),
                    ActorLink(name = "危ないリンク", url = "javascript:alert(1)"),
                ),
                iconVersion = ICON_VERSION,
                headerVersion = HEADER_VERSION,
            ),
        ),
    )

    /**
     * [STORED_USERNAME] だけがプロフィールを設定している。未設定との差を見るため。
     */
    val profiles: FakeStoredActorProfiles = FakeStoredActorProfiles(
        profiles = mapOf(
            STORED_USERNAME to ActorProfile(
                displayName = "フィード 1",
                summary = "1 つ目のフィード\n<b>タグ</b>",
            ),
        ),
    )
}
