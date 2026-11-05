package miku.moe.app

import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object AniListClient {

    data class AniListCharacter(
        val anilistId: Int,
        val name: String,
        val imageUrl: String,
        val role: String,
        val voiceActor: String,
        val voiceActorImage: String
    )

    data class AniListRecommendation(
        val anilistId: Int,
        val title: String,
        val imageUrl: String
    )

    data class AniListInfo(
        val anilistId: Int,
        val title: String,
        val imageUrl: String,
        val score: Double,
        val rank: Int,
        val popularityRank: Int,
        val users: Int,
        val favourites: Int,
        val status: String,
        val facts: List<Pair<String, String>>,
        val genres: List<String>,
        val characters: List<AniListCharacter>,
        val recommendations: List<AniListRecommendation>
    )

    private const val API_URL = "https://graphql.anilist.co"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36"

    private const val QUERY = """
query (${'$'}search: String, ${'$'}type: MediaType) {
  Page(perPage: 10) {
    media(search: ${'$'}search, type: ${'$'}type, sort: POPULARITY_DESC) {
      id
      title { romaji english native }
      synonyms
      meanScore
      popularity
      favourites
      status
      format
      season
      seasonYear
      episodes
      duration
      chapters
      volumes
      source
      startDate { year month day }
      endDate { year month day }
      genres
      coverImage { large extraLarge }
      rankings { rank type allTime }
      studios { edges { isMain node { name } } }
      staff(perPage: 8) { edges { role node { name { full } } } }
      characters(perPage: 15, sort: [ROLE]) { edges { role node { id name { full } image { large } } voiceActors(language: JAPANESE) { name { full } image { large } } } }
      recommendations(perPage: 25) { edges { node { rating mediaRecommendation { id title { romaji english } coverImage { large } } } } }
    }
  }
}
"""

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val cache = ConcurrentHashMap<String, AniListInfo>()

    suspend fun fetch(rawTitle: String, manga: Boolean): AniListInfo? {
        val cleaned = cleanQueryTitle(rawTitle)
        if (cleaned.isEmpty()) return null
        val key = (if (manga) "m|" else "a|") + normalizeKey(cleaned)
        cache[key]?.let { return it }
        return try {
            val info = withContext(Dispatchers.IO) { load(cleaned, manga) }
            if (info != null) cache[key] = info
            info
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
    }

    private fun load(cleanedTitle: String, manga: Boolean): AniListInfo? {
        var weakFallback: JSONObject? = null
        for (attempt in buildAttempts(cleanedTitle)) {
            val result = searchBest(attempt, manga) ?: continue
            val candidate = result.first
            val score = result.second
            if (score >= 2) return parse(candidate, manga)
            if (weakFallback == null) weakFallback = candidate
        }
        return weakFallback?.let { parse(it, manga) }
    }

    private fun buildAttempts(cleaned: String): List<String> {
        val out = ArrayList<String>()
        fun add(value: String) {
            val v = value.trim()
            if (v.length >= 3 && out.none { it.equals(v, ignoreCase = true) }) out.add(v)
        }
        add(cleaned)
        val noSeason = stripSeasonSuffix(cleaned)
        add(noSeason)
        add(stripYearSuffix(noSeason))
        val words = cleaned.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size > 4) add(words.take(4).joinToString(" "))
        if (words.size > 2) add(words.take(2).joinToString(" "))
        if (out.isEmpty()) out.add(cleaned)
        return out
    }

    private fun searchBest(title: String, manga: Boolean): Pair<JSONObject, Int>? {
        val variables = JSONObject()
        variables.put("search", title)
        variables.put("type", if (manga) "MANGA" else "ANIME")
        val payload = JSONObject()
        payload.put("query", QUERY)
        payload.put("variables", variables)
        val root = postWithRetry(payload) ?: return null
        val list = root.optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media") ?: return null
        if (list.length() == 0) return null
        val wanted = normalizeKey(title)
        var best: JSONObject? = null
        var bestScore = -1
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val score = matchScore(item, wanted)
            if (score > bestScore) {
                bestScore = score
                best = item
            }
        }
        return best?.let { it to bestScore }
    }

    private fun matchScore(item: JSONObject, wanted: String): Int {
        if (wanted.isEmpty()) return 0
        val names = ArrayList<String>()
        val t = item.optJSONObject("title")
        if (t != null) {
            names.add(t.optString("romaji"))
            names.add(t.optString("english"))
            names.add(t.optString("native"))
        }
        val syn = item.optJSONArray("synonyms")
        if (syn != null) for (j in 0 until syn.length()) names.add(syn.optString(j))
        var score = 0
        for (raw in names) {
            val name = normalizeKey(raw)
            if (name.isEmpty()) continue
            if (name == wanted) return 3
            if (score < 2 && name.length >= 4 && wanted.length >= 4 && (name.startsWith(wanted) || wanted.startsWith(name))) score = 2
            if (score < 1 && (name.contains(wanted) || wanted.contains(name))) score = 1
        }
        return score
    }

    private fun parse(o: JSONObject, manga: Boolean): AniListInfo {
        val id = o.optInt("id", 0)
        val titleObj = o.optJSONObject("title")
        val romaji = titleObj?.optString("romaji").orEmpty()
        val english = titleObj?.optString("english").orEmpty()
        val nativeTitle = titleObj?.optString("native").orEmpty()
        val title = english.ifBlank { romaji }
        val cover = o.optJSONObject("coverImage")
        val image = cover?.optString("extraLarge").orEmpty()
            .ifBlank { cover?.optString("large").orEmpty() }
        var rank = 0
        var popularityRank = 0
        val rankings = o.optJSONArray("rankings")
        if (rankings != null) {
            for (i in 0 until rankings.length()) {
                val r = rankings.optJSONObject(i) ?: continue
                if (!r.optBoolean("allTime")) continue
                when (r.optString("type")) {
                    "RATED" -> rank = r.optInt("rank", 0)
                    "POPULAR" -> popularityRank = r.optInt("rank", 0)
                }
            }
        }
        val statusRaw = o.optString("status")
        val facts = ArrayList<Pair<String, String>>()
        val format = formatLabel(o.optString("format"))
        if (format.isNotBlank()) facts.add("Tipe" to format)
        if (romaji.isNotBlank() && !romaji.equals(title, ignoreCase = true)) facts.add("Judul Romaji" to romaji)
        if (english.isNotBlank() && !english.equals(title, ignoreCase = true)) facts.add("Judul Inggris" to english)
        if (nativeTitle.isNotBlank() && !nativeTitle.equals(title, ignoreCase = true)) facts.add("Judul Jepang" to nativeTitle)
        val altNames = ArrayList<String>()
        val syn = o.optJSONArray("synonyms")
        if (syn != null) {
            for (i in 0 until syn.length()) {
                val value = syn.optString(i)
                if (value.isBlank()) continue
                if (value.equals(title, true) || value.equals(romaji, true) || value.equals(english, true) || value.equals(nativeTitle, true)) continue
                if (altNames.none { it.equals(value, true) }) altNames.add(value)
                if (altNames.size >= 3) break
            }
        }
        if (altNames.isNotEmpty()) facts.add("Judul Alternatif" to altNames.joinToString(", "))
        val airingLabel = if (manga) "Terbit" else "Tayang"
        val rangeText = dateRangeText(o.optJSONObject("startDate"), o.optJSONObject("endDate"), statusRaw)
        if (manga) {
            val staffNames = staffByRole(o)
            val story = staffNames.first
            val art = staffNames.second
            if (story.isNotBlank() && art.isNotBlank() && story.equals(art, ignoreCase = true)) {
                facts.add("Pengarang" to story)
            } else {
                if (story.isNotBlank()) facts.add("Cerita" to story)
                if (art.isNotBlank()) facts.add("Gambar" to art)
                if (story.isBlank() && art.isBlank() && staffNames.third.isNotBlank()) facts.add("Pengarang" to staffNames.third)
            }
            if (rangeText.isNotBlank()) facts.add(airingLabel to rangeText)
            val chapters = if (o.isNull("chapters")) 0 else o.optInt("chapters", 0)
            if (chapters > 0) facts.add("Chapter" to chapters.toString())
            val volumes = if (o.isNull("volumes")) 0 else o.optInt("volumes", 0)
            if (volumes > 0) facts.add("Volume" to volumes.toString())
        } else {
            val studioNames = studioNamesByMain(o, true)
            if (studioNames.isNotEmpty()) facts.add("Studio" to studioNames.joinToString(", "))
            val producerNames = studioNamesByMain(o, false)
            if (producerNames.isNotEmpty()) facts.add("Produser" to producerNames.take(3).joinToString(", "))
            val season = o.optString("season")
            val year = if (o.isNull("seasonYear")) 0 else o.optInt("seasonYear", 0)
            if (season.isNotBlank()) facts.add("Musim" to (seasonLabel(season) + if (year > 0) " $year" else ""))
            if (rangeText.isNotBlank()) facts.add(airingLabel to rangeText)
            val episodes = if (o.isNull("episodes")) 0 else o.optInt("episodes", 0)
            if (episodes > 0) facts.add("Episode" to episodes.toString())
            val duration = if (o.isNull("duration")) 0 else o.optInt("duration", 0)
            if (duration > 0) facts.add("Durasi" to (duration.toString() + if (o.optString("format") == "MOVIE") " menit" else " menit/episode"))
        }
        val source = sourceLabel(o.optString("source"))
        if (source.isNotBlank()) facts.add("Sumber" to source)
        val genres = ArrayList<String>()
        val genreArray = o.optJSONArray("genres")
        if (genreArray != null) for (i in 0 until genreArray.length()) {
            val name = genreArray.optString(i)
            if (name.isNotBlank()) genres.add(name)
        }
        val characters = ArrayList<AniListCharacter>()
        val charEdges = o.optJSONObject("characters")?.optJSONArray("edges")
        if (charEdges != null) {
            for (i in 0 until charEdges.length()) {
                val edge = charEdges.optJSONObject(i) ?: continue
                val node = edge.optJSONObject("node") ?: continue
                val charName = node.optJSONObject("name")?.optString("full").orEmpty()
                if (charName.isBlank()) continue
                val charImage = node.optJSONObject("image")?.optString("large").orEmpty()
                var vaName = ""
                var vaImage = ""
                val vas = edge.optJSONArray("voiceActors")
                if (vas != null && vas.length() > 0) {
                    val vaNode = vas.optJSONObject(0)
                    vaName = vaNode?.optJSONObject("name")?.optString("full").orEmpty()
                    vaImage = vaNode?.optJSONObject("image")?.optString("large").orEmpty()
                }
                characters.add(AniListCharacter(node.optInt("id", 0), charName, charImage, edge.optString("role"), vaName, vaImage))
            }
        }
        val recRows = ArrayList<Pair<Int, AniListRecommendation>>()
        val seenRec = HashSet<Int>()
        val edges = o.optJSONObject("recommendations")?.optJSONArray("edges")
        if (edges != null) {
            for (i in 0 until edges.length()) {
                val node = edges.optJSONObject(i)?.optJSONObject("node") ?: continue
                val rec = node.optJSONObject("mediaRecommendation") ?: continue
                val recId = rec.optInt("id", 0)
                if (recId <= 0 || recId == id || !seenRec.add(recId)) continue
                val recTitleObj = rec.optJSONObject("title")
                val recTitle = recTitleObj?.optString("english").orEmpty()
                    .ifBlank { recTitleObj?.optString("romaji").orEmpty() }
                if (recTitle.isBlank()) continue
                val recImage = rec.optJSONObject("coverImage")?.optString("large").orEmpty()
                recRows.add(node.optInt("rating", 0) to AniListRecommendation(recId, recTitle, recImage))
            }
        }
        return AniListInfo(
            anilistId = id,
            title = title,
            imageUrl = image,
            score = (if (o.isNull("meanScore")) 0 else o.optInt("meanScore", 0)) / 10.0,
            rank = rank,
            popularityRank = popularityRank,
            users = o.optInt("popularity", 0),
            favourites = o.optInt("favourites", 0),
            status = statusLabel(statusRaw, manga),
            facts = facts,
            genres = genres.take(8),
            characters = characters,
            recommendations = recRows.sortedByDescending { it.first }.take(15).map { it.second }
        )
    }

    private fun studioNamesByMain(o: JSONObject, main: Boolean): List<String> {
        val edges = o.optJSONObject("studios")?.optJSONArray("edges") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until edges.length()) {
            val edge = edges.optJSONObject(i) ?: continue
            if (edge.optBoolean("isMain") != main) continue
            val name = edge.optJSONObject("node")?.optString("name").orEmpty()
            if (name.isNotBlank() && name !in out) out.add(name)
        }
        return out
    }

    private fun staffByRole(o: JSONObject): Triple<String, String, String> {
        val edges = o.optJSONObject("staff")?.optJSONArray("edges") ?: return Triple("", "", "")
        var story = ""
        var originalStory = ""
        var art = ""
        var first = ""
        for (i in 0 until edges.length()) {
            val edge = edges.optJSONObject(i) ?: continue
            val name = edge.optJSONObject("node")?.optJSONObject("name")?.optString("full").orEmpty()
            if (name.isBlank()) continue
            if (first.isEmpty()) first = name
            val role = edge.optString("role")
            if (originalStory.isEmpty() && role.contains("Original Story", ignoreCase = true)) originalStory = name
            if (story.isEmpty() && role.contains("Story", ignoreCase = true)) story = name
            if (art.isEmpty() && role.contains("Art", ignoreCase = true)) art = name
        }
        return Triple(originalStory.ifBlank { story }, art, first)
    }

    private fun postWithRetry(payload: JSONObject): JSONObject? {
        for (attempt in 1..3) {
            try {
                val result = executeOnce(payload)
                val code = result.first
                val text = result.second
                if (code in 200..299) {
                    if (text.isEmpty()) return null
                    return try {
                        JSONObject(text)
                    } catch (e: Throwable) {
                        null
                    }
                }
                if (code == 429 || code >= 500) {
                    if (attempt < 3) sleepRetry(result.third, attempt)
                    continue
                }
                return null
            } catch (e: IOException) {
                if (attempt < 3) sleepRetry(null, attempt)
            }
        }
        return null
    }

    private fun executeOnce(payload: JSONObject): Triple<Int, String, Long?> {
        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(API_URL)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .post(body)
            .build()
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val retryAfter = response.header("Retry-After")?.toLongOrNull()
            return Triple(response.code, text, retryAfter)
        }
    }

    private fun sleepRetry(retryAfterSeconds: Long?, attempt: Int) {
        val millis = if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            minOf(retryAfterSeconds * 1000L, 6000L)
        } else {
            1200L * attempt * attempt
        }
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun fuzzyDateText(date: JSONObject?): String {
        if (date == null) return ""
        val year = if (date.isNull("year")) 0 else date.optInt("year", 0)
        if (year <= 0) return ""
        val month = if (date.isNull("month")) 0 else date.optInt("month", 0)
        val day = if (date.isNull("day")) 0 else date.optInt("day", 0)
        val monthNames = arrayOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu", "Sep", "Okt", "Nov", "Des")
        val monthText = if (month in 1..12) monthNames[month - 1] else ""
        return when {
            day > 0 && monthText.isNotEmpty() -> "$day $monthText $year"
            monthText.isNotEmpty() -> "$monthText $year"
            else -> year.toString()
        }
    }

    private fun dateRangeText(start: JSONObject?, end: JSONObject?, statusRaw: String): String {
        val startText = fuzzyDateText(start)
        if (startText.isEmpty()) return ""
        val endText = fuzzyDateText(end)
        if (endText.isNotEmpty() && endText != startText) return "$startText – $endText"
        if (endText == startText && endText.isNotEmpty()) return startText
        return if (statusRaw == "RELEASING") "$startText – sekarang" else startText
    }

    private fun formatLabel(format: String): String = when (format) {
        "TV" -> "TV"
        "TV_SHORT" -> "TV Short"
        "MOVIE" -> "Movie"
        "SPECIAL" -> "Special"
        "OVA" -> "OVA"
        "ONA" -> "ONA"
        "MUSIC" -> "Music"
        "MANGA" -> "Manga"
        "NOVEL" -> "Novel"
        "ONE_SHOT" -> "One Shot"
        else -> ""
    }

    private fun seasonLabel(season: String): String = when (season) {
        "WINTER" -> "Winter"
        "SPRING" -> "Spring"
        "SUMMER" -> "Summer"
        "FALL" -> "Fall"
        else -> season
    }

    private fun sourceLabel(source: String): String = when (source) {
        "MANGA" -> "Manga"
        "LIGHT_NOVEL" -> "Light Novel"
        "VISUAL_NOVEL" -> "Visual Novel"
        "VIDEO_GAME" -> "Video Game"
        "ORIGINAL" -> "Original"
        "NOVEL" -> "Novel"
        "DOUJIN" -> "Doujin"
        "ANIME" -> "Anime"
        "WEB_NOVEL" -> "Web Novel"
        "LIVE_ACTION" -> "Live Action"
        "GAME" -> "Game"
        "COMIC" -> "Komik"
        "MULTIMEDIA_PROJECT" -> "Proyek Multimedia"
        "PICTURE_BOOK" -> "Buku Bergambar"
        "OTHER" -> "Lainnya"
        else -> ""
    }

    private fun statusLabel(status: String, manga: Boolean): String = when (status) {
        "RELEASING" -> if (manga) "Sedang Terbit" else "Sedang Tayang"
        "FINISHED" -> "Tamat"
        "NOT_YET_RELEASED" -> "Belum Rilis"
        "CANCELLED" -> "Dibatalkan"
        "HIATUS" -> "Hiatus"
        else -> ""
    }

    private fun cleanQueryTitle(raw: String): String {
        var t = raw.replace(Regex("\\[[^\\]]*\\]"), " ")
        t = t.replace(Regex("\\([^)]*\\)"), " ")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    private fun stripSeasonSuffix(title: String): String {
        var t = title.replace(Regex("(?i)\\s+(season|part|cour)\\s*\\d+.*$"), "")
        t = t.replace(Regex("(?i)\\s+s\\d+\\s*$"), "")
        return t.trim()
    }

    private fun stripYearSuffix(title: String): String {
        return title.replace(Regex("\\s+(19|20)\\d{2}\\s*$"), "").trim()
    }

    private fun normalizeKey(value: String): String =
        value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
}
