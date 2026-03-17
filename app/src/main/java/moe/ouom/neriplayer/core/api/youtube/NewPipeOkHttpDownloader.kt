package moe.ouom.neriplayer.core.api.youtube

import java.io.IOException
import moe.ouom.neriplayer.data.appendYouTubeConsentCookie
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response

class NewPipeOkHttpDownloader(
    private val client: OkHttpClient
) : Downloader() {

    override fun execute(request: Request): Response {
        val method = request.httpMethod().uppercase()
        val builder = okhttp3.Request.Builder()
            .url(request.url())

        request.headers().forEach { (name, values) ->
            values.forEach { value ->
                builder.addHeader(name, value)
            }
        }

        if (builder.build().header("Cookie").isNullOrBlank() &&
            request.url().contains("youtube", ignoreCase = true)
        ) {
            builder.header("Cookie", appendYouTubeConsentCookie(""))
        }

        when (method) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "POST" -> {
                val contentType = builder.build().header("Content-Type")
                    ?.toMediaTypeOrNull()
                    ?: "application/json".toMediaTypeOrNull()
                val body = (request.dataToSend() ?: ByteArray(0)).toRequestBody(contentType)
                builder.post(body)
            }
            else -> throw IOException("Unsupported NewPipe request method: $method")
        }

        client.newCall(builder.build()).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            val headers = linkedMapOf<String, List<String>>().apply {
                response.headers.names().forEach { name ->
                    put(name, response.headers.values(name))
                }
            }
            return Response(
                response.code,
                response.message,
                headers,
                responseBody,
                response.request.url.toString()
            )
        }
    }
}
