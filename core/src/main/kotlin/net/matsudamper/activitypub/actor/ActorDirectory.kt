package net.matsudamper.activitypub.actor

/**
 * リクエストで指定された名前から、どのアクターを指しているかを決める。
 *
 * 引き当てる先は [StoredActorNames] だけ。無ければ存在しない。
 *
 * WebFinger の `resource` とパスの `{username}` で判定がずれると、
 * 「検索には出るが開けない」という分かりにくい壊れ方をするので、
 * 両方の入口をここに通す。
 */
class ActorDirectory(
    private val domain: String,
    private val stored: StoredActorNames,
) {
    /**
     * パスの `{username}` から引く。
     *
     * 大文字小文字は保存側で吸収する。返すのは保存されている綴り。
     */
    fun resolve(username: String?): ActorUrls? {
        if (username.isNullOrEmpty()) return null

        // 保存されている名前は必ずこの形式なので、引く前に落とせる。
        // `test-1/inbox` のようにパスを含んだものが保存先まで届かなくなる
        if (!ActorUsernameUtil.isValid(username)) return null

        val found = stored.find(username) ?: return null

        return ActorUrls(domain = domain, username = found)
    }

    fun resolve(usernames: Set<String>): Map<String, ActorUrls> {
        if (usernames.isEmpty()) return emptyMap()
        val candidates = usernames.filter { it.isNotEmpty() && ActorUsernameUtil.isValid(it) }
        if (candidates.isEmpty()) return emptyMap()

        val found = stored.finds(candidates.toSet())
        return buildMap {
            for (name in candidates) {
                val foundUser = found[name] ?: continue
                put(name, ActorUrls(domain = domain, username = foundUser))
            }
        }
    }

    fun resolveActorId(actorId: String): ActorUrls? {
        val actorUrlPrefix = "https://$domain/users/"
        if (actorId.length <= actorUrlPrefix.length || !actorId.startsWith(actorUrlPrefix, ignoreCase = true)) return null

        // 残りがパスを含んでいたらユーザー名として不正になり、
        // resolve が弾く（`/` は使える文字に入っていない）
        return resolve(actorId.substring(actorUrlPrefix.length))
    }

    /**
     * WebFinger の `resource` から引く。
     *
     * Mastodon は `acct:name@domain` で引いてくるが、実装によっては Actor の URL を
     * そのまま渡してくるものもあるので両方を受ける。`acct:` を省いた形も受ける。
     */
    fun resolveResource(resource: String): ActorUrls? {
        val trimmed = resource.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("https://$domain/users/", ignoreCase = true)) {
            return resolveActorId(trimmed)
        }

        val withoutScheme =
            if (trimmed.startsWith(ACCT_SCHEME, ignoreCase = true)) {
                trimmed.substring(ACCT_SCHEME.length)
            } else {
                trimmed
            }

        // ユーザー名に @ は使えないので、区切りは最後の @
        val separator = withoutScheme.lastIndexOf('@')
        if (separator <= 0) return null

        val host = withoutScheme.substring(separator + 1)
        if (!host.equals(domain, ignoreCase = true)) return null

        return resolve(withoutScheme.substring(0, separator))
    }

    private companion object {
        const val ACCT_SCHEME = "acct:"
    }
}
