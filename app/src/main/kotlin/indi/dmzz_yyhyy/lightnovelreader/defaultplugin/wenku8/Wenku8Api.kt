package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.wenku8

import android.content.Context
import android.net.Uri
import androidx.navigation.NavController
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.wenku8.book.BookRequestDispatcher
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.wenku8.explore.Wenku8ExplorePageProvider
import indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.expanded.navigateToExploreExpandDestination
import indi.dmzz_yyhyy.lightnovelreader.utils.ImageUtils
import indi.dmzz_yyhyy.lightnovelreader.utils.ofId
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.util.Cache
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSource
import io.nightfish.lightnovelreader.api.web.explore.ExplorePageProvider
import io.nightfish.lightnovelreader.api.web.search.SearchProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.select.Elements
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.milliseconds

@WebDataSource("Wenku8", "LightNovelReader from wenku8.net")
class Wenku8Api(
    /**
     * The relay's dynamic appver is intentionally not embedded in the public project.
     * Supply the value produced by the official signer in the application build.
     */
    private val appver: String = ""
) : WebBookDataSource {
    companion object {
        const val API_ENDPOINT = "https://wenku8-relay.mewx.org/"
        const val API_VERSION = "1.30"
        const val IMAGE_ENDPOINT = "https://img.wenku8.com"
        val DOWNLOAD_ENDPOINTS = listOf(
            "https://dl1.wenku8.com",
            "https://dl2.wenku8.com"
        )
        private const val API_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; MewX-Wenku8/1.30.73) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0"
    }

    val ktorClient = HttpClient(OkHttp) {
        install(UserAgent) { agent = API_USER_AGENT }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
    }

    private val hosts = listOf("https://www.wenku8.net")
    var host = hosts.first()
    private val bookRequestDispatcher = BookRequestDispatcher(this)
    private val offlineState = MutableStateFlow(false)
    private val limiter = Semaphore(3)
    private val scope = CoroutineScope(Dispatchers.IO)

    override val cache = Cache(timeout = 2 * 60 * 60 * 1000)
    override val permits = 5
    override val id = "Wenku8".ofId()
    override var offLine: Boolean = true
    override val isOffLineFlow = offlineState

    override fun onLoad() {
        scope.launch {
            while (currentCoroutineContext().isActive) {
                offLine = isOffLine()
                offlineState.emit(offLine)
                delay((if (offLine) 3_000 else 100_000).milliseconds)
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    internal suspend fun apiPost(command: String): Result<ByteArray, WebRequestError> =
        withContext(Dispatchers.IO) {
            limiter.withPermit {
                if (appver.isBlank()) {
                    return@withPermit Err(
                        WebRequestError("API 配置错误", "未提供 Wenku8 relay appver")
                    )
                }
                kotlin.runCatching {
                    val body = "&appver=$appver" +
                        "&request=${Base64.encode(command.toByteArray(StandardCharsets.UTF_8))}" +
                        "&timetoken=${System.currentTimeMillis()}"
                    val response = ktorClient.post(API_ENDPOINT) {
                        contentType(ContentType.Application.FormUrlEncoded)
                        headers {
                            append(HttpHeaders.Accept, "text/xml, application/xml, text/plain, */*")
                            append(HttpHeaders.AcceptEncoding, "gzip")
                        }
                        setBody(body)
                    }
                    val bytes = response.bodyAsBytes()
                    if (!response.status.isSuccess()) {
                        throw IllegalStateException("Wenku8 API HTTP ${response.status.value}")
                    }
                    bytes
                }.fold(
                    onSuccess = { Ok(it) },
                    onFailure = {
                        Err(WebRequestError("网络请求失败", it.message ?: "Wenku8 API request failed"))
                    }
                )
            }
        }

    internal suspend fun cdnGet(url: String): Result<ByteArray, WebRequestError> =
        kotlin.runCatching {
            val response = ktorClient.request(url) {
                method = HttpMethod.Get
                headers { append(HttpHeaders.Accept, "*/*") }
            }
            val bytes = response.bodyAsBytes()
            if (!response.status.isSuccess()) {
                throw IllegalStateException("Wenku8 CDN HTTP ${response.status.value}")
            }
            bytes
        }.fold(
            onSuccess = { Ok(it) },
            onFailure = {
                Err(WebRequestError("CDN 请求失败", it.message ?: "Wenku8 CDN request failed"))
            }
        )

    fun coverUrl(bookId: String): String =
        "$IMAGE_ENDPOINT/image/${bookId.toInt() / 1000}/$bookId/${bookId}s.jpg"

    fun pictureUrl(url: String): String = url

    suspend fun getFullNovelContent(bookId: String): Result<String, WebRequestError> {
        var lastError: WebRequestError? = null
        for (endpoint in DOWNLOAD_ENDPOINTS) {
            val result = cdnGet("$endpoint/txtutf8/${bookId.toInt() / 1000}/$bookId.txt")
            val bytes = result.component1()
            if (bytes != null) return Ok(String(bytes, StandardCharsets.UTF_8))
            lastError = result.component2()
        }
        return Err(lastError ?: WebRequestError("CDN 请求失败", "无法下载全本"))
    }

    override suspend fun isOffLine(): Boolean = kotlin.runCatching {
        ktorClient.post(API_ENDPOINT) { setBody("") }.status.isSuccess()
    }.getOrDefault(false).not()

    override suspend fun getBookInformation(id: String) = bookRequestDispatcher.getBookInformation(id)
    override suspend fun getBookVolumes(id: String) = bookRequestDispatcher.getBookVolumes(id)
    override suspend fun getChapterContent(chapterId: String, bookId: String) =
        bookRequestDispatcher.getChapterContent(chapterId, bookId)

    override val searchProvider: SearchProvider = Wenku8SearchProvider(bookRequestDispatcher)
    override val explorePageProvider: ExplorePageProvider = Wenku8ExplorePageProvider(host, this)

    override fun progressBookTagClick(tag: String, navController: NavController) {
        navController.navigateToExploreExpandDestination(tag)
    }

    /**
     * Kept for the legacy explore UI, which still renders the site's non-book menus.
     * Book/detail/chapter requests never use this path.
     */
    suspend fun getWithWenku8Cookie(url: String): Result<Document, Throwable> =
        kotlin.runCatching {
            Jsoup.parse(
                String(
                    ktorClient.request(url) {
                        method = HttpMethod.Get
                    }.bodyAsBytes(),
                    Charset.forName("GB18030")
                )
            )
        }.fold({ Ok(it) }, { Err(it) })

    fun getBookInformationListFromBookCards(
        elements: Elements
    ): List<Pair<String, Result<BookInformation, WebRequestError>>> = elements.mapNotNull { element ->
        val link = element.selectFirst("a[href*=/book/]") ?: return@mapNotNull null
        val id = link.attr("href").substringAfter("/book/").substringBefore(".htm")
        if (id.isBlank()) return@mapNotNull null
        val title = link.attr("title").ifBlank { link.text() }
        id to Ok(
            BookInformation(
                id = id,
                title = title,
                author = "",
                description = "",
                publishingHouse = "",
                wordCount = io.nightfish.lightnovelreader.api.book.WordCount(-1),
                lastUpdated = java.time.LocalDateTime.MIN,
                isComplete = false,
                coverUri = link.selectFirst("img")?.attr("src")?.let(Uri::parse) ?: Uri.EMPTY
            )
        )
    }

    override suspend fun getCoverUriInVolume(
        bookId: String,
        volume: Volume,
        volumeChapterContentMap: MutableMap<String, ChapterContent>,
        context: Context
    ): Uri? {
        val chapter = volume.chapters.find { it.title.endsWith("插图") } ?: return null
        val uris = volumeChapterContentMap[chapter.id]?.content?.get("components")?.jsonArray
            ?.mapNotNull { it.jsonObject["data"]?.jsonObject?.get("uri")?.jsonPrimitive?.content }
            ?.map(Uri::parse)
            ?: return null
        for (uri in uris) {
            if (ImageUtils.uriToBitmap(uri, context).component1()?.let {
                    it.height > it.width
                } == true) {
                return uri
            }
        }
        return null
    }

    suspend fun anyTrue(tasks: List<suspend () -> Boolean>): Boolean = coroutineScope {
        val deferred = tasks.map { async { it() } }.toMutableList()
        try {
            while (deferred.isNotEmpty()) {
                val (finished, value) = select {
                    deferred.forEach { task -> task.onAwait { task to it } }
                }
                deferred.remove(finished)
                if (value) {
                    deferred.forEach { it.cancel() }
                    return@coroutineScope true
                }
            }
            false
        } finally {
            deferred.forEach { it.cancel() }
        }
    }
}