package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.wenku8.book

import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.wenku8.Wenku8Api
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.flow.Flow

class BookRequestDispatcher(
    wenku8Api: Wenku8Api
): Wenku8BookDataSource {
    private val source = Wenku8ApiDataSource(wenku8Api)

    override suspend fun getBookInformation(id: String) = source.getBookInformation(id)

    override suspend fun getBookVolumes(id: String) = source.getBookVolumes(id)

    override suspend fun getChapterContent(
        chapterId: String,
        bookId: String
    ) = source.getChapterContent(chapterId, bookId)

    override fun search(searchType: String, keyword: String): Flow<SearchResult> {
        return source.search(searchType, keyword)
    }
}