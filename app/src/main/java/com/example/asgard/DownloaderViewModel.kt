package com.example.asgard

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

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
                        if (it.status == "Downloading..." || it.status == "Extracting...") {
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
        _previewTask.value = null // Clear old preview while loading new one
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
                // Use the preview task directly if it matches
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
        val task = _downloadQueue.value.find { it.id == id }
        task?.downloadId?.let { downloadId ->
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.remove(downloadId)
        }
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

                // If availableStreams is empty, we need to extract metadata first
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
                                status = "Pending" // Keep pending, we'll pick best in next loop or wait if user wants to change
                            )
                        }
                        saveQueue()
                    } catch (e: Exception) {
                        updateTaskStatus(nextTask.id, "Error: ${e.localizedMessage}")
                        saveQueue()
                    }
                    continue
                }

                updateTaskStatus(nextTask.id, "Downloading...")
                saveQueue()
                
                try {
                    val stream = if (nextTask.selectedStreamIndex != null) {
                        nextTask.availableStreams.getOrNull(nextTask.selectedStreamIndex)
                    } else {
                        // PICK DEFAULT: Best quality
                        if (nextTask.isMp3) {
                            // Already filtered or just pick first
                            nextTask.availableStreams.firstOrNull()
                        } else {
                            // Pick best video
                            nextTask.availableStreams.filter { it.isVideo }.firstOrNull() 
                                ?: nextTask.availableStreams.firstOrNull()
                        }
                    }

                    if (stream != null) {
                        val extension = if (nextTask.isMp3) "mp3" else "mp4"
                        val fileName = sanitizeFileName("${nextTask.title}.$extension")
                        
                        val downloadId = startDownload(context, stream.content, nextTask.title, fileName)
                        
                        updateTask(nextTask.id) { 
                            it.copy(
                                fileName = fileName,
                                downloadId = downloadId
                            )
                        }
                        saveQueue()
                        
                        launch { monitorDownload(context, nextTask.id, downloadId, fileName, nextTask.isMp3) }
                    } else {
                        updateTaskStatus(nextTask.id, "Error: No stream selected")
                        saveQueue()
                    }
                } catch (e: Exception) {
                    updateTaskStatus(nextTask.id, "Error: ${e.localizedMessage}")
                    saveQueue()
                }
                delay(1000)
            }
            isProcessingQueue = false
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

    private fun startDownload(context: Context, url: String, title: String, fileName: String): Long {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(title)
            .setDescription("Downloading from Asgard")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(!_wifiOnly.value)
            .setAllowedOverRoaming(!_wifiOnly.value)
            
        if (_wifiOnly.value) {
            request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
        }
        
        return downloadManager.enqueue(request)
    }

    private suspend fun monitorDownload(context: Context, taskId: String, downloadId: Long, fileName: String, isMp3: Boolean) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        var downloading = true
        var retryCount = 0
        val maxRetries = 5

        while (downloading) {
            if (_downloadQueue.value.none { it.id == taskId }) break

            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = downloadManager.query(query)
            
            if (cursor != null && cursor.moveToFirst()) {
                retryCount = 0
                val bytesDownloaded = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val totalBytes = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))

                if (totalBytes > 0) {
                    val progress = bytesDownloaded.toFloat() / totalBytes.toFloat()
                    updateTask(taskId) { it.copy(progress = progress) }
                }

                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        downloading = false
                        handleDownloadFinished(context, taskId, downloadId, fileName, isMp3)
                        saveQueue()
                    }
                    DownloadManager.STATUS_FAILED -> {
                        downloading = false
                        updateTaskStatus(taskId, "Error: Download failed")
                        saveQueue()
                    }
                }
                cursor.close()
            } else {
                cursor?.close()
                retryCount++
                if (retryCount >= maxRetries) {
                    downloading = false
                }
            }
            delay(1000)
        }
    }

    private fun handleDownloadFinished(context: Context, taskId: String, downloadId: Long, fileName: String, isMp3: Boolean) {
        val task = _downloadQueue.value.find { it.id == taskId } ?: return
        val baseFolderUriString = _downloadFolder.value
        
        if (baseFolderUriString != null) {
            try {
                updateTaskStatus(taskId, "Moving file...")
                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val downloadedFileUri = downloadManager.getUriForDownloadedFile(downloadId)
                val baseFolderUri = Uri.parse(baseFolderUriString)
                var targetFolder = DocumentFile.fromTreeUri(context, baseFolderUri)
                
                if (task.parentFolder != null) {
                    val subFolder = targetFolder?.findFile(task.parentFolder!!) 
                        ?: targetFolder?.createDirectory(task.parentFolder!!)
                    targetFolder = subFolder
                }
                
                val mimeType = if (isMp3) "audio/mpeg" else "video/mp4"
                val newFile = targetFolder?.createFile(mimeType, fileName)
                
                newFile?.uri?.let { destUri ->
                    context.contentResolver.openInputStream(downloadedFileUri!!)?.use { input ->
                        context.contentResolver.openOutputStream(destUri)?.use { output ->
                            input.copyTo(output)
                        }
                    }
                    context.contentResolver.delete(downloadedFileUri!!, null, null)
                    updateTask(taskId) { it.copy(status = "Completed & Moved", progress = 1f, isHistory = true) }
                } ?: run {
                    updateTask(taskId) { it.copy(status = "Completed (Move Failed)", progress = 1f, isHistory = true) }
                }
            } catch (e: Exception) {
                updateTask(taskId) { it.copy(status = "Completed (Error: ${e.localizedMessage})", progress = 1f, isHistory = true) }
            }
        } else {
            updateTask(taskId) { it.copy(status = "Completed", progress = 1f, isHistory = true) }
        }
    }
}
