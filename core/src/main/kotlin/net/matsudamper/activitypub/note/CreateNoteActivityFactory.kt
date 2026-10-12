package net.matsudamper.activitypub.note

import net.matsudamper.activitypub.activity.ActivityStreamsIri
import net.matsudamper.activitypub.activity.CreateNoteActivity
import net.matsudamper.activitypub.actor.ActorUrls
import net.matsudamper.activitypub.url.WebPageUrls

/**
 * 保存済みの投稿から、配信する `Create{Note}` を組み立てる。
 *
 * 初回配信と、フォロー成立後の再配信で同じものを組み立てる必要がある。
 * 相手は `Create.id` と `Note.id` で重複を判断するので、組み立てを 2 か所に書いて
 * 片方だけ変わると、同じ投稿がタイムラインに 2 度並ぶ。
 */
internal object CreateNoteActivityFactory {
    fun create(
        sender: ActorUrls,
        note: StoredNote,
        webPages: WebPageUrls?,
    ): CreateNoteActivity {
        val urls = NoteUrls(domain = sender.domain, publicId = note.publicId)
        val published = note.publishedAt.toActivityPubPublished()

        return CreateNoteActivity(
            id = urls.createId,
            actor = sender.actorId,
            published = published,
            to = listOf(ActivityStreamsIri.PUBLIC_AUDIENCE),
            cc = listOf(sender.followers),
            target = Note(
                id = urls.noteId,
                attributedTo = sender.actorId,
                content = note.contentHtml,
                published = published,
                to = listOf(ActivityStreamsIri.PUBLIC_AUDIENCE),
                cc = listOf(sender.followers),
                url = webPages?.note(username = sender.username, publicId = note.publicId),
                atomUri = urls.noteUrl,
            ),
        )
    }
}
