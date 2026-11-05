package miku.moe.app

import android.content.Context

private val genericSimilarGenres = setOf(
    "action", "comedy", "anime", "drama", "fantasy", "adventure", "shounen", "shonen",
    "school", "romance", "slice of life", "supernatural", "ecchi", "tv", "movie", "ova", "ona", "special"
)

private val trailingParticles = setOf("no", "wa", "ni", "to", "de", "ga", "wo", "o", "na", "ha", "he", "e", "mo", "the", "of", "a")

internal fun similarGenreOrder(genres: List<String>): List<String> {
    val clean = genres.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
    val specific = clean.filter { it.lowercase() !in genericSimilarGenres }
    val generic = clean.filter { it.lowercase() in genericSimilarGenres }
    return (specific + generic).take(3)
}

internal fun similarTitleQuery(title: String): String {
    var value = title.trim()
    val colon = value.indexOf(':')
    if (colon > 0) value = value.substring(0, colon)
    val dash = value.indexOf(" - ")
    if (dash > 0) value = value.substring(0, dash)
    value = value.replace(Regex("\\(.*?\\)"), " ")
    value = value.replace(Regex("(?i)\\b(\\d+(st|nd|rd|th)\\s+season|season\\s*\\d+|part\\s*\\d+|final season|sub indo|subtitle indonesia|batch)\\b"), " ")
    value = value.replace(Regex("\\s+\\d+$"), " ")
    val words = value.split(Regex("\\s+")).map { it.trim(',', '.', '!', '?') }.filter { it.isNotEmpty() }.toMutableList()
    while (words.size > 3) words.removeAt(words.size - 1)
    while (words.size > 1 && words.last().lowercase() in trailingParticles) words.removeAt(words.size - 1)
    val query = words.joinToString(" ")
    return if (query.length >= 3) query else ""
}

private fun similarKey(post: AnimePost): String {
    val slug = post.slug.orEmpty().trim().trim('/')
    if (slug.isNotEmpty()) return "s:" + slug.lowercase()
    if (post.categoryId > 0) return "i:" + post.categoryId
    return "n:" + post.categoryName.orEmpty().trim().lowercase()
}

internal class AnimeSimilarSource(context: Context, post: AnimePost, genres: List<String>) {
    private val sourceId = post.sourceId ?: AnimeSettingsManager.SOURCE_DEFAULT
    private val sourceLabel = AnimeSettingsManager.labelForSourceId(post.sourceId)
    private val titleController: BrowseSourceAnimeController? = similarTitleQuery(post.categoryName.orEmpty()).let { query ->
        if (query.isEmpty()) null else BrowseSourceAnimeController(context, sourceId, sourceLabel, query, "", "", false)
    }
    private val genreControllers: List<BrowseSourceAnimeController> = similarGenreOrder(genres).map { genre ->
        BrowseSourceAnimeController(context, sourceId, sourceLabel, "", genre, genre, true)
    }
    private val all: List<BrowseSourceAnimeController> = listOfNotNull(titleController) + genreControllers

    val loading: Boolean
        get() = all.any { it.loading }

    val hasMore: Boolean
        get() = all.any { it.hasMore }

    val isEmptyBasis: Boolean
        get() = all.isEmpty()

    fun start() {
        all.forEach { it.start() }
    }

    fun loadNextPage() {
        all.forEach { it.loadNextPage() }
    }

    fun destroy() {
        all.forEach { it.destroy() }
    }

    fun merged(isSame: (AnimePost) -> Boolean): List<AnimePost> {
        val seen = HashSet<String>()
        val out = ArrayList<AnimePost>()
        fun add(item: AnimePost) {
            if (isSame(item)) return
            if (seen.add(similarKey(item))) out.add(item)
        }
        val titleItems = titleController?.posts?.toList().orEmpty()
        titleItems.take(TITLE_FIRST_COUNT).forEach { add(it) }
        val streams = ArrayList<List<AnimePost>>()
        genreControllers.forEach { streams.add(it.posts.toList()) }
        val titleRest = titleItems.drop(TITLE_FIRST_COUNT)
        if (titleRest.isNotEmpty()) streams.add(titleRest)
        val longest = streams.maxOfOrNull { it.size } ?: 0
        for (index in 0 until longest) {
            for (stream in streams) {
                if (index < stream.size) add(stream[index])
            }
        }
        return out
    }

    private companion object {
        const val TITLE_FIRST_COUNT = 6
    }
}
