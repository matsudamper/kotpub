package net.matsudamper.kotpub.emoji

/**
 * 相手のサーバーのカスタム絵文字。本文や表示名には [name] の文字だけが入るので、画像は別に持つ
 *
 * @param name `:name:` の形。本文や表示名に現れる文字そのまま
 * @param imageUrl 相手のサーバーが書いた画像の URL。http か https のものだけ
 */
data class CustomEmoji(
    val name: String,
    val imageUrl: String,
)
