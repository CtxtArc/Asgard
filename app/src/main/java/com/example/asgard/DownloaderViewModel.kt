package com.example.asgard

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import androidx.documentfile.provider.DocumentFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.UUID
import okhttp3.OkHttpClient
import okhttp3.Request as OkHttpRequest
import java.util.concurrent.atomic.AtomicLong

@Serializable
data class StreamRepresentation(
    val content: String,
    val format: String,
    val quality: String,
    val isVideo: Boolean
)

@Serializable
data class DownloadTask(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val isMp3: Boolean,
    val title: String = "",
    val fileName: String = "",
    val status: String = "Pending",
    val progress: Float = 0f,
    val downloadId: Long? = null,
    val parentFolder: String? = null,
    val isPlaylist: Boolean = false,
    val thumbnailUrl: String? = null,
    val duration: Long = 0,
    val uploader: String? = null,
    val availableStreams: List<StreamRepresentation> = emptyList(),
    val selectedStreamIndex: Int? = null,
    val isHistory: Boolean = false,
    val dateAdded: Long = System.currentTimeMillis()
)

class DownloaderViewModel : ViewModel() {
    private val _downloadQueue = MutableStateFlow<List<DownloadTask>>(emptyList())
    val downloadQueue: StateFlow<List<DownloadTask>> = _downloadQueue

    private val _previewTask = MutableStateFlow<DownloadTask?>(null)
    val previewTask: StateFlow<DownloadTask?> = _previewTask

    private val _downloadFolder = MutableStateFlow<String?>(null)
    val downloadFolder: StateFlow<String?> = _downloadFolder

    private val _wifiOnly = MutableStateFlow(false)
    val wifiOnly: StateFlow<Boolean> = _wifiOnly

    private var isProcessingQueue = false
    private var queueFile: File? = null
    private var previewJob: Job? = null
    
    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _downloadFolder.value = prefs.getString("download_folder", null)
        _wifiOnly.value = prefs.getBoolean("wifi_only", false)
        
        queueFile = File(context.filesDir, "download_queue.json")
        loadQueue()
        
