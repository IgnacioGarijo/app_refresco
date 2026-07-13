package es.personal.avisosairef.data.network

import android.content.Context
import es.personal.avisosairef.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

interface AirefFetcher {
    suspend fun fetch(url: String, eTag: String?, lastModified: String?): FetchResult
}

class AirefHttpClient private constructor(
    private val defaultClient: OkHttpClient,
    private val airefClient: OkHttpClient?
) : AirefFetcher {
    constructor(context: Context) : this(buildBaseClient(), buildAirefClient(context.applicationContext))

    constructor() : this(buildBaseClient(), null)

    override suspend fun fetch(url: String, eTag: String?, lastModified: String?): FetchResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "RefrescoWeb/1.0 (Android personal web monitor)")
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Encoding", "gzip")
            .apply {
                eTag?.let { header("If-None-Match", it) }
                lastModified?.let { header("If-Modified-Since", it) }
            }
            .build()

        try {
            clientFor(url).newCall(request).execute().use { response ->
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

    private fun clientFor(url: String): OkHttpClient {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return if ((host == "airef.es" || host.endsWith(".airef.es")) && airefClient != null) {
            airefClient
        } else {
            defaultClient
        }
    }

    private companion object {
        const val MAX_BYTES = 1_500_000L

        fun buildBaseClient(): OkHttpClient =
            OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build()

        fun buildAirefClient(context: Context): OkHttpClient {
            val trustManager = CompositeTrustManager(
                listOf(systemTrustManager(), fnmtTrustManager(context))
            )
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustManager), null)
            }
            return buildBaseClient().newBuilder()
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .build()
        }

        fun systemTrustManager(): X509TrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
        }

        fun fnmtTrustManager(context: Context): X509TrustManager {
            val certificateFactory = CertificateFactory.getInstance("X.509")
            val certificate = context.resources.openRawResource(R.raw.fnmt_ac_componentes_informaticos).use {
                certificateFactory.generateCertificate(it)
            }
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("fnmt_ac_componentes_informaticos", certificate)
            }
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(keyStore)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
        }
    }
}

private class CompositeTrustManager(
    private val delegates: List<X509TrustManager>
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        delegates.first().checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val failures = mutableListOf<Throwable>()
        for (delegate in delegates) {
            try {
                delegate.checkServerTrusted(chain, authType)
                return
            } catch (ex: Throwable) {
                failures += ex
            }
        }
        throw failures.lastOrNull() ?: SSLHandshakeException("No trust manager accepted the server certificate")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        delegates.flatMap { it.acceptedIssuers.asIterable() }.toTypedArray()
}
