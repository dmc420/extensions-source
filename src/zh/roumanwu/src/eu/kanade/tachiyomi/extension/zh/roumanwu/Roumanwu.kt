package eu.kanade.tachiyomi.extension.zh.roumanwu

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferences
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class Roumanwu :
    HttpSource(),
    ConfigurableSource {

    override val supportsLatest = true

    private val preferences = getPreferences()

    // 优先使用用户自定义的域名，否则回落到选中的镜像域名
    override val baseUrl: String
        get() {
            val custom = preferences.getString(PREF_CUSTOM_URL, "")!!.trim()
            if (custom.isNotBlank()) {
                return custom.removeSuffix("/")
            }
            return preferences.getString(PREF_MIRROR, MIRRORS[0])!!.removeSuffix("/")
        }

    override val client = network.client.newBuilder()
        .addInterceptor(ScrambledImageInterceptor())
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", MOBILE_UA)
                .build()
            chain.proceed(request)
        }
        .build()

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/home", headers)

    private fun parseEntries(container: Element): List<SManga> = container.select("a[href*=/books/]").mapNotNull {
        // 标题：优先 h3，回退到封面图 alt（兼容站点改版前后结构）
        val title = it.selectFirst("h3")?.text()
            ?: it.selectFirst("div.truncate")?.text()
            ?: it.selectFirst("img")?.attr("alt")
            ?: return@mapNotNull null
        SManga.create().apply {
            this.title = title
            url = it.attr("href")
            thumbnail_url = it.selectFirst("img")?.absUrl("src")
                ?: it.selectFirst("div.bg-cover")?.attr("style")
                    ?.substringAfter("background-image:url(\"")
                    ?.substringBefore("\")")
        }
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        return parseHomePage(document, Regex("正熱門|今日最佳|本週熱門"))
    }

    private fun parseHomePage(document: Document, sections: Regex): MangasPage {
        val container = document.selectFirst("div.px-1") ?: return MangasPage(emptyList(), false)
        val entries = ArrayList<SManga>()
        // 按 site-section-heading 划分区块：标题在 heading 内的 h1/h2，卡片在 heading 之后的同级节点里
        val headings = container.select("div.site-section-heading")
        if (headings.isNotEmpty()) {
            for (heading in headings) {
                val title = heading.selectFirst("h1, h2")?.text()?.trim() ?: continue
                if (!title.contains(sections)) continue
                // heading 之后的兄弟节点直到下一个 heading 都属于此区块
                var node: Element? = heading.nextElementSibling()
                while (node != null && node.selectFirst("div.site-section-heading") == null) {
                    entries += parseEntries(node)
                    node = node.nextElementSibling()
                }
            }
        } else {
            // 旧版结构：直接遍历 section 子节点
            var currentHeading = ""
            for (child in container.children()) {
                val title = child.selectFirst("h1, h2")?.text()?.trim()
                if (title != null) {
                    currentHeading = title
                    continue
                }
                if (currentHeading.isEmpty()) currentHeading = child.child(0).text()
                if (currentHeading.contains(sections)) {
                    entries += parseEntries(child)
                }
            }
        }
        return MangasPage(entries.distinctBy { it.url }, false)
    }

    override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        return parseHomePage(document, Regex("最近更新"))
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = if (query.isNotBlank()) {
        GET("$baseUrl/search?term=$query&page=${page - 1}", headers)
    } else {
        val parts = filters.filterIsInstance<UriPartFilter>().joinToString("") { it.toUriPart() }
        GET("$baseUrl/books?page=${page - 1}$parts", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val entries = parseEntries(document)
        val hasNextPage = document.selectFirst("div.justify-end > a:contains(下一頁)") != null
        return MangasPage(entries, hasNextPage)
    }

    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val document = response.asJsoup()

        val thumbnail = document.selectFirst("div.basis-2\\/5 img")
        thumbnail_url = thumbnail?.absUrl("src")?.run { toHttpUrl().queryParameter("url") ?: this }

        val descriptionEl = document.selectFirst("p:contains(簡介:)")
        description = descriptionEl?.text()?.removePrefix("簡介:")?.trim()

        val infobox = parseInfobox(document).iterator()
        if (infobox.hasNext()) title = infobox.next()

        val genres = ArrayList<String>()
        for (text in infobox) {
            val value = text.drop(3).trimStart()
            if (value.isEmpty()) continue
            when (text.take(3)) {
                "別名:" -> if (value != title) description = "$text\n\n$description"

                "作者:" -> author = value

                "狀態:" -> status = when (value) {
                    "連載中" -> SManga.ONGOING
                    "已完結" -> SManga.COMPLETED
                    else -> SManga.UNKNOWN
                }

                "地區:" -> genres.add(value)

                "標籤:" -> genres.addAll(value.split(","))
            }
        }
        genre = genres.joinToString()
    }

    private fun parseInfobox(document: Document): List<String> {
        val infobox = document.selectFirst("div.basis-3\\/5")?.children() ?: return emptyList()
        return infobox.map { it.text() }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = document.select("a[href~=/books/.*/\\d+]").map {
            SChapter.create().apply {
                url = it.attr("href")
                name = it.text()
            }
        }.asReversed()
        if (chapters.isNotEmpty()) {
            for (text in parseInfobox(document).asReversed()) {
                val date = DATE_FORMAT.tryParse(text)
                if (date != 0L) {
                    chapters[0].date_upload = date
                    break
                }
            }
        }
        return chapters
    }

    override fun pageListRequest(chapter: SChapter): Request {
        // Rendered HTML might have links sitting on the boundary of two scripts
        return super.pageListRequest(chapter).newBuilder().addHeader("rsc", "1").build()
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body.string()
        return IMAGE_URL_REGEX.findAll(html).mapIndexedTo(ArrayList()) { index, match ->
            Page(index, imageUrl = match.groupValues[1])
        }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_MIRROR
            title = "镜像域名"
            entries = MIRRORS
            entryValues = MIRRORS
            setDefaultValue(MIRRORS[0])
            summary = "当未填写自定义域名时使用的镜像"
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_CUSTOM_URL
            title = "自定义域名"
            summary = "留空则使用上方镜像；填写后优先级最高（如 https://example.com）"
            setDefaultValue("")
        }.let(screen::addPreference)
    }

    override fun getFilterList() = FilterList(
        Filter.Header("提示：搜尋時篩選無效"),
        StatusFilter(),
    )

    private abstract class UriPartFilter(name: String, values: Array<String>) : Filter.Select<String>(name, values) {
        abstract fun toUriPart(): String
    }

    private class StatusFilter : UriPartFilter("狀態", arrayOf("全部", "連載中", "已完結")) {
        override fun toUriPart() = when (state) {
            1 -> "&continued=true"
            2 -> "&continued=false"
            else -> ""
        }
    }

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("M/d/yyyy", Locale.ROOT)
        private val IMAGE_URL_REGEX = Regex(""""imageUrl":"([^"]+)""")

        private const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private const val PREF_MIRROR = "pref_mirror"
        private const val PREF_CUSTOM_URL = "pref_custom_url"
        private val MIRRORS = arrayOf(
            "https://rouman5.com",
            "https://roum27.xyz",
        )
    }
}
