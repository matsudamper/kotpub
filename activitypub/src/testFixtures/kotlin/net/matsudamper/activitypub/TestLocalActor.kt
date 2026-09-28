package net.matsudamper.activitypub

import net.matsudamper.activitypub.actor.ActorDirectory
import net.matsudamper.activitypub.actor.ActorProfile
import net.matsudamper.activitypub.actor.ActorUrls
import net.matsudamper.activitypub.actor.FeedLinks

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

    const val FEED_ICON_URL: String = "https://feed1.example.org/icon.png"
    const val FEED_HEADER_VERSION: String = "0123456789abcdef"

    val feedLinks: FakeStoredFeedLinks = FakeStoredFeedLinks(
        links = mapOf(
            STORED_USERNAME to FeedLinks(
                siteUrl = "https://feed1.example.org/",
                feedUrl = "https://feed1.example.org/rss.xml",
                iconUrl = FEED_ICON_URL,
                headerVersion = FEED_HEADER_VERSION,
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
