package net.matsudamper.activitypub.httpsignature

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * `Date` ヘッダの読み書き。
 *
 * HTTP Signatures では `date` が署名対象に入るので、送るときの綴りと
 * 相手が読む綴りが一致していないと検証が通らない。読む側と書く側を
 * ここに並べて、片方だけ形式が変わることを防ぐ。
 *
 * 扱うのは `Tue, 20 Apr 2021 02:07:55 GMT` の形（RFC 9110 の IMF-fixdate）だけ。
 * 旧形式を送ってくる実装は現存しないうえ、緩く読むと時刻のずれの判定が甘くなる。
 */
object HttpDate {
    /** 曜日と月は英語の綴りで固定する。実行環境のロケールに引きずられると相手が読めない */
    private val FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)

    /**
     * `ZoneOffset.UTC` で組み立てると `zzz` が `GMT` ではなく `Z` になる。
     * RFC 1123 のパーサは `Z` を受け付けないので、実際に流通している `GMT` に揃える。
     */
    private val GMT: ZoneId = ZoneId.of("GMT")

    fun format(instant: Instant): String = FORMATTER.format(instant.atZone(GMT))

    /** 読めなければ null。受信するのは相手が作った文字列なので例外にはしない */
    fun parse(value: String): Instant? =
        runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }
            .getOrNull()
}
