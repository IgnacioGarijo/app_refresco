package es.personal.avisosairef.notifications

import es.personal.avisosairef.data.parser.Publicacion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class TelegramNotifier(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    suspend fun send(botToken: String, chatId: String, monitorName: String, publications: List<Publicacion>) {
        if (botToken.isBlank() || chatId.isBlank() || publications.isEmpty()) return
        val text = buildString {
            appendLine("Refresco Web: cambio en $monitorName")
            appendLine()
            publications.take(8).forEach { publication ->
                appendLine("- ${publication.title}")
                appendLine(publication.url)
            }
            if (publications.size > 8) appendLine("... y ${publications.size - 8} enlaces mas.")
        }.take(3900)

        val body = FormBody.Builder()
            .add("chat_id", chatId)
            .add("text", text)
            .disableWebPagePreview()
            .build()
        val request = Request.Builder()
            .url("https://api.telegram.org/bot$botToken/sendMessage")
            .post(body)
            .build()

        withContext(Dispatchers.IO) {
            runCatching { client.newCall(request).execute().close() }
        }
    }

    private fun FormBody.Builder.disableWebPagePreview(): FormBody.Builder =
        add("disable_web_page_preview", "true")
}