        processQueue(context)
    }

    private fun loadQueue() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (queueFile?.exists() == true) {
                    val json = queueFile?.readText() ?: return@launch
                    val queue = Json.decodeFromString<List<DownloadTask>>(json)
                    val cleanedQueue = queue.map { 
                        if (it.status == "Downloading..." || it.status == "Extracting..." || it.status == "Preparing...") {
                            it.copy(status = "Pending", progress = 0f, downloadId = null)
                        } else {
                            it
                        }
                    }
                    _downloadQueue.value = cleanedQueue
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun saveQueue() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = Json.encodeToString(_downloadQueue.value)
                queueFile?.writeText(json)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setDownloadFolder(context: Context, uri: String?) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().putString("download_folder", uri).apply()
        _downloadFolder.value = uri
    }

    fun setWifiOnly(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("wifi_only", enabled).apply()
        _wifiOnly.value = enabled
    }

    fun fetchPreview(url: String, isMp3: Boolean) {
        previewJob?.cancel()
        _previewTask.value = null
        if (url.isBlank() || url.lines().size > 1) {
            return
        }

        previewJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val service = try { NewPipe.getServiceByUrl(url) } catch (e: Exception) { ServiceList.YouTube }
                val linkType = try { service.getLinkTypeByUrl(url) } catch (e: Exception) { StreamingService.LinkType.STREAM }
                
                if (linkType == StreamingService.LinkType.PLAYLIST) {
                    _previewTask.value = DownloadTask(url = url, isMp3 = isMp3, isPlaylist = true, status = "Playlist Info Ready")
                    return@launch
                }

                val streamInfo = StreamInfo.getInfo(service, url)
                val audioStreams = streamInfo.audioStreams.map { 
                    StreamRepresentation(it.content ?: "", it.format?.suffix ?: "audio", "${it.bitrate / 1000}kbps", false)
                }
                val videoStreams = streamInfo.videoStreams.map { 
                    StreamRepresentation(it.content ?: "", it.format?.suffix ?: "video", it.resolution ?: "unknown", true)
                }
                
                val allStreams = if (isMp3) audioStreams else videoStreams + audioStreams

                _previewTask.value = DownloadTask(
                    url = url,
                    isMp3 = isMp3,
                    title = streamInfo.name,
                    thumbnailUrl = streamInfo.thumbnails.firstOrNull()?.url,
                    uploader = streamInfo.uploaderName,
                    availableStreams = allStreams,
                    status = "Preview Ready"
                )
            } catch (e: Exception) {
                _previewTask.value = null
            }
        }
    }

    fun clearPreview() {
        previewJob?.cancel()
        _previewTask.value = null
    }

    fun addToQueue(context: Context, url: String, isMp3: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val preview = _previewTask.value
            if (preview != null && preview.url == url && preview.isMp3 == isMp3) {
                _downloadQueue.value = _downloadQueue.value + preview.copy(id = UUID.randomUUID().toString(), status = if (preview.isPlaylist) "Playlist Pending" else "Pending")
                _previewTask.value = null
            } else {
                val urls = url.split("\n").map { it.trim() }.filter { it.isNotBlank() }
                val newTasks = urls.map { singleUrl ->
                    val service = try { NewPipe.getServiceByUrl(singleUrl) } catch (e: Exception) { ServiceList.YouTube }
                    val linkType = try { service.getLinkTypeByUrl(singleUrl) } catch (e: Exception) { StreamingService.LinkType.STREAM }
                    
                    val isPlaylist = linkType == StreamingService.LinkType.PLAYLIST
                    DownloadTask(
                        url = singleUrl, 
                        isMp3 = isMp3, 
                        isPlaylist = isPlaylist,
                        status = if (isPlaylist) "Playlist Pending" else "Pending"
                    )
                }
                _downloadQueue.value = _downloadQueue.value + newTasks
            }
            saveQueue()
            startBackgroundService(context)
            processQueue(context)
        }
    }

    fun retryTask(context: Context, id: String) {
        updateTask(id) { it.copy(status = "Pending", progress = 0f, downloadId = null, selectedStreamIndex = null, availableStreams = emptyList()) }
        saveQueue()
        startBackgroundService(context)
        processQueue(context)
    }

    private fun startBackgroundService(context: Context) {
        viewModelScope.launch(Dispatchers.Main) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    fun removeTask(context: Context, id: String) {
        _downloadQueue.value = _downloadQueue.value.filter { it.id != id }
        saveQueue()
    }
    
    fun clearHistory() {
        _downloadQueue.value = _downloadQueue.value.filter { !it.isHistory }
        saveQueue()
    }

    fun selectStream(id: String, index: Int) {
        updateTask(id) { it.copy(selectedStreamIndex = index, status = "Pending") }
        saveQueue()
    }

    fun selectPreviewStream(index: Int) {
        _previewTask.value = _previewTask.value?.copy(selectedStreamIndex = index)
    }

    private fun processQueue(context: Context) {
        if (isProcessingQueue) return
        
        viewModelScope.launch(Dispatchers.IO) {
            isProcessingQueue = true
            while (true) {
                val nextTask = _downloadQueue.value.firstOrNull { 
                    (it.status == "Pending" || it.status == "Playlist Pending") && !it.isHistory 
                } ?: break
                
                if (nextTask.isPlaylist) {
                    expandPlaylist(context, nextTask)
                    saveQueue()
                    continue
                }

                if (nextTask.availableStreams.isEmpty()) {
                    updateTaskStatus(nextTask.id, "Extracting...")
                    saveQueue()
                    
                    try {
                        val service = try { NewPipe.getServiceByUrl(nextTask.url) } catch (e: Exception) { ServiceList.YouTube }
                        val streamInfo = StreamInfo.getInfo(service, nextTask.url)
                        
                        val audioStreams = streamInfo.audioStreams.map { 
                            StreamRepresentation(it.content ?: "", it.format?.suffix ?: "audio", "${it.bitrate / 1000}kbps", false)
                        }
                        val videoStreams = streamInfo.videoStreams.map { 
                            StreamRepresentation(it.content ?: "", it.format?.suffix ?: "video", it.resolution ?: "unknown", true)
                        }
                        
                        val allStreams = if (nextTask.isMp3) audioStreams else videoStreams + audioStreams

                        updateTask(nextTask.id) { 
                            it.copy(
                                title = streamInfo.name,
                                thumbnailUrl = streamInfo.thumbnails.firstOrNull()?.url,
                                duration = streamInfo.duration,
                                uploader = streamInfo.uploaderName,
                                availableStreams = allStreams,
                                status = "Pending"
                            )
                        }
                        saveQueue()
                    } catch (e: Exception) {
                        updateTaskStatus(nextTask.id, "Error: ${e.localizedMessage}")
                        saveQueue()
                    }
                    continue
                }

                val stream = if (nextTask.selectedStreamIndex != null) {
                    nextTask.availableStreams.getOrNull(nextTask.selectedStreamIndex)
                } else {
                    if (nextTask.isMp3) {
                        nextTask.availableStreams.firstOrNull()
                    } else {
                        nextTask.availableStreams.filter { it.isVideo }.firstOrNull() 
                            ?: nextTask.availableStreams.firstOrNull()
                    }
                }

                if (stream != null) {
                    val extension = if (nextTask.isMp3) "mp3" else "mp4"
                    val fileName = sanitizeFileName("${nextTask.title}.$extension")
                    
                    fastDownload(context, nextTask.id, stream.content, fileName, nextTask.isMp3, nextTask.parentFolder)
                } else {
                    updateTaskStatus(nextTask.id, "Error: No stream selected")
                    saveQueue()
                }
                delay(1000)
            }
            isProcessingQueue = false
        }
    }

    private suspend fun fastDownload(context: Context, taskId: String, url: String, fileName: String, isMp3: Boolean, parentFolder: String?) {
        val app = context.applicationContext as AsgardApp
        val client = app.okHttpClient
        
        try {
            updateTaskStatus(taskId, "Preparing...")
            
            val baseFolderUriString = _downloadFolder.value ?: throw Exception("Set download folder in settings")
            val baseFolderUri = Uri.parse(baseFolderUriString)
            var targetFolder = DocumentFile.fromTreeUri(context, baseFolderUri) ?: throw Exception("Folder access lost")
            
            if (parentFolder != null) {
                targetFolder = targetFolder.findFile(parentFolder) ?: targetFolder.createDirectory(parentFolder) ?: targetFolder
            }
            
            targetFolder.findFile(fileName)?.delete()
            val newFile = targetFolder.createFile(if (isMp3) "audio/mpeg" else "video/mp4", fileName) ?: throw Exception("File creation failed")

            // Instead of HEAD, we do a GET with a Range to check size and range support
            val initialResponse = withContext(Dispatchers.IO) { 
                client.newCall(OkHttpRequest.Builder()
                    .url(url)
                    .addHeader("User-Agent", USER_AGENT)
                    .addHeader("Range", "bytes=0-0")
                    .build()).execute() 
            }
            
            val contentRange = initialResponse.header("Content-Range")
            val totalSize = contentRange?.substringAfterLast("/")?.toLongOrNull() ?: -1L
            val supportsRanges = initialResponse.code == 206
            initialResponse.close()

            updateTaskStatus(taskId, "Downloading...")

            context.contentResolver.openFileDescriptor(newFile.uri, "rw")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).channel.use { channel ->
                    val downloadedBytes = AtomicLong(0)

                    if (supportsRanges && totalSize > 2 * 1024 * 1024) {
                        val numChunks = 2 // Reduced to 2 for better stability on YouTube
                        val chunkSize = totalSize / numChunks
                        
                        coroutineScope {
                            (0 until numChunks).map { i ->
                                launch(Dispatchers.IO) {
                                    val start = i * chunkSize
                                    val end = if (i == numChunks - 1) totalSize - 1 else (i + 1) * chunkSize - 1
                                    
                                    var chunkRetries = 0
                                    val maxChunkRetries = 3
                                    var success = false
                                    
                                    while (chunkRetries < maxChunkRetries && !success) {
                                        try {
                                            client.newCall(OkHttpRequest.Builder()
                                                .url(url)
                                                .addHeader("User-Agent", USER_AGENT)
                                                .addHeader("Range", "bytes=$start-$end")
                                                .build()).execute().use { response ->
                                                if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                                                
                                                val stream = response.body?.byteStream() ?: return@use
                                                val buffer = ByteArray(32768) // Smaller buffer for more frequent progress updates
                                                var read: Int
                                                var currentPos = start
                                                
                                                while (stream.read(buffer).also { read = it } != -1) {
                                                    if (_downloadQueue.value.none { it.id == taskId }) {
                                                        cancel()
                                                        return@use
                                                    }
                                                    channel.write(ByteBuffer.wrap(buffer, 0, read), currentPos)
                                                    currentPos += read
                                                    val total = downloadedBytes.addAndGet(read.toLong())
                                                    if (totalSize > 0) {
                                                        updateTask(taskId) { it.copy(progress = total.toFloat() / totalSize) }
                                                    }
                                                }
                                                success = true
                                            }
                                        } catch (e: Exception) {
                                            chunkRetries++
                                            if (chunkRetries < maxChunkRetries) delay(1000L * chunkRetries)
                                        }
                                    }
                                    if (!success) throw Exception("Chunk $i failed after $maxChunkRetries retries")
                                }
                            }
                        }
                    } else {
                        client.newCall(OkHttpRequest.Builder()
                            .url(url)
                            .addHeader("User-Agent", USER_AGENT)
                            .build()).execute().use { response ->
                            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                            val stream = response.body?.byteStream() ?: throw Exception("Empty body")
                            val fullTotalSize = response.body?.contentLength() ?: -1L
                            val buffer = ByteArray(32768)
                            var read: Int
                            var currentPos = 0L
                            while (stream.read(buffer).also { read = it } != -1) {
                                if (_downloadQueue.value.none { it.id == taskId }) break
                                channel.write(ByteBuffer.wrap(buffer, 0, read), currentPos)
                                currentPos += read
                                val total = downloadedBytes.addAndGet(read.toLong())
                                if (fullTotalSize > 0) updateTask(taskId) { it.copy(progress = total.toFloat() / fullTotalSize) }
                            }
                        }
                    }
                }
            }
            updateTask(taskId) { it.copy(status = "Completed", progress = 1f, isHistory = true) }
            saveQueue()
        } catch (e: Exception) {
            updateTaskStatus(taskId, "Error: ${e.localizedMessage ?: "Timeout or Network issue"}")
            saveQueue()
        }
    }

    private fun sanitizeFileName(name: String): String {
        var sanitized = name.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
        sanitized = sanitized.trim()
        while (sanitized.endsWith(".") || sanitized.endsWith(" ")) {
            sanitized = sanitized.substring(0, sanitized.length - 1)
        }
        if (sanitized.length > 127) {
            val ext = sanitized.substringAfterLast(".", "")
            val base = sanitized.substringBeforeLast(".")
            sanitized = base.take(120) + (if (ext.isNotEmpty()) ".$ext" else "")
        }
        return sanitized.ifEmpty { "download_${System.currentTimeMillis()}" }
    }

    private fun expandPlaylist(context: Context, playlistTask: DownloadTask) {
        updateTaskStatus(playlistTask.id, "Extracting Playlist...")
        try {
            val service = try { NewPipe.getServiceByUrl(playlistTask.url) } catch (e: Exception) { ServiceList.YouTube }
            val playlistInfo = PlaylistInfo.getInfo(service, playlistTask.url)
            val playlistTitle = sanitizeFileName(playlistInfo.name)
            
            val newTasks = playlistInfo.relatedItems.map { item ->
                DownloadTask(
                    url = item.url,
                    isMp3 = playlistTask.isMp3,
                    title = item.name,
                    status = "Pending",
                    parentFolder = playlistTitle,
                    thumbnailUrl = item.thumbnails.firstOrNull()?.url,
                    uploader = item.uploaderName
                )
            }
            
            _downloadQueue.value = _downloadQueue.value.filter { it.id != playlistTask.id } + newTasks
            saveQueue()
            
        } catch (e: Exception) {
            updateTaskStatus(playlistTask.id, "Error: ${e.localizedMessage}")
            saveQueue()
        }
    }

    private fun updateTaskStatus(id: String, status: String) {
        updateTask(id) { it.copy(status = status) }
    }

    private fun updateTask(id: String, transform: (DownloadTask) -> DownloadTask) {
        _downloadQueue.value = _downloadQueue.value.map {
            if (it.id == id) transform(it) else it
        }
    }
}
