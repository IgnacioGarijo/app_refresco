package es.personal.avisosairef.data.network

sealed interface FetchResult {
    data class NotModified(val eTag: String?, val lastModified: String?) : FetchResult
    data class Success(
        val html: String,
        val eTag: String?,
        val lastModified: String?,
        val finalUrl: String? = null,
        val bytesRead: Int = -1
    ) : FetchResult
    data class Failure(val error: FetchError) : FetchResult
}

sealed interface FetchError {
    data class Network(val message: String) : FetchError
    data class Tls(val message: String) : FetchError
    data class Http(val code: Int) : FetchError
    data class TooLarge(val maxBytes: Long) : FetchError
}
