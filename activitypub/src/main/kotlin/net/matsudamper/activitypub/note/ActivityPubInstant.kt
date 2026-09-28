package net.matsudamper.activitypub.note

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * ActivityPub で返す公開日時の文字列。
 *
 * ナノ秒まで入れた ISO 8601 は相手の実装で読めないことがある。
 */
internal fun Instant.toActivityPubPublished(): String =
    DateTimeFormatter.ISO_INSTANT.format(truncatedTo(ChronoUnit.MILLIS))
