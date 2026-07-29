package com.example.asgard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.StrokeCap

import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.BorderStroke

import androidx.compose.ui.tooling.preview.Preview
import com.example.asgard.ui.theme.AsgardTheme
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.Intent
import android.content.Context
import android.app.DownloadManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.List
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items

import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

import androidx.compose.ui.res.stringResource

import androidx.compose.material.icons.filled.Refresh

import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != 
                PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
        
        val sharedText = if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)
        } else {
            null
        }
        
        val sharedUrl = sharedText?.let { text ->
            val urlRegex = "(https?://[^\\s]+)".toRegex()
            urlRegex.find(text)?.value
        }

        setContent {
            AsgardTheme {
                val app = LocalContext.current.applicationContext as AsgardApp
                val viewModel: DownloaderViewModel = app.downloaderViewModel
                val context = LocalContext.current
                LaunchedEffect(Unit) {
                    viewModel.init(context)
                }

                var currentScreen by remember { mutableStateOf("home") }
                var sharedUrlState by remember { mutableStateOf(sharedUrl) }
                
                val folderPickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocumentTree(),
                    onResult = { uri ->
                        uri?.let {
                            contentResolver.takePersistableUriPermission(
                                it,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            )
                            viewModel.setDownloadFolder(context, it.toString())
                        }
                    }
                )

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        @OptIn(ExperimentalMaterial3Api::class)
                        TopAppBar(
                            title = { 
                                Text(
                                    when (currentScreen) {
                                        "settings" -> stringResource(R.string.settings)
                                        "queue" -> "DOWNLOADS"
                                        else -> stringResource(R.string.app_name).uppercase()
                                    },
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 1.sp
                                    )
                                ) 
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background,
                                titleContentColor = MaterialTheme.colorScheme.primary,
                                navigationIconContentColor = MaterialTheme.colorScheme.primary,
                                actionIconContentColor = MaterialTheme.colorScheme.primary
                            ),
                            navigationIcon = {
                                if (currentScreen != "home") {
                                    IconButton(onClick = { currentScreen = "home" }) {
                                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                                    }
                                }
                            },
                            actions = {
                                if (currentScreen == "home") {
                                    IconButton(onClick = { currentScreen = "queue" }) {
                                        Icon(Icons.Default.List, contentDescription = "Queue")
                                    }
                                    IconButton(onClick = { currentScreen = "settings" }) {
                                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                                    }
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    when (currentScreen) {
                        "settings" -> {
                            SettingsScreen(
                                modifier = Modifier.padding(innerPadding),
                                viewModel = viewModel,
                                onSelectFolder = {
                                    folderPickerLauncher.launch(null)
                                }
                            )
                        }
                        "queue" -> {
                            QueueScreen(
                                modifier = Modifier.padding(innerPadding),
                                viewModel = viewModel
                            )
                        }
                        else -> {
                            DownloaderScreen(
                                modifier = Modifier.padding(innerPadding),
                                viewModel = viewModel,
                                initialUrl = sharedUrlState ?: "",
                                onNavigateToQueue = { currentScreen = "queue" },
                                onUrlConsumed = { sharedUrlState = null }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: DownloaderViewModel,
    onSelectFolder: () -> Unit
) {
    val downloadFolder by viewModel.downloadFolder.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.wifi_only),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            stringResource(R.string.wifi_only_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val wifiOnly by viewModel.wifiOnly.collectAsState()
                    Switch(
                        checked = wifiOnly,
                        onCheckedChange = { viewModel.setWifiOnly(context, it) }
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.download_location),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = downloadFolder ?: "Default (System Downloads)",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onSelectFolder,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.change_folder))
                }
                if (downloadFolder != null) {
                    TextButton(
                        onClick = { viewModel.setDownloadFolder(context, null) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.reset_to_default), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            "Note: Choosing a custom folder will save files to that location once the download is finished.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

@Composable
fun DownloaderScreen(
    modifier: Modifier = Modifier,
    viewModel: DownloaderViewModel,
    initialUrl: String = "",
    onNavigateToQueue: () -> Unit,
    onUrlConsumed: () -> Unit
) {
    var url by remember { mutableStateOf(initialUrl) }
    var isMp3 by remember { mutableStateOf(false) }
    val downloadQueue by viewModel.downloadQueue.collectAsState()
    val previewTask by viewModel.previewTask.collectAsState()
    val context = LocalContext.current

    val activeQueue = downloadQueue.filter { !it.isHistory }

    LaunchedEffect(initialUrl) {
        if (initialUrl.isNotEmpty()) {
            url = initialUrl
            onUrlConsumed()
        }
    }

    LaunchedEffect(url, isMp3) {
        if (url.isNotBlank() && url.lines().size == 1) {
            viewModel.fetchPreview(url, isMp3)
        } else {
            viewModel.clearPreview()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            ),
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "YGGDRASIL DOWNLOADER",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(top = 4.dp)
        )
        
        Spacer(modifier = Modifier.height(32.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.youtube_url)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 150.dp),
                    shape = RoundedCornerShape(16.dp),
                    placeholder = { Text("https://www.youtube.com/...", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        cursorColor = MaterialTheme.colorScheme.primary
                    ),
                    singleLine = false,
                    maxLines = 5
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        onClick = { isMp3 = false },
                        shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
                        color = if (!isMp3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                        contentColor = if (!isMp3) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) { Text(stringResource(R.string.mp4), style = MaterialTheme.typography.labelLarge) }
                    }
                    Surface(
                        onClick = { isMp3 = true },
                        shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp),
                        color = if (isMp3) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surface,
                        contentColor = if (isMp3) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) { Text(stringResource(R.string.mp3), style = MaterialTheme.typography.labelLarge) }
                    }
                }
            }
        }

        if (previewTask != null) {
            var showPreviewPicker by remember { mutableStateOf(false) }
            
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (previewTask!!.thumbnailUrl != null) {
                            AsyncImage(
                                model = previewTask!!.thumbnailUrl,
                                contentDescription = null,
                                modifier = Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (previewTask!!.title.isNotEmpty()) previewTask!!.title else "Metadata Ready",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (previewTask!!.uploader != null) {
                                Text(previewTask!!.uploader!!, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    
                    if (previewTask!!.availableStreams.isNotEmpty()) {
                        val selectedStream = previewTask!!.availableStreams.getOrNull(previewTask!!.selectedStreamIndex ?: 0)
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = { showPreviewPicker = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "FORMAT: ${selectedStream?.quality ?: "Default"} (${selectedStream?.format ?: "?"})",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                if (showPreviewPicker) {
                    AlertDialog(
                        onDismissRequest = { showPreviewPicker = false },
                        title = { Text("Select Quality") },
                        text = {
                            Column {
                                previewTask!!.availableStreams.forEachIndexed { index, stream ->
                                    TextButton(
                                        onClick = {
                                            viewModel.selectPreviewStream(index)
                                            showPreviewPicker = false
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("${stream.quality} (${stream.format})")
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showPreviewPicker = false }) { Text("Cancel") }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    viewModel.addToQueue(context, url, isMp3)
                    url = "" // Clear URL after adding to queue
                    viewModel.clearPreview() // Explicitly clear preview
                    onNavigateToQueue() // Navigate to queue after adding
                },
                enabled = url.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    text = stringResource(R.string.add_to_queue),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }

            if (activeQueue.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                TextButton(onClick = onNavigateToQueue) {
                    Text(
                        text = "VIEW ACTIVE QUEUE (${activeQueue.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

@Composable
fun QueueScreen(
    modifier: Modifier = Modifier,
    viewModel: DownloaderViewModel
) {
    val downloadQueue by viewModel.downloadQueue.collectAsState()
    val context = LocalContext.current

    val activeQueue = downloadQueue.filter { !it.isHistory }
    val historyQueue = downloadQueue.filter { it.isHistory }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        if (activeQueue.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.download_queue),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = stringResource(R.string.items, activeQueue.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            items(activeQueue.reversed(), key = { it.id }) { task ->
                QueueItem(
                    viewModel = viewModel,
                    context = context,
                    task = task,
                    onRemove = { viewModel.removeTask(context, task.id) }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        if (historyQueue.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "HISTORY",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    TextButton(onClick = { viewModel.clearHistory() }) {
                        Text("CLEAR", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            items(historyQueue.reversed(), key = { it.id }) { task ->
                QueueItem(
                    viewModel = viewModel,
                    context = context,
                    task = task,
                    onRemove = { viewModel.removeTask(context, task.id) }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        if (activeQueue.isEmpty() && historyQueue.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillParentMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.queue_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
fun QueueItem(viewModel: DownloaderViewModel, context: Context, task: DownloadTask, onRemove: () -> Unit) {
    var showQualityPicker by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = if (task.status.contains("Downloading")) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) else null
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (task.thumbnailUrl != null) {
                    AsyncImage(
                        model = task.thumbnailUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (task.title.isNotEmpty()) task.title else task.url,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (task.isMp3) stringResource(R.string.mp3) else stringResource(R.string.mp4),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        if (task.uploader != null) {
                            Text(
                                text = " • ${task.uploader}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (task.status.contains("Error")) {
                        IconButton(
                            onClick = { viewModel.retryTask(context, task.id) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Retry",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.remove),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            if (task.status == "Select Quality" && task.availableStreams.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { showQualityPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("SELECT QUALITY", style = MaterialTheme.typography.labelLarge)
                }
            } else if (!task.isHistory) {
                Spacer(modifier = Modifier.height(12.dp))
                
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = if (task.status.contains("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surface,
                    strokeCap = StrokeCap.Round
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = task.status,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (task.status.contains("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                    if (task.progress > 0f) {
                        Text(
                            text = "${(task.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = task.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    if (showQualityPicker) {
        AlertDialog(
            onDismissRequest = { showQualityPicker = false },
            title = { Text("Select Quality") },
            text = {
                Column {
                    task.availableStreams.forEachIndexed { index, stream ->
                        TextButton(
                            onClick = {
                                viewModel.selectStream(task.id, index)
                                showQualityPicker = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("${stream.quality} (${stream.format})")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQualityPicker = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
fun DownloaderPreview() {
    AsgardTheme {
        DownloaderScreen(
            viewModel = DownloaderViewModel(),
            initialUrl = "",
            onNavigateToQueue = {},
            onUrlConsumed = {}
        )
    }
}
