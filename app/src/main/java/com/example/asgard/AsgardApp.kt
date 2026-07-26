package com.example.asgard

import android.app.Application
import okhttp3.OkHttpClient
import okhttp3.Request as OkHttpRequest
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.Localization
import java.io.IOException

class AsgardApp : Application() {
    
    // Global ViewModel to persist queue across activity restarts
    val downloaderViewModel: DownloaderViewModel by lazy {
        DownloaderViewModel()
    }

    override fun onCreate() {
        super.onCreate()
        
        val client = OkHttpClient.Builder()
            .followRedirects(true)
            .build()

        val downloader = object : Downloader() {
            @Throws(IOException::class, InterruptedException::class)
            override fun execute(request: Request): Response {
                val method = request.httpMethod()
                val url = request.url()
                val data = request.dataToSend()
                
                val body = if (data != null) {
                    val contentType = request.headers()["Content-Type"]?.firstOrNull() ?: "application/octet-stream"
                    data.toRequestBody(contentType.toMediaTypeOrNull())
                } else if (method == "POST") {
                    "".toRequestBody()
                } else {
                    null
                }

                val okHttpRequest = OkHttpRequest.Builder()
                    .url(url)
                    .method(method, body)
                    .apply {
                        request.headers().forEach { (name, values) ->
                            values.forEach { value -> addHeader(name, value) }
                        }
                    }
                    .build()

                val okHttpResponse = client.newCall(okHttpRequest).execute()

                return Response(
                    okHttpResponse.code,
                    okHttpResponse.message,
                    okHttpResponse.headers.toMultimap(),
                    okHttpResponse.body?.string(),
                    okHttpResponse.request.url.toString()
                )
            }
        }

        NewPipe.init(downloader, Localization.DEFAULT)
    }
}
