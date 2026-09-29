package com.example.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.AppDatabase
import com.example.data.model.BrowserBookmark
import com.example.data.model.BrowserHistory
import com.example.data.model.BrowserTab
import com.example.data.model.DetectedMedia
import com.example.data.model.DownloadEngine
import com.example.data.model.DownloadStatus
import com.example.data.model.DownloadTask
import com.example.data.model.ExtractedVideoOption
import com.example.data.model.MediaType
import com.example.data.model.VideoInfo
import com.example.data.repository.BrowserRepository
import com.example.data.repository.DownloadRepository
import com.example.download.AppDownloadManager
import com.example.download.VideoExtractor
import com.example.download.YtDlpEngine
import com.example.ui.navigation.Screen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

enum class LibrarySortBy {
    DATE_DESC,
    DATE_ASC,
    SIZE_DESC,
    NAME_ASC
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    private val downloadDao = database.downloadDao()
    private val browserDao = database.browserDao()

    val downloadRepository = DownloadRepository(downloadDao)
    val browserRepository = BrowserRepository(browserDao)
    val downloadManager = AppDownloadManager(application, downloadDao)
    private val videoExtractor = VideoExtractor(application)

    // yt-dlp engine -----------------------------------------------------
    val ytDlpStatus: StateFlow<YtDlpEngine.Status> = YtDlpEngine.status

    private val _isUpdatingYtDlp = MutableStateFlow(false)
    val isUpdatingYtDlp: StateFlow<Boolean> = _isUpdatingYtDlp.asStateFlow()

    private val _ytDlpMessage = MutableStateFlow<String?>(null)
    val ytDlpMessage: StateFlow<String?> = _ytDlpMessage.asStateFlow()

    init {
        // Unpacking python/ffmpeg takes a moment on first launch; do it up front so
        // the first paste-and-analyze does not have to wait for it.
        viewModelScope.launch {
            YtDlpEngine.ensureReady(application)
        }
    }

    fun updateYtDlpEngine() {
        if (_isUpdatingYtDlp.value) return
        viewModelScope.launch {
            _isUpdatingYtDlp.value = true
            _ytDlpMessage.value = "Checking for a newer yt-dlp release…"
            val result = YtDlpEngine.update(getApplication<Application>())
            _isUpdatingYtDlp.value = false
            _ytDlpMessage.value = result.getOrElse { error ->
                "Update failed: ${error.localizedMessage ?: "unknown error"}"
            }
        }
    }

    fun dismissYtDlpMessage() {
        _ytDlpMessage.value = null
    }

    // Navigation
    private val _currentScreen = MutableStateFlow(Screen.LINK)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    fun navigateTo(screen: Screen) {
        _currentScreen.value = screen
    }

    // Direct Link Screen State
    private val _linkInput = MutableStateFlow("")
    val linkInput: StateFlow<String> = _linkInput.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val _analyzedVideoInfo = MutableStateFlow<VideoInfo?>(null)
    val analyzedVideoInfo: StateFlow<VideoInfo?> = _analyzedVideoInfo.asStateFlow()

    private val _showFormatDialog = MutableStateFlow(false)
    val showFormatDialog: StateFlow<Boolean> = _showFormatDialog.asStateFlow()

    private val _linkErrorMessage = MutableStateFlow<String?>(null)
    val linkErrorMessage: StateFlow<String?> = _linkErrorMessage.asStateFlow()

    private val _clipboardDetectedUrl = MutableStateFlow<String?>(null)
    val clipboardDetectedUrl: StateFlow<String?> = _clipboardDetectedUrl.asStateFlow()

    fun updateLinkInput(url: String) {
        _linkInput.value = url
        _linkErrorMessage.value = null
    }

