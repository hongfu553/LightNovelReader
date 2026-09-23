package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.wenku8.book

import android.net.Uri
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.get
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.wenku8.Wenku8Api
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.content.builder.ContentBuilder
import io.nightfish.lightnovelreader.api.content.builder.image
import io.nightfish.lightnovelreader.api.content.builder.simpleText
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class Wenku8ApiDataSource(private val api: Wenku8Api) : Wenku8BookDataSource {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private fun text(bytes: ByteArray) = String(bytes, StandardCharsets.UTF_8)
    private fun data(document: org.jsoup.nodes.Document, name: String): String =
        document.select("data[name=$name]").firstOrNull()?.attr("value")
            ?.ifBlank { document.select("data[name=$name]").text() } ?: ""

    override suspend fun getBookInformation(id: String): Result<BookInformation, WebRequestError> =
        coroutineBinding {
            val document = Jsoup.parse(
                text(api.apiPost("action=book&do=meta&aid=$id&t=0").bind())
            )
            val date = runCatching { LocalDate.parse(data(document, "LastUpdate"), formatter).atStartOfDay() }
                .getOrDefault(LocalDateTime.MIN)
            BookInformation(
                id = id,
                title = data(document, "Title"),
                author = data(document, "Author"),
                description = text(api.apiPost("action=book&do=intro&aid=$id&t=0").bind()).trim(),
                tags = data(document, "Tags").split(" ").filter(String::isNotBlank),
                publishingHouse = data(document, "PressId"),
                wordCount = WordCount(data(document, "BookLength").toIntOrNull() ?: -1),
                lastUpdated = date,
                isComplete = data(document, "BookStatus").contains("完结"),
                coverUri = Uri.parse(api.coverUrl(id))
            )
        }

    override suspend fun getBookVolumes(id: String): Result<BookVolumes, WebRequestError> =
        coroutineBinding {
            val document = Jsoup.parse(text(api.apiPost("action=book&do=list&aid=$id&t=0").bind()))
            val volumes = document.select("volume").map { volume ->
                Volume(
                    volume.attr("vid"),
                    volume.text().trim(),
                    volume.select("chapter").map { ChapterInformation(it.attr("cid"), it.text().trim()) }
                )
            }
            if (volumes.isEmpty()) Err(WebRequestError("解析错误", "目录为空")).bind()
            BookVolumes(id, volumes)
        }

    override suspend fun getChapterContent(
        chapterId: String,
        bookId: String
    ): Result<ChapterContent, WebRequestError> = coroutineBinding {
        val raw = text(api.apiPost("action=book&do=text&aid=$bookId&cid=$chapterId&t=0").bind())
        val lines = raw.lines()
        val title = lines.firstOrNull { it.isNotBlank() && !it.startsWith("<!--image-->") }?.trim()
            ?: "章节 $chapterId"
        val builder = ContentBuilder()
        lines.dropWhile { it.trim() != title }.drop(1).forEach { line ->
            if (line.startsWith("<!--image-->") && line.endsWith("<!--image-->")) {
                builder.image(Uri.parse(line.removePrefix("<!--image-->").removeSuffix("<!--image-->")))
            } else if (line.isNotBlank()) {
                builder.simpleText(line)
            }
        }
        ChapterContent(chapterId, title, builder.build())
    }

    override fun search(searchType: String, keyword: String): Flow<SearchResult> = flow {
        val result = api.apiPost(
            "action=search&searchtype=$searchType&searchkey=${java.net.URLEncoder.encode(keyword, "UTF-8")}&t=0"
        )
        val bytes = result.get()
        if (bytes == null) {
            emit(SearchResult.Error("Wenku8 API search failed"))
            return@flow
        }
        val body = text(bytes).trim()
        val ids = if (body.startsWith("{")) {
            runCatching {
                Json.parseToJsonElement(body).jsonObject["items"]?.jsonArray
                    ?.mapNotNull { it.jsonObject["aid"]?.jsonPrimitive?.content?.toIntOrNull() }
                    ?: emptyList()
            }.getOrDefault(emptyList())
        } else {
            Jsoup.parse(body).select("item[aid]").mapNotNull { it.attr("aid").toIntOrNull() }
        }
        if (ids.isEmpty()) emit(SearchResult.Empty())
        else ids.forEach { emit(SearchResult.MultipleBook(it.toString())) }
        emit(SearchResult.End())
    }
}
