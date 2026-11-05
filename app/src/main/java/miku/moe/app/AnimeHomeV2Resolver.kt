package miku.moe.app

import android.net.Uri
import android.os.Build
import android.text.Html
import android.os.Handler
import android.os.Looper
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Enrichment genre/status/total-episode untuk kartu Home Anime V2.
 *
 * Perbaikan 2026-10-08:
 * 1. HENTIKAN infinite rebind loop (penyebab scroll "geter"/"ketarik" khusus di
 *    Anime X Nonton): dulu `needsResolve()` selalu true untuk item yang genre-nya
 *    tak bisa di-resolve, dan `request()` tetap memicu callback walau cache hit
 *    tanpa data baru -> notifyItemChanged -> rebind -> request -> ... selamanya.
 *    Sekarang: key yang fetch-nya sudah SUKSES dicatat di `completed` (tidak
 *    di-fetch ulang), dan callback hanya dipicu bila `apply()` benar-benar
 *    mengubah data post.
 * 2. Genre XNonton: selalu baca `category.genre` dari get_category_posts_secure
 *    (reliable), dan pakai `meta["Themes"]` sebagai fallback karena HTML baru
 *    memakai label "Theme:" bukan "Genres:".
 * 3. Dukung Animeloverz (dulu key="" -> tidak pernah di-resolve): ambil
 *    genre/status/total episode dari AnimeLoverzApi.detail(slug) (API v3).
 * 4. 2026-10-08: dukung Dramora — new-upload tidak mengirim genre (terbukti
 *    dari HAR), jadi genre di-enrich per-item dari
 *    `/api/v2/movie/detail-movie` (genre[]).
 */
object AnimeHomeV2Resolver {
    interface Callback {
        fun onResolved(post: AnimePost)
    }

    private class Info(val genre: String, val status: String, val totalEpisodes: Int, val description: String = "")

    private class Waiter(val post: AnimePost, val callback: Callback)

    private const val RETRY_MS = 120000L
    private const val ANIMEKU_API_BASE = "https://pencarinafkah.xyz/vA6//api"
    private const val ANIMEKU_API_KEY = "cda11y63tfI7rwln8BLeiKTvjsD5g2Mox01RzkhQCEXSGWbqYO"
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(3)
    private val cache = HashMap<String, Info>()
    /** Key yang fetch-nya sudah sukses (walau hasilnya kosong) -> jangan fetch ulang. */
    private val completed = HashSet<String>()
    private val failedAt = HashMap<String, Long>()
    private val pending = HashSet<String>()
    private val waiters = HashMap<String, ArrayList<Waiter>>()

    @JvmStatic
    fun key(post: AnimePost?): String {
        if (post == null) return ""
        if (post.sourceId == AnimeSettingsManager.SOURCE_DEFAULT) {
            if (post.categoryId > 0) return "default|category:" + post.categoryId
            if (post.channelId > 0) return "default|channel:" + post.channelId
            return ""
        }
        if (post.sourceId == AnimeSettingsManager.SOURCE_ANIMEKU) {
            if (post.categoryId > 0) return "animeku|category:" + post.categoryId
            return ""
        }
        if (post.sourceId == AnimeSettingsManager.SOURCE_ANIMELOVERZ) {
            val slug = AnimeLoverzApi.normalizeSlug(post.slug)
            if (slug.isNotEmpty()) return "loverz|slug:${slug.lowercase()}"
        }
        if (post.sourceId == AnimeSettingsManager.SOURCE_DRAMORA) {
            // 2026-10-08: new-upload tidak mengirim genre -> enrich per-item
            // via detail-movie (genre[]). post.slug = id movie (UUID).
            val id = post.slug?.trim()?.trim('/') ?: ""
            if (id.isNotEmpty()) return "dramora|movie:$id"
        }
        if (post.sourceId == AnimeSettingsManager.SOURCE_DRAKORKU && post.categoryId > 0) {
            return "drakorku|category:${post.categoryId}"
        }
        if (AnimeSettingsManager.normalizeSourceId(post.sourceId) == AnimeSettingsManager.SOURCE_SOKUJA) {
            val slug = post.slug?.trim()?.trim('/') ?: ""
            if (slug.isNotEmpty()) return "sokuja|slug:$slug"
        }
        return ""
    }

    @JvmStatic
    fun needsResolve(post: AnimePost?): Boolean {
        val key = key(post)
        if (key.isEmpty() || post == null) return false
        val genreBlank = post.genre.isNullOrBlank()
        val statusBlank = AnimeHomeV2Labels.statusText(post).isEmpty()
        val totalUnknown = post.totalEpisodes <= 0
        val descBlank = post.description.isNullOrBlank()
        if (!genreBlank && !statusBlank && !totalUnknown && !descBlank) return false
        synchronized(lock) {
            // Fetch sudah pernah sukses -> berhenti, jangan loop selamanya.
            if (completed.contains(key)) return false
            val failed = failedAt[key]
            if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) return false
        }
        return true
    }

    @JvmStatic
    fun applyCached(post: AnimePost?) {
        val key = key(post)
        if (key.isEmpty() || post == null) return
        val info = synchronized(lock) { cache[key] } ?: return
        apply(post, info)
    }

    @JvmStatic
    fun isPending(post: AnimePost?): Boolean {
        val key = key(post)
        if (key.isEmpty()) return false
        return synchronized(lock) { pending.contains(key) }
    }

    @JvmStatic
    fun request(post: AnimePost?, callback: Callback) {
        val key = key(post)
        if (key.isEmpty() || post == null) return
        var cached: Info? = null
        var start = false
        var settled = false
        synchronized(lock) {
            cached = cache[key]
            if (cached == null && !completed.contains(key)) {
                val failed = failedAt[key]
                if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) return
                val list = waiters.getOrPut(key) { ArrayList() }
                if (list.none { it.post === post }) list.add(Waiter(post, callback))
                start = pending.add(key)
            } else {
                settled = true
            }
        }
        val ready = cached
        if (ready != null) {
            // Cache hit: hanya beri kabar bila benar-benar ada data baru untuk post ini.
            // Ini pemutus utama infinite rebind loop.
            if (apply(post, ready)) main.post { callback.onResolved(post) }
            return
        }
        // Sudah pernah sukses tapi tak ada data baru -> diam, jangan callback.
        if (settled) return
        if (start) executor.execute { run(key, post) }
    }

    private fun run(key: String, source: AnimePost) {
        var info: Info? = null
        try {
            info = fetch(source)
        } catch (e: Exception) {
            info = null
        }
        val list: ArrayList<Waiter>
        synchronized(lock) {
            if (info != null) {
                cache[key] = info
                completed.add(key)
            } else {
                failedAt[key] = System.currentTimeMillis()
            }
            pending.remove(key)
            list = waiters.remove(key) ?: ArrayList()
        }
        val resolved = info
        main.post {
            for (waiter in list) {
                val changed = resolved != null && apply(waiter.post, resolved)
                if (changed) waiter.callback.onResolved(waiter.post)
            }
        }
    }

    private fun fetch(post: AnimePost): Info {
        return when (AnimeSettingsManager.normalizeSourceId(post.sourceId)) {
            AnimeSettingsManager.SOURCE_ANIMEKU -> fetchAnimeku(post)
            AnimeSettingsManager.SOURCE_ANIMELOVERZ -> fetchLoverz(post)
            AnimeSettingsManager.SOURCE_DRAMORA -> fetchDramora(post)
            AnimeSettingsManager.SOURCE_DRAKORKU -> fetchDrakorku(post)
            // Sokuja: listing TIDAK membawa genre/episode terbaru (katalog
            // tidak punya genre per kartu) — enrich per kartu dari detail()
            // halaman serial. Tanpa cabang ini kartu jatuh ke fetchDefault
            // (ANX) dengan categoryId hash dan menampilkan konten yang salah.
            // Dijalankan di pool 3 thread resolver dengan cache sesi +
            // pemutus loop yang sudah ada (completed/pending/retry 120 dtk),
            // jadi kartu tetap tampil dulu tanpa genre, lalu terisi menyusul.
            AnimeSettingsManager.SOURCE_SOKUJA -> fetchSokuja(post)
            else -> fetchDefault(post)
        }
    }

    private fun fetchSokuja(post: AnimePost): Info {
        val slug = post.slug?.trim()?.trim('/') ?: ""
        if (slug.isEmpty()) return Info("", "", 0)
        val scratch = AnimePost(post.imgUrl, post.categoryName, post.categoryId, post.channelId)
        scratch.sourceId = AnimeSettingsManager.SOURCE_SOKUJA
        scratch.slug = slug
        val detail = SokujaSource.detail(scratch)
        // totalEpisodes = nomor episode terbaru (mis. 1180 untuk One Piece),
        // BUKAN jumlah entri — chip "Episode terbaru" memakai angka ini.
        return Info(detail.genres.joinToString(", "), detail.post.statusVideo, detail.latestEpisodeNumber.toInt(), detail.description)
    }

    /**
     * 2026-10-08: genre Dramora untuk kartu Rilis Terbaru. Endpoint new-upload
     * tidak mengirim genre (terbukti dari HAR) -> ambil dari detail-movie.
     * Hanya genre yang diisi; status & total episode sudah ada dari new-upload.
     */
    private fun fetchDramora(post: AnimePost): Info {
        val id = post.slug?.trim()?.trim('/') ?: ""
        if (id.isEmpty()) return Info("", "", 0)
        return try {
            val result = Dramora.detail(post)
            Info(result.genres.joinToString(", "), "", 0, result.description)
        } catch (e: Exception) {
            Info(Dramora.movieGenre(id), "", 0)
        }
    }

    // Data listing Drakorku sudah mencakup genre, status, dan jumlah episode;
    // sinopsis diambil dari detail bila listing tidak membawanya.
    private fun fetchDrakorku(post: AnimePost): Info {
        val base = Info(post.genre.orEmpty(), post.statusVideo.orEmpty(), post.totalEpisodes, post.description.orEmpty())
        if (post.categoryId <= 0 || !post.description.isNullOrBlank()) return base
        return try {
            val result = Drakorku.detail(post)
            Info(
                result.genres.joinToString(", ").ifBlank { base.genre },
                result.post.statusVideo.orEmpty().ifBlank { base.status },
                if (result.post.totalEpisodes > 0) result.post.totalEpisodes else base.totalEpisodes,
                result.description.ifBlank { base.description }
            )
        } catch (e: Exception) {
            base
        }
    }

    private fun fetchAnimeku(post: AnimePost): Info {
        val fallback = Info(post.genre.orEmpty(), post.statusVideo.orEmpty(), post.totalEpisodes, post.description.orEmpty())
        if (post.categoryId <= 0) return fallback
        val url = ANIMEKU_API_BASE + "/get_anime_detail?id=" + post.categoryId + "&api_key=" + ANIMEKU_API_KEY
        val request = Request.Builder().url(url).headers(animekuHeaders()).get().build()
        val body = httpClient.newCall(request).execute().use { it.body?.string().orEmpty() }
        val json = JSONObject(body)
        if (!json.optString("status").equals("ok", true)) return fallback
        val category = json.optJSONObject("category") ?: return fallback
        val rawDesc = category.optString("desc_anime", "")
        val meta = parseAnimekuDescriptionHtml(rawDesc)
        val genre = firstUsefulAnime(category.optString("genre", ""), firstUsefulAnime(meta["Genres"], meta["Genre"]))
        val status = normalizeAnimeStatus(firstUsefulAnime(category.optString("status_video", ""), post.statusVideo))
        val total = AnimeEpisodeLabelUtils.numericValue(category.optString("video_count", "")).toInt()
        var synopsis = animekuSynopsis(rawDesc, meta)
        if (synopsis.isBlank() && post.channelId > 0) {
            try {
                val detailUrl = ANIMEKU_API_BASE + "/get_post_detail?id=" + post.channelId
                val detailRequest = Request.Builder().url(detailUrl).headers(animekuHeaders()).get().build()
                val detailBody = httpClient.newCall(detailRequest).execute().use { it.body?.string().orEmpty() }
                val item = JSONObject(detailBody).optJSONObject("post")
                if (item != null) {
                    for (field in arrayOf("video_description", "desc_anime")) {
                        val text = item.optString(field, "")
                        if (!isUsefulAnime(text)) continue
                        val found = animekuSynopsis(text, parseAnimekuDescriptionHtml(text))
                        if (found.isNotBlank()) {
                            synopsis = found
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                // Sinopsis tetap kosong; enrichment lain tidak boleh ikut gagal.
            }
        }
        return Info(genre.orEmpty(), status, if (total > 0) total else post.totalEpisodes, synopsis)
    }

    private fun animekuHeaders(): Headers = Headers.Builder()
        .add("Cache-Control", "max-age=0")
        .add("Data-Agent", "Your Videos Channel")
        .add("User-Agent", "Dalvik/7.1.12.1.0 (com.newanimeku.animechanneldonghuasubindosubenglish U; Android ; 20175 Build/NMF260)")
        .add("Accept", "application/vnd.yourapi.v1.full+json")
        .build()

    private val ANIMEKU_INFO_KEYS = setOf("alternative", "english", "japanese", "synonyms", "type", "episodes", "status", "aired", "premiered", "broadcast", "producers", "licensors", "studios", "source", "genres", "genre", "duration", "rating", "score")

    private fun isUsefulAnime(value: String?): Boolean {
        val v = value?.trim().orEmpty()
        return v.isNotBlank() && !v.equals("null", true) && v != "#" && v != "-"
    }

    private fun isAnimekuSynopsisHeading(value: String): Boolean {
        val v = value.trim().trimEnd(':', '\uFF1A').trim()
        return v == "synopsis" || v == "sinopsis" || v == "deskripsi" || v == "description" || v == "sinopsis anime" || v == "deskripsi anime" || v == "ringkasan" || v == "plot" || v == "storyline"
    }

    private fun cleanAnimekuHtml(value: String?): String {
        val marker = "@@NL@@"
        val normalized = value.orEmpty().replace(Regex("(?i)<br\\s*/?>"), marker).replace(Regex("(?i)</p>"), marker)
        val text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(normalized, Html.FROM_HTML_MODE_LEGACY).toString()
        } else {
            @Suppress("DEPRECATION") Html.fromHtml(normalized).toString()
        }
        return text.replace(marker, "\n").trim()
    }

    /** Port dari AnimeDetailV2Fragment.parseAnimekuDescriptionHtml (parser detail v2 untuk desc_anime). */
    private fun parseAnimekuDescriptionHtml(html: String?): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        if (!isUsefulAnime(html)) return result
        val lines = cleanAnimekuHtml(html).replace('\u00A0', ' ').split(Regex("\\r?\\n+"))
        var synopsisMode = false
        val synopsis = StringBuilder()
        val orphanLines = ArrayList<String>()
        for (raw in lines) {
            val line = raw.trim()
            if (!isUsefulAnime(line)) continue
            val lower = line.lowercase(Locale.ROOT)
            if (isAnimekuSynopsisHeading(lower)) {
                synopsisMode = true
                continue
            }
            if (lower == "alternative titles" || lower == "information" || lower == "informasi") {
                synopsisMode = false
                continue
            }
            val colon = line.indexOf(':')
            if (!synopsisMode && colon > 0 && isAnimekuSynopsisHeading(line.substring(0, colon).lowercase(Locale.ROOT))) {
                val inlineText = line.substring(colon + 1).trim()
                synopsisMode = true
                if (isUsefulAnime(inlineText)) {
                    if (synopsis.isNotEmpty()) synopsis.append('\n')
                    synopsis.append(inlineText)
                }
                continue
            }
            if (!synopsisMode) {
                val lineKey = line.substringBefore(':').trim().lowercase(Locale.ROOT)
                val isKeyed = colon > 0 && lineKey in ANIMEKU_INFO_KEYS
                if (!isKeyed && ((colon <= 0 && line.length >= 30) || (colon > 0 && line.length >= 60))) {
                    orphanLines.add(line)
                    continue
                }
            }
            if (!synopsisMode && colon > 0) {
                val k = line.substring(0, colon).trim()
                val v = line.substring(colon + 1).trim()
                if (isUsefulAnime(k) && isUsefulAnime(v)) result[k] = v
            }
            if (synopsisMode) {
                if (synopsis.isNotEmpty()) synopsis.append('\n')
                synopsis.append(line)
            }
        }
        if (synopsis.isEmpty() && orphanLines.isNotEmpty()) synopsis.append(orphanLines.joinToString("\n"))
        if (synopsis.isNotEmpty()) result["Synopsis"] = synopsis.toString().trim()
        return result
    }

    private fun looksLikeAnimekuInfoOnly(value: String): Boolean {
        val lines = value.split(Regex("\\r?\\n+|\\s{2,}")).map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return false
        var keyed = 0
        for (line in lines) {
            val key = line.substringBefore(':').trim().lowercase(Locale.ROOT)
            if (line.contains(':') && key in ANIMEKU_INFO_KEYS) keyed++
        }
        return keyed >= 3 || (keyed > 0 && keyed == lines.size)
    }

    private fun animekuSynopsis(raw: String?, meta: Map<String, String>): String {
        val synopsis = meta["Synopsis"].orEmpty().trim()
        if (synopsis.isNotBlank()) return synopsis
        val clean = cleanAnimekuHtml(raw).replace('\u00A0', ' ').trim()
        if (!isUsefulAnime(clean)) return ""
        if (looksLikeAnimekuInfoOnly(clean)) return ""
        return clean
    }

    private fun fetchDefault(post: AnimePost): Info {
        val scratch = AnimePost(post.imgUrl, post.categoryName, post.categoryId, post.channelId)
        scratch.sourceId = AnimeSettingsManager.SOURCE_DEFAULT
        scratch.genre = post.genre.orEmpty()
        scratch.statusVideo = post.statusVideo.orEmpty()
        scratch.rating = post.rating.orEmpty()
        scratch.year = post.year
        scratch.episodeCount = post.episodeCount.orEmpty()
        scratch.countView = post.countView.orEmpty()
        var channelId = post.channelId
        var total = 0
        // Kategori selalu diambil bila ada categoryId: sumber genre/rating/tahun
        // yang reliable + jumlah posts = total episode.
        if (post.categoryId > 0) {
            try {
                val body = postForm(DEFAULT_CATEGORY_URL, mapOf("id" to post.categoryId.toString(), "isAPKvalid" to "true"), defaultHeaders())
                val json = JSONObject(body)
                val category = json.optJSONObject("category")
                if (category != null) {
                    scratch.genre = firstUsefulAnime(category.optString("genre", ""), scratch.genre)
                    scratch.rating = firstUsefulAnime(category.optString("rating", ""), scratch.rating)
                    if (scratch.year <= 0) scratch.year = category.optInt("years", 0)
                }
                val posts = json.optJSONArray("posts")
                if (posts != null) {
                    if (posts.length() > 0) total = posts.length()
                    if (channelId <= 0) {
                        for (i in 0 until posts.length()) {
                            val id = posts.optJSONObject(i)?.optInt("channel_id", -1) ?: -1
                            if (id > 0) {
                                channelId = id
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // lanjut ke fallback deskripsi
            }
        }
        var meta: Map<String, String> = emptyMap()
        if (channelId > 0 && (scratch.genre.isNullOrBlank() || scratch.statusVideo.isNullOrBlank() || post.description.isNullOrBlank())) {
            try {
                meta = loadDefaultDescription(channelId, scratch)
            } catch (e: Exception) {
                meta = emptyMap()
            }
        }
        // Fallback genre: HTML baru memakai label "Theme:" (key "Themes").
        if (scratch.genre.isNullOrBlank()) {
            scratch.genre = firstUsefulAnime(scratch.genre, meta["Themes"].orEmpty())
        }
        // Total episode: meta "Episodes" (paling akurat) -> jumlah posts kategori.
        var totalEpisodes = total
        val metaNumber = AnimeEpisodeLabelUtils.numericValue(meta["Episodes"].orEmpty())
        if (metaNumber > 0) totalEpisodes = metaNumber.toInt()
        return Info(scratch.genre.orEmpty().trim(), normalizeAnimeStatus(scratch.statusVideo), totalEpisodes, meta["Synopsis"].orEmpty())
    }

    private fun fetchLoverz(post: AnimePost): Info {
        val slug = AnimeLoverzApi.normalizeSlug(post.slug)
        if (slug.isEmpty()) return Info("", "", 0)
        val detail = AnimeLoverzApi.detail(slug)
        val total = if (detail.episodes.isNotEmpty()) detail.episodes.size else detail.post.totalEpisodes
        return Info(detail.post.genre.orEmpty(), normalizeAnimeStatus(detail.post.statusVideo), total, detail.post.description.orEmpty())
    }

    /**
     * Terapkan info ke post. Return true bila ada data yang benar-benar berubah;
     * dipakai untuk memutus infinite rebind loop (callback hanya bila berubah).
     */
    private fun apply(post: AnimePost, info: Info): Boolean {
        var changed = false
        if (post.genre.isNullOrBlank() && info.genre.isNotBlank()) {
            post.genre = info.genre
            changed = true
        }
        if (post.statusVideo.isNullOrBlank() && info.status.isNotBlank()) {
            post.statusVideo = info.status
            post.ongoing = !info.status.equals("Completed", true)
            changed = true
        }
        if (post.totalEpisodes <= 0 && info.totalEpisodes > 0) {
            post.totalEpisodes = info.totalEpisodes
            changed = true
        }
        if (post.description.isNullOrBlank() && info.description.isNotBlank()) {
            post.description = info.description
            changed = true
        }
        return changed
    }
}