    fun checkClipboard() {
        try {
            val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = clipboard.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val text = clipData.getItemAt(0).text?.toString()?.trim()
                if (!text.isNullOrBlank() && (text.startsWith("http://") || text.startsWith("https://"))) {
                    _clipboardDetectedUrl.value = text
                }
            }
        } catch (_: Exception) {}
    }

    fun applyClipboardUrl() {
        _clipboardDetectedUrl.value?.let {
            _linkInput.value = it
            _clipboardDetectedUrl.value = null
            analyzeLink(it)
        }
    }

    fun dismissClipboardBanner() {
        _clipboardDetectedUrl.value = null
    }

    fun analyzeLink(urlToAnalyze: String = _linkInput.value) {
        val url = urlToAnalyze.trim()
        if (url.isBlank()) {
            _linkErrorMessage.value = "Please enter a valid link"
            return
        }

        viewModelScope.launch {
            _isAnalyzing.value = true
            _linkErrorMessage.value = null
            val result = videoExtractor.extractInfo(url)
            _isAnalyzing.value = false

            result.onSuccess { info ->
                _analyzedVideoInfo.value = info
                _showFormatDialog.value = true
            }.onFailure { err ->
                _linkErrorMessage.value = err.localizedMessage ?: "Failed to extract video details"
            }
        }
    }

    fun dismissFormatDialog() {
        _showFormatDialog.value = false
    }

    fun startDownloadFromOption(info: VideoInfo, option: ExtractedVideoOption, customName: String? = null) {
        _showFormatDialog.value = false
        val finalTitle = customName?.ifBlank { info.title } ?: info.title
        val mediaType = if (option.isAudioOnly) MediaType.AUDIO else MediaType.VIDEO

        downloadManager.startDownload(
            url = option.downloadUrl,
            title = finalTitle,
            quality = option.qualityLabel,
            format = option.format,
            estimatedBytes = option.estimatedBytes,
            thumbnailUrl = info.thumbnailUrl,
            mediaType = mediaType,
            engine = option.engine,
            formatSelector = option.formatSelector,
            sourcePageUrl = option.sourcePageUrl ?: info.sourceUrl
        )

        // Navigate to Downloading Screen so user immediately sees active progress!
        navigateTo(Screen.DOWNLOADING)
    }

    // Downloads screen flows
    val activeTasks: StateFlow<List<DownloadTask>> = downloadRepository.activeTasks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val completedTasks: StateFlow<List<DownloadTask>> = downloadRepository.completedTasks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalDownloadedBytes: StateFlow<Long?> = downloadRepository.totalDownloadedBytes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    fun pauseTask(taskId: String) = downloadManager.pauseDownload(taskId)
    fun resumeTask(taskId: String) = downloadManager.resumeDownload(taskId)
    fun retryTask(taskId: String) = downloadManager.retryDownload(taskId)
    fun cancelTask(taskId: String) = downloadManager.cancelOrDeleteDownload(taskId, deleteFile = true)
    fun pauseAll() = downloadManager.pauseAll()
    fun resumeAll() = downloadManager.resumeAll()

    // Browser State
    private val _browserTabs = MutableStateFlow<List<BrowserTab>>(
        listOf(
            BrowserTab(
                id = "default_tab",
                title = "Home",
                url = "https://www.google.com"
            )
        )
    )
    val browserTabs: StateFlow<List<BrowserTab>> = _browserTabs.asStateFlow()

    private val _activeTabId = MutableStateFlow("default_tab")
    val activeTabId: StateFlow<String> = _activeTabId.asStateFlow()

    private val _browserUrlInput = MutableStateFlow("https://www.google.com")
    val browserUrlInput: StateFlow<String> = _browserUrlInput.asStateFlow()

    private val _browserProgress = MutableStateFlow(0)
    val browserProgress: StateFlow<Int> = _browserProgress.asStateFlow()

    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    private val _canGoForward = MutableStateFlow(false)
    val canGoForward: StateFlow<Boolean> = _canGoForward.asStateFlow()

    private val _isDesktopMode = MutableStateFlow(false)
    val isDesktopMode: StateFlow<Boolean> = _isDesktopMode.asStateFlow()

    private val _isAdBlockEnabled = MutableStateFlow(true)
    val isAdBlockEnabled: StateFlow<Boolean> = _isAdBlockEnabled.asStateFlow()

    private val _detectedMediaList = MutableStateFlow<List<DetectedMedia>>(emptyList())
    val detectedMediaList: StateFlow<List<DetectedMedia>> = _detectedMediaList.asStateFlow()

    private val _showDetectedMediaSheet = MutableStateFlow(false)
    val showDetectedMediaSheet: StateFlow<Boolean> = _showDetectedMediaSheet.asStateFlow()

    private val _rescanTrigger = MutableStateFlow(0L)
    val rescanTrigger: StateFlow<Long> = _rescanTrigger.asStateFlow()

    private val _showTabManagerSheet = MutableStateFlow(false)
    val showTabManagerSheet: StateFlow<Boolean> = _showTabManagerSheet.asStateFlow()

    val browserBookmarks: StateFlow<List<BrowserBookmark>> = browserRepository.bookmarks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val browserHistory: StateFlow<List<BrowserHistory>> = browserRepository.history
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateBrowserUrlInput(url: String) {
        _browserUrlInput.value = url
    }

    fun onWebPageStarted(url: String) {
        _browserUrlInput.value = url
        _browserProgress.value = 15
        _detectedMediaList.value = emptyList() // Clear detected media for new page
    }

    fun onWebPageFinished(url: String, title: String?) {
        _browserProgress.value = 100
        val tabId = _activeTabId.value
        _browserTabs.value = _browserTabs.value.map {
            if (it.id == tabId) it.copy(title = title?.ifBlank { "Web Page" } ?: "Web Page", url = url) else it
        }
        if (!_isIncognito.value) {
            viewModelScope.launch {
                browserRepository.recordHistory(title ?: url, url)
            }
        }
    }

    fun onWebProgressChanged(newProgress: Int) {
        _browserProgress.value = newProgress
    }

    fun updateWebNavigationState(canBack: Boolean, canForward: Boolean) {
        _canGoBack.value = canBack
        _canGoForward.value = canForward
    }

    fun toggleDesktopMode() {
        _isDesktopMode.value = !_isDesktopMode.value
    }

    fun toggleAdBlock() {
        _isAdBlockEnabled.value = !_isAdBlockEnabled.value
    }

    fun toggleDetectedMediaSheet(show: Boolean) {
        _showDetectedMediaSheet.value = show
    }

    fun triggerWebMediaRescan() {
        _rescanTrigger.value = System.currentTimeMillis()
    }

    fun clearDetectedMedia() {
        _detectedMediaList.value = emptyList()
    }

    fun toggleTabManager(show: Boolean) {
        _showTabManagerSheet.value = show
    }

    fun registerDetectedMedia(
        url: String,
        mimeType: String,
        title: String? = null,
        contentLength: Long = 0L
    ) {
        if (url.isBlank()) return
        if (url.startsWith("data:") || url.startsWith("javascript:") || url.startsWith("about:")) return
        val lowerUrl = url.lowercase()
        // Skip common ad networks or tracking pixels
        if (lowerUrl.contains("googleads") || lowerUrl.contains("doubleclick") ||
            lowerUrl.contains("analytics") || lowerUrl.contains("facebook.com/tr")
        ) return

        val current = _detectedMediaList.value
        if (current.any { it.url == url }) return // already detected

        val detectedExt = when {
            url.substringBefore("?").contains(".") -> url.substringBefore("?").substringAfterLast(".").lowercase()
            mimeType.contains("mp3") || mimeType.contains("mpeg") -> "mp3"
            mimeType.contains("webm") -> "webm"
            mimeType.contains("pdf") -> "pdf"
            mimeType.contains("apk") -> "apk"
            mimeType.contains("zip") -> "zip"
            mimeType.contains("audio") -> "mp3"
            else -> "mp4"
        }

        val rawName = title ?: url.substringAfterLast("/").substringBefore("?").ifBlank { "Media_${System.currentTimeMillis() % 10000}" }
        val cleanTitle = if (rawName.isBlank() || rawName == "null") "download_$detectedExt" else rawName

        val isAudio = mimeType.contains("audio") || detectedExt in listOf("mp3", "m4a", "wav", "aac", "ogg")
        val isDoc = detectedExt in listOf("pdf", "doc", "docx", "xls", "xlsx", "zip", "rar", "7z", "apk", "tar", "gz")
        val isVideo = mimeType.contains("video") || detectedExt in listOf("mp4", "webm", "mkv", "mov", "flv", "m3u8", "ts")

        val quality = when {
            url.contains("1080") -> "1080p FHD"
            url.contains("720") -> "720p HD"
            url.contains("480") -> "480p SD"
            isAudio -> "Audio ($detectedExt)"
            isDoc -> "${detectedExt.uppercase()} File"
            else -> if (isVideo) "HD Video" else "File"
        }

        val estimatedBytes = if (contentLength > 0L) {
            contentLength
        } else {
            when {
                url.contains("1080") -> 52_000_000L
                url.contains("720") -> 28_000_000L
                url.contains("480") -> 14_000_000L
                isAudio -> 4_500_000L
                isDoc -> 6_000_000L
                else -> 22_000_000L
            }
        }

        val item = DetectedMedia(
            url = url,
            title = cleanTitle,
            mimeType = mimeType,
            quality = quality,
            estimatedBytes = estimatedBytes,
            sourcePageUrl = _browserUrlInput.value
        )
        _detectedMediaList.value = current + item
    }

    fun downloadDetectedMedia(media: DetectedMedia) {
        _showDetectedMediaSheet.value = false
        val ext = when {
            media.url.substringBefore("?").contains(".") -> media.url.substringBefore("?").substringAfterLast(".").lowercase()
            media.mimeType.contains("audio") || media.mimeType.contains("mp3") -> "mp3"
            media.mimeType.contains("pdf") -> "pdf"
            media.mimeType.contains("apk") -> "apk"
            media.mimeType.contains("zip") -> "zip"
            else -> "mp4"
        }

        val resolvedMediaType = when (ext) {
            "mp3", "m4a", "wav", "aac", "ogg" -> MediaType.AUDIO
            "mp4", "webm", "mkv", "mov", "flv", "3gp", "ts" -> MediaType.VIDEO
            "pdf", "doc", "docx", "xls", "xlsx", "zip", "rar", "7z", "apk" -> MediaType.DOCUMENT
            "jpg", "jpeg", "png", "webp", "gif" -> MediaType.IMAGE
            else -> if (media.mimeType.contains("audio")) MediaType.AUDIO else if (media.mimeType.contains("video")) MediaType.VIDEO else MediaType.OTHER
        }

        // HLS/DASH manifests are playlists, not media: fetching them over plain HTTP
        // just saves a text file. yt-dlp downloads every segment and muxes them.
        val isStreamManifest = ext == "m3u8" || ext == "mpd"

        downloadManager.startDownload(
            url = media.url,
            title = media.title,
            quality = media.quality,
            format = if (isStreamManifest) "MP4" else ext.uppercase(),
            estimatedBytes = if (isStreamManifest) 0L else media.estimatedBytes,
            thumbnailUrl = media.thumbnailUrl,
            mediaType = if (isStreamManifest) MediaType.VIDEO else resolvedMediaType,
            explicitMimeType = if (isStreamManifest) "video/mp4" else media.mimeType,
            engine = if (isStreamManifest) DownloadEngine.YTDLP else DownloadEngine.HTTP,
            formatSelector = if (isStreamManifest) "bestvideo*+bestaudio/best" else null,
            sourcePageUrl = if (isStreamManifest) media.url else null
        )
        navigateTo(Screen.DOWNLOADING)
    }

    fun addBookmark(title: String, url: String) {
        viewModelScope.launch {
            browserRepository.addBookmark(title, url)
        }
    }

    fun removeBookmark(id: Long) {
        viewModelScope.launch {
            browserRepository.removeBookmark(id)
        }
    }

    fun createNewTab(url: String = "https://www.google.com") {
        val newTab = BrowserTab(url = url, title = "New Tab")
        _browserTabs.value = _browserTabs.value + newTab
        _activeTabId.value = newTab.id
        _browserUrlInput.value = url
        _showTabManagerSheet.value = false
    }

    fun selectTab(tabId: String) {
        val tab = _browserTabs.value.find { it.id == tabId }
        if (tab != null) {
            _activeTabId.value = tabId
            _browserUrlInput.value = tab.url
        }
        _showTabManagerSheet.value = false
    }

    fun closeTab(tabId: String) {
        val tabs = _browserTabs.value
        if (tabs.size <= 1) {
            // keep at least one tab
            val resetTab = BrowserTab(url = "https://www.google.com", title = "New Tab")
            _browserTabs.value = listOf(resetTab)
            _activeTabId.value = resetTab.id
            _browserUrlInput.value = resetTab.url
            return
        }

        val updated = tabs.filter { it.id != tabId }
        _browserTabs.value = updated
        if (_activeTabId.value == tabId) {
            val next = updated.last()
            _activeTabId.value = next.id
            _browserUrlInput.value = next.url
        }
    }

    // Finished / Library Screen State
    private val _libraryFilterMediaType = MutableStateFlow<MediaType?>(null)
    val libraryFilterMediaType: StateFlow<MediaType?> = _libraryFilterMediaType.asStateFlow()

    private val _librarySearchQuery = MutableStateFlow("")
    val librarySearchQuery: StateFlow<String> = _librarySearchQuery.asStateFlow()

    private val _librarySortBy = MutableStateFlow(LibrarySortBy.DATE_DESC)
    val librarySortBy: StateFlow<LibrarySortBy> = _librarySortBy.asStateFlow()

    private val _playingTask = MutableStateFlow<DownloadTask?>(null)
    val playingTask: StateFlow<DownloadTask?> = _playingTask.asStateFlow()

    private val _viewingDetailsTask = MutableStateFlow<DownloadTask?>(null)
    val viewingDetailsTask: StateFlow<DownloadTask?> = _viewingDetailsTask.asStateFlow()

    fun setLibraryFilter(type: MediaType?) {
        _libraryFilterMediaType.value = type
    }

    fun setLibrarySearch(query: String) {
        _librarySearchQuery.value = query
    }

    fun setLibrarySort(sortBy: LibrarySortBy) {
        _librarySortBy.value = sortBy
    }

    fun playMedia(task: DownloadTask) {
        _playingTask.value = task
    }

    fun closePlayer() {
        _playingTask.value = null
    }

    fun showFileDetails(task: DownloadTask) {
        _viewingDetailsTask.value = task
    }

    fun closeFileDetails() {
        _viewingDetailsTask.value = null
    }

    fun deleteCompletedFile(taskId: String) {
        downloadManager.cancelOrDeleteDownload(taskId, deleteFile = true)
        if (_playingTask.value?.id == taskId) {
            _playingTask.value = null
        }
        if (_viewingDetailsTask.value?.id == taskId) {
            _viewingDetailsTask.value = null
        }
    }

    fun renameCompletedFile(taskId: String, newTitle: String) {
        viewModelScope.launch {
            val task = downloadDao.getTaskById(taskId) ?: return@launch
            val updated = task.copy(title = newTitle)
            downloadDao.update(updated)
            if (_viewingDetailsTask.value?.id == taskId) {
                _viewingDetailsTask.value = updated
            }
        }
    }

    // Settings & Search Engines
    companion object {
        val SEARCH_ENGINES = listOf(
            "DuckDuckGo" to "https://duckduckgo.com/?q=",
            "Google" to "https://www.google.com/search?q=",
            "Chromium / Google" to "https://www.google.com/search?q=",
            "Bing" to "https://www.bing.com/search?q=",
            "Brave" to "https://search.brave.com/search?q=",
            "Ecosia" to "https://www.ecosia.org/search?q=",
            "Yahoo" to "https://search.yahoo.com/search?q=",
            "Startpage" to "https://www.startpage.com/sp/search?query=",
            "Yandex" to "https://yandex.com/search/?text="
        )

        fun getSearchEngineHome(engine: String): String = when (engine) {
            "DuckDuckGo" -> "https://duckduckgo.com"
            "Bing" -> "https://www.bing.com"
            "Brave" -> "https://search.brave.com"
            "Ecosia" -> "https://www.ecosia.org"
            "Yahoo" -> "https://www.yahoo.com"
            "Startpage" -> "https://www.startpage.com"
            "Yandex" -> "https://yandex.com"
            else -> "https://www.google.com"
        }
    }

    private val _searchEngine = MutableStateFlow("Google")
    val searchEngine: StateFlow<String> = _searchEngine.asStateFlow()

    private val _isIncognito = MutableStateFlow(false)
    val isIncognito: StateFlow<Boolean> = _isIncognito.asStateFlow()

    fun toggleIncognito(enabled: Boolean? = null) {
        _isIncognito.value = enabled ?: !_isIncognito.value
    }

    fun buildSearchOrUrl(input: String): String {
        val trimmed = input.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }
        if (trimmed.contains(".") && !trimmed.contains(" ")) {
            return "https://$trimmed"
        }
        val enginePrefix = SEARCH_ENGINES.firstOrNull { it.first == _searchEngine.value }?.second
            ?: "https://www.google.com/search?q="
        return "$enginePrefix${android.net.Uri.encode(trimmed)}"
    }

    private val _themeMode = MutableStateFlow("dark") // "dark", "light", "system"
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _maxConcurrentDownloads = MutableStateFlow(3)
    val maxConcurrentDownloads: StateFlow<Int> = _maxConcurrentDownloads.asStateFlow()

    private val _isWifiOnly = MutableStateFlow(false)
    val isWifiOnly: StateFlow<Boolean> = _isWifiOnly.asStateFlow()

    fun setSearchEngine(engine: String) {
        _searchEngine.value = engine
        val home = getSearchEngineHome(engine)
        _browserUrlInput.value = home
    }

    fun setThemeMode(mode: String) {
        _themeMode.value = mode
    }

    fun setMaxConcurrentDownloads(limit: Int) {
        _maxConcurrentDownloads.value = limit
        downloadManager.maxConcurrentDownloads = limit
    }

    fun setWifiOnly(wifiOnly: Boolean) {
        _isWifiOnly.value = wifiOnly
        downloadManager.isWifiOnly = wifiOnly
    }

    fun clearDownloadHistory() {
        viewModelScope.launch {
            downloadRepository.deleteCompleted()
        }
    }

    fun clearBrowserHistory() {
        viewModelScope.launch {
            browserRepository.clearHistory()
        }
    }
}
