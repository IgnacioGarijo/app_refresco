package es.personal.avisosairef.data.network

import es.personal.avisosairef.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

interface AirefFetcher {
    suspend fun fetch(url: String, eTag: String?, lastModified: String?): FetchResult
}

class AirefHttpClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
) : AirefFetcher {
    override suspend fun fetch(url: String, eTag: String?, lastModified: String?): FetchResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "AvisosAIReF/1.0 (Android personal monitor; +https://www.airef.es)")
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Encoding", "gzip")
            .apply {
                eTag?.let { header("If-None-Match", it) }
                lastModified?.let { header("If-Modified-Since", it) }
            }
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val newETag = response.header("ETag") ?: eTag
                val newLastModified = response.header("Last-Modified") ?: lastModified
                if (response.code == 304) return@withContext FetchResult.NotModified(newETag, newLastModified)
                if (!response.isSuccessful) return@withContext FetchResult.Failure(FetchError.Http(response.code))
                val body = response.body
                val contentLength = body.contentLength()
                if (contentLength > MAX_BYTES) return@withContext FetchResult.Failure(FetchError.TooLarge(MAX_BYTES))
                val bytes = body.bytes()
                if (bytes.size > MAX_BYTES) return@withContext FetchResult.Failure(FetchError.TooLarge(MAX_BYTES))
                FetchResult.Success(bytes.toString(Charsets.UTF_8), newETag, newLastModified)
            }
        } catch (ex: SSLHandshakeException) {
            FetchResult.Failure(FetchError.Tls(ex.cause?.message ?: ex.message ?: "Error de certificado TLS"))
        } catch (ex: IOException) {
            FetchResult.Failure(FetchError.Network(ex.message ?: "Error de red"))
        }
    }

    private companion object {
        const val MAX_BYTES = 1_500_000L
    }
}
