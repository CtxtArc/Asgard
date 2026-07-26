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
import java.util.UUID

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
    val isPlaylist: Boolean = false
)

class DownloaderViewModel : ViewModel() {
    private val _downloadQueue = MutableStateFlow<List<DownloadTask>>(emptyList())
    val downloadQueue: StateFlow<List<DownloadTask>> = _downloadQueue

    private val _downloadFolder = MutableStateFlow<String?>(null)
    val downloadFolder: StateFlow<String?> = _downloadFolder

    private var isProcessingQueue = false

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _downloadFolder.value = prefs.getString("download_folder", null)
    }

    fun setDownloadFolder(context: Context, uri: String?) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().putString("download_folder", uri).apply()
        _downloadFolder.value = uri
    }

    fun addToQueue(context: Context, url: String, isMp3: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val service = ServiceList.YouTube
            val linkType = try { service.getLinkTypeByUrl(url) } catch (e: Exception) { StreamingService.LinkType.STREAM }
            
            val isPlaylist = linkType == StreamingService.LinkType.PLAYLIST
            val newTask = DownloadTask(
                url = url, 
                isMp3 = isMp3, 
                isPlaylist = isPlaylist,
                status = if (isPlaylist) "Playlist Pending" else "Pending"
            )
            
            _downloadQueue.value = _downloadQueue.value + newTask
            
            withContext(Dispatchers.Main) {
                // Start foreground service
                val intent = Intent(context, DownloadService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }

            processQueue(context)
        }
    }

    fun removeTask(id: String) {
        _downloadQueue.value = _downloadQueue.value.filter { it.id != id }
    }

    private fun processQueue(context: Context) {
        if (isProcessingQueue) return
        
        viewModelScope.launch(Dispatchers.IO) {
            isProcessingQueue = true
            while (true) {
                val nextTask = _downloadQueue.value.firstOrNull { it.status == "Pending" || it.status == "Playlist Pending" } ?: break
                
                if (nextTask.isPlaylist) {
                    expandPlaylist(context, nextTask)
                    continue
                }

                updateTaskStatus(nextTask.id, "Extracting...")
                
                try {
                    val service = ServiceList.YouTube
                    val streamInfo = StreamInfo.getInfo(service, nextTask.url)

                    val stream = if (nextTask.isMp3) {
                        streamInfo.audioStreams.maxByOrNull { it.bitrate }
                    } else {
                        streamInfo.videoStreams.filter { !it.isVideoOnly }.maxByOrNull { it.height }
                            ?: streamInfo.videoStreams.maxByOrNull { it.height }
                    }

                    if (stream != null && stream.content != null) {
                        val extension = if (nextTask.isMp3) "mp3" else "mp4"
                        val fileName = "${streamInfo.name.replace("[\\\\/:*?\"<>|]".toRegex(), "_")}.$extension"
                        
                        val downloadId = startDownload(context, stream.content!!, streamInfo.name, fileName)
                        
                        updateTask(nextTask.id) { 
                            it.copy(
                                title = streamInfo.name,
                                fileName = fileName,
                                status = "Downloading...",
                                downloadId = downloadId
                            )
                        }
                        
                        // Launch monitoring for this specific task
                        launch { monitorDownload(context, nextTask.id, downloadId, fileName, nextTask.isMp3) }
                    } else {
                        updateTaskStatus(nextTask.id, "Error: No stream found")
                    }
                } catch (e: Exception) {
                    updateTaskStatus(nextTask.id, "Error: ${e.localizedMessage}")
                    e.printStackTrace()
                }
                delay(1000) // Small delay between extractions
            }
            isProcessingQueue = false
        }
    }

    private suspend fun expandPlaylist(context: Context, playlistTask: DownloadTask) {
        updateTaskStatus(playlistTask.id, "Extracting Playlist...")
        try {
            val service = ServiceList.YouTube
            val playlistInfo = PlaylistInfo.getInfo(service, playlistTask.url)
            val playlistTitle = playlistInfo.name.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
            
            val newTasks = playlistInfo.relatedItems.map { item ->
                DownloadTask(
                    url = item.url,
                    isMp3 = playlistTask.isMp3,
                    title = item.name,
                    status = "Pending",
                    parentFolder = playlistTitle
                )
            }
            
            // Remove the playlist placeholder and add individual tasks
            _downloadQueue.value = _downloadQueue.value.filter { it.id != playlistTask.id } + newTasks
            
        } catch (e: Exception) {
            updateTaskStatus(playlistTask.id, "Error: ${e.localizedMessage}")
            e.printStackTrace()
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
            .setDescription("Downloading from YouTube")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
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
                    }
                    DownloadManager.STATUS_FAILED -> {
                        downloading = false
                        updateTaskStatus(taskId, "Error: Download failed")
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
                
                // Handle sub-folder for playlist
                if (task.parentFolder != null) {
                    val subFolder = targetFolder?.findFile(task.parentFolder) 
                        ?: targetFolder?.createDirectory(task.parentFolder)
                    targetFolder = subFolder
                }
                
                val mimeType = if (isMp3) "audio/mpeg" else "video/mp4"
                val newFile = targetFolder?.createFile(mimeType, fileName)
                
                newFile?.uri?.let { destUri ->
                    context.contentResolver.openInputStream(downloadedFileUri)?.use { input ->
                        context.contentResolver.openOutputStream(destUri)?.use { output ->
                            input.copyTo(output)
                        }
                    }
                    context.contentResolver.delete(downloadedFileUri, null, null)
                    updateTask(taskId) { it.copy(status = "Completed & Moved", progress = 1f) }
                } ?: run {
                    updateTask(taskId) { it.copy(status = "Completed (Move Failed)", progress = 1f) }
                }
            } catch (e: Exception) {
                updateTask(taskId) { it.copy(status = "Completed (Error: ${e.localizedMessage})", progress = 1f) }
            }
        } else {
            updateTask(taskId) { it.copy(status = "Completed", progress = 1f) }
        }
    }
}
