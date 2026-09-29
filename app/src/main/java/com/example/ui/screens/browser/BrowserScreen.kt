package com.example.ui.screens.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.MainViewModel
import com.example.ui.navigation.Screen
import kotlin.math.roundToInt

private const val CHROME_MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.6613.88 Mobile Safari/537.36"
private const val CHROME_DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

private const val MEDIA_SNIFFER_JS = """
(function() {
    function scanMedia() {
        // 1. Scan HTML5 media elements
        var mediaElements = document.querySelectorAll('video, audio, source, track');
        mediaElements.forEach(function(el) {
            var src = el.src || el.currentSrc;
            if (src && src.startsWith('http')) {
                var mime = el.type || (src.indexOf('.mp3') !== -1 ? 'audio/mpeg' : 'video/mp4');
                var title = document.title || 'Web Video';
                if (window.MediaSniffer) {
                    window.MediaSniffer.onMediaFound(src, mime, title);
                }
            }
        });

        // 2. Scan direct media and file links
        var links = document.querySelectorAll('a[href]');
        links.forEach(function(a) {
            var href = a.href;
            if (href && (/\.(mp4|webm|m3u8|mp3|m4a|wav|pdf|apk|zip|rar|tar|gz|docx|xlsx)(\?|$)/i).test(href)) {
                var label = a.innerText.trim() || document.title || 'File';
                var mime = 'application/octet-stream';
                if (href.indexOf('.mp4') !== -1) mime = 'video/mp4';
                else if (href.indexOf('.mp3') !== -1) mime = 'audio/mpeg';
                else if (href.indexOf('.pdf') !== -1) mime = 'application/pdf';
                else if (href.indexOf('.apk') !== -1) mime = 'application/vnd.android.package-archive';
                else if (href.indexOf('.zip') !== -1) mime = 'application/zip';
                if (window.MediaSniffer) {
                    window.MediaSniffer.onMediaFound(href, mime, label);
                }
            }
        });
    }

    scanMedia();

    if (document.body) {
        var observer = new MutationObserver(function() { scanMedia(); });
        observer.observe(document.body, { childList: true, subtree: true });
    }

    document.addEventListener('play', function(e) {
        if (e.target && (e.target.tagName === 'VIDEO' || e.target.tagName === 'AUDIO')) {
            var src = e.target.currentSrc || e.target.src;
            if (src && src.startsWith('http') && window.MediaSniffer) {
                var mime = e.target.type || 'video/mp4';
                window.MediaSniffer.onMediaFound(src, mime, document.title || 'Playing Media');
            }
        }
    }, true);
})();
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    viewModel: MainViewModel,
    contentPadding: PaddingValues
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current

    val browserUrl by viewModel.browserUrlInput.collectAsStateWithLifecycle()
    val progress by viewModel.browserProgress.collectAsStateWithLifecycle()
    val canGoBack by viewModel.canGoBack.collectAsStateWithLifecycle()
    val canGoForward by viewModel.canGoForward.collectAsStateWithLifecycle()
    val isDesktopMode by viewModel.isDesktopMode.collectAsStateWithLifecycle()
    val isIncognito by viewModel.isIncognito.collectAsStateWithLifecycle()
    val searchEngine by viewModel.searchEngine.collectAsStateWithLifecycle()
    val detectedMediaList by viewModel.detectedMediaList.collectAsStateWithLifecycle()
    val showDetectedMediaSheet by viewModel.showDetectedMediaSheet.collectAsStateWithLifecycle()
    val rescanTrigger by viewModel.rescanTrigger.collectAsStateWithLifecycle()
    val showTabManagerSheet by viewModel.showTabManagerSheet.collectAsStateWithLifecycle()
    val tabs by viewModel.browserTabs.collectAsStateWithLifecycle()
    val activeTabId by viewModel.activeTabId.collectAsStateWithLifecycle()

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var urlBarText by remember { mutableStateOf(browserUrl) }
    var isUrlBarFocused by remember { mutableStateOf(false) }

    var showChromeMenu by remember { mutableStateOf(false) }
    var showSearchEngineDialog by remember { mutableStateOf(false) }
    var isCurrentBookmarked by remember { mutableStateOf(false) }

    LaunchedEffect(browserUrl) {
        urlBarText = browserUrl
    }

    LaunchedEffect(rescanTrigger) {
        if (rescanTrigger > 0L) {
            webViewRef?.let { wv ->
                val currentWvUrl = wv.url ?: ""
                val lowerUrl = currentWvUrl.lowercase()
                val isDirectMediaOrFile = listOf(
                    ".mp4", ".webm", ".mkv", ".mp3", ".m4a", ".pdf", ".apk", ".zip", ".rar", "videoplayback"
                ).any { lowerUrl.contains(it) }
                if (isDirectMediaOrFile) {
                    val mime = when {
                        lowerUrl.contains(".mp3") || lowerUrl.contains(".m4a") -> "audio/mpeg"
                        lowerUrl.contains(".pdf") -> "application/pdf"
                        lowerUrl.contains(".apk") -> "application/vnd.android.package-archive"
                        lowerUrl.contains(".zip") -> "application/zip"
                        else -> "video/mp4"
                    }
                    val filename = URLUtil.guessFileName(currentWvUrl, null, mime)
                    viewModel.registerDetectedMedia(currentWvUrl, mime, filename)
                }
                wv.evaluateJavascript(MEDIA_SNIFFER_JS, null)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                webViewRef?.stopLoading()
                webViewRef?.destroy()
            } catch (_: Exception) {}
            webViewRef = null
        }
    }

    BackHandler(enabled = canGoBack) {
        webViewRef?.goBack()
    }

    // Display domain name or full query in Omnibox
    val displayHostOrTitle = remember(browserUrl, isUrlBarFocused) {
        if (isUrlBarFocused) {
            browserUrl
        } else {
            try {
                val uri = Uri.parse(browserUrl)
                val host = uri.host
                if (!host.isNullOrBlank()) {
                    host.removePrefix("www.").removePrefix("m.")
                } else {
                    browserUrl
                }
            } catch (_: Exception) {
                browserUrl
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .testTag("screen_browser")
    ) {
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenHeightPx = with(density) { maxHeight.toPx() }

        // Draggable floating download icon offsets
        var dragOffsetX by remember { mutableFloatStateOf(0f) }
        var dragOffsetY by remember { mutableFloatStateOf(0f) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
        ) {
            // ================= Chrome Top Toolbar =================
            val topBarBg = if (isIncognito) Color(0xFF1F1F1F) else Color(0xFFF1F3F4)
            val topBarTextColor = if (isIncognito) Color.White else Color(0xFF1F1F1F)
            val omniboxBg = if (isIncognito) Color(0xFF2E2E2E) else Color.White

            Surface(
                tonalElevation = 2.dp,
                color = topBarBg,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Chrome Home Icon Button
                        IconButton(
                            onClick = {
                                val home = MainViewModel.getSearchEngineHome(searchEngine)
                                viewModel.updateBrowserUrlInput(home)
                                webViewRef?.loadUrl(home)
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("chrome_home_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Home,
                                contentDescription = "Home",
                                tint = topBarTextColor
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // 2. Chrome Omnibox (Address Bar Pill)
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp),
                            shape = RoundedCornerShape(24.dp),
                            color = omniboxBg,
                            border = if (isUrlBarFocused) {
                                androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                            } else {
                                androidx.compose.foundation.BorderStroke(0.8.dp, Color(0xFFDADCE0))
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Security Lock or Search icon
                                val isHttps = urlBarText.startsWith("https://")
                                Icon(
                                    imageVector = if (isHttps) Icons.Default.Lock else Icons.Default.Search,
                                    contentDescription = if (isHttps) "Secure connection" else "Search",
                                    tint = if (isHttps) Color(0xFF1E8E3E) else Color(0xFF5F6368),
                                    modifier = Modifier.size(16.dp)
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                // Editable address / query text field
                                OutlinedTextField(
                                    value = if (isUrlBarFocused) urlBarText else displayHostOrTitle,
                                    onValueChange = {
                                        urlBarText = it
                                        isUrlBarFocused = true
                                    },
                                    placeholder = {
                                        Text(
                                            text = "Search or type URL",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                            color = Color(0xFF70757A)
                                        )
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("browser_url_input"),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                    keyboardActions = KeyboardActions(onGo = {
                                        focusManager.clearFocus()
                                        isUrlBarFocused = false
                                        val target = viewModel.buildSearchOrUrl(urlBarText)
                                        viewModel.updateBrowserUrlInput(target)
                                        webViewRef?.loadUrl(target)
                                    }),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedBorderColor = Color.Transparent,
                                        unfocusedBorderColor = Color.Transparent,
                                        focusedTextColor = if (isIncognito) Color.White else Color(0xFF202124),
                                        unfocusedTextColor = if (isIncognito) Color.White else Color(0xFF202124)
                                    ),
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)
                                )

                                // Clear or Refresh Button inside Omnibox
                                if (urlBarText.isNotBlank()) {
                                    IconButton(
                                        onClick = {
                                            if (isUrlBarFocused) {
                                                urlBarText = ""
                                            } else {
                                                webViewRef?.reload()
                                            }
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isUrlBarFocused) Icons.Default.Clear else Icons.Default.Refresh,
                                            contentDescription = if (isUrlBarFocused) "Clear" else "Reload",
                                            tint = Color(0xFF5F6368),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        // 3. Chrome Tab Switcher Button (Square with tab count)
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .border(
                                    width = 1.8.dp,
                                    color = topBarTextColor,
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .clickable { viewModel.toggleTabManager(true) }
                                .testTag("chrome_tab_switcher_btn"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (tabs.size > 9) "9+" else "${tabs.size}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = topBarTextColor
                                )
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // Chrome Top Bar Download Media Button with Badge
                        IconButton(
                            onClick = { viewModel.toggleDetectedMediaSheet(true) },
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("chrome_download_topbar_btn")
                        ) {
                            BadgedBox(
                                badge = {
                                    if (detectedMediaList.isNotEmpty()) {
                                        Badge(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.offset(x = (-2).dp, y = 2.dp)
                                        ) {
                                            Text(
                                                text = "${detectedMediaList.size}",
                                                color = Color.White,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Downloads & Detected Media",
                                    tint = if (detectedMediaList.isNotEmpty()) MaterialTheme.colorScheme.primary else topBarTextColor
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // 4. Chrome 3-Dots Menu Button
                        Box {
                            IconButton(
                                onClick = { showChromeMenu = true },
                                modifier = Modifier
                                    .size(40.dp)
                                    .testTag("chrome_3dots_menu_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Chrome Menu",
                                    tint = topBarTextColor
                                )
                            }

                            // Chrome Dropdown Menu
                            DropdownMenu(
                                expanded = showChromeMenu,
                                onDismissRequest = { showChromeMenu = false },
                                modifier = Modifier
                                    .width(260.dp)
                                    .testTag("chrome_dropdown_menu")
                            ) {
                                // Top quick navigation icons
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceAround,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = {
                                            webViewRef?.goForward()
                                            showChromeMenu = false
                                        },
                                        enabled = canGoForward
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = "Forward",
                                            tint = if (canGoForward) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            isCurrentBookmarked = !isCurrentBookmarked
                                            viewModel.addBookmark("Bookmark", browserUrl)
                                            showChromeMenu = false
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (isCurrentBookmarked) Icons.Default.Star else Icons.Default.BookmarkBorder,
                                            contentDescription = "Bookmark",
                                            tint = if (isCurrentBookmarked) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurface
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            showChromeMenu = false
                                            viewModel.navigateTo(Screen.DOWNLOADING)
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Download,
                                            contentDescription = "Downloads",
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            webViewRef?.reload()
                                            showChromeMenu = false
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "Reload",
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }

                                HorizontalDivider()

                                DropdownMenuItem(
                                    text = { Text("New tab") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Add, contentDescription = null)
                                    },
                                    onClick = {
                                        showChromeMenu = false
                                        val home = MainViewModel.getSearchEngineHome(searchEngine)
                                        viewModel.createNewTab(home)
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text(if (isIncognito) "Exit Incognito" else "New Incognito tab") },
                                    leadingIcon = {
                                        Icon(
                                            if (isIncognito) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        showChromeMenu = false
                                        viewModel.toggleIncognito()
                                    }
                                )

                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Search engine")
                                            Text(
                                                text = searchEngine,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Search, contentDescription = null)
                                    },
                                    onClick = {
                                        showChromeMenu = false
                                        showSearchEngineDialog = true
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("History") },
                                    leadingIcon = {
                                        Icon(Icons.Default.History, contentDescription = null)
                                    },
                                    onClick = {
                                        showChromeMenu = false
                                        viewModel.navigateTo(Screen.SETTINGS)
                                    }
                                )

                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Download page media")
                                            if (detectedMediaList.isNotEmpty()) {
                                                Text(
                                                    text = "${detectedMediaList.size}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Download, contentDescription = null)
                                    },
                                    onClick = {
                                        showChromeMenu = false
                                        viewModel.toggleDetectedMediaSheet(true)
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("Downloads") },
                                    leadingIcon = {
                                        Icon(Icons.Default.History, contentDescription = null)
                                    },
                                    onClick = {
                                        showChromeMenu = false
                                        viewModel.navigateTo(Screen.DOWNLOADING)
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("Share...") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Share, contentDescription = null)
                                    },
                                    onClick = {
                                        showChromeMenu = false
                                        try {
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, browserUrl)
                                            }
                                            context.startActivity(Intent.createChooser(shareIntent, "Share Link"))
                                        } catch (_: Exception) {}
                                    }
                                )

                                HorizontalDivider()

                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Desktop site")
                                            Checkbox(
                                                checked = isDesktopMode,
                                                onCheckedChange = null,
                                                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                                            )
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Computer, contentDescription = null)
                                    },
                                    onClick = {
                                        viewModel.toggleDesktopMode()
                                        webViewRef?.settings?.userAgentString = if (!isDesktopMode) {
                                            CHROME_DESKTOP_USER_AGENT
                                        } else {
                                            CHROME_MOBILE_USER_AGENT
                                        }
                                        webViewRef?.reload()
                                        showChromeMenu = false
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("Settings") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Settings, contentDescription = null)
                                    },
                                    onClick = {
                                        showChromeMenu = false
                                        viewModel.navigateTo(Screen.SETTINGS)
                                    }
                                )
                            }
                        }
                    }

                    // Chrome Horizontal Loading Progress Line
                    if (progress in 1..99) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.5.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.Transparent
                        )
                    }
                }
            }

            // ================= Chrome Android WebView Area =================
            // Background is explicitly Color.White so it NEVER renders pitch black!
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.White)
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewRef = this
                            // Use Software layer to avoid Mesa DRM rendernode search failure (E/MESA : Failed to open rendernode)
                            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                            // Explicitly set white canvas background to avoid black render glitch
                            setBackgroundColor(android.graphics.Color.WHITE)
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                loadWithOverviewMode = true
                                useWideViewPort = true
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                                mediaPlaybackRequiresUserGesture = false
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                cacheMode = WebSettings.LOAD_DEFAULT
                                userAgentString = if (isDesktopMode) {
                                    CHROME_DESKTOP_USER_AGENT
                                } else {
                                    CHROME_MOBILE_USER_AGENT
                                }
                            }

                            // JavaScript interface for media sniffing
                            class MediaSnifferInterface {
                                @JavascriptInterface
                                fun onMediaFound(url: String, mimeType: String, title: String?) {
                                    post {
                                        viewModel.registerDetectedMedia(url, mimeType, title)
                                    }
                                }
                            }
                            addJavascriptInterface(MediaSnifferInterface(), "MediaSniffer")

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    viewModel.onWebProgressChanged(newProgress)
                                }

                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    viewModel.onWebPageFinished(view?.url ?: "", title)
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val url = request?.url?.toString() ?: return false
                                    val lowerUrl = url.lowercase()

                                    // Catch direct media/file downloads clicked on pages
                                    val isDownloadableFile = listOf(
                                        ".mp4", ".webm", ".mkv", ".mp3", ".m4a", ".wav", ".pdf", ".apk",
                                        ".zip", ".rar", ".7z", ".tar", ".gz", ".docx", ".xlsx"
                                    ).any { lowerUrl.contains(it) } && !lowerUrl.contains(".html") && !lowerUrl.contains(".php")

                                    if (isDownloadableFile) {
                                        val guessedName = URLUtil.guessFileName(url, null, null)
                                        val mime = when {
                                            lowerUrl.contains(".mp3") || lowerUrl.contains(".m4a") -> "audio/mpeg"
                                            lowerUrl.contains(".pdf") -> "application/pdf"
                                            lowerUrl.contains(".apk") -> "application/vnd.android.package-archive"
                                            lowerUrl.contains(".zip") -> "application/zip"
                                            else -> "video/mp4"
                                        }
                                        viewModel.registerDetectedMedia(url, mime, guessedName)
                                        viewModel.toggleDetectedMediaSheet(true)
                                        return true
                                    }

                                    if (url.startsWith("http://") || url.startsWith("https://")) {
                                        return false
                                    }
                                    return try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        view?.context?.startActivity(intent)
                                        true
                                    } catch (_: Exception) {
                                        true
                                    }
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    url?.let { viewModel.onWebPageStarted(it) }
                                    viewModel.updateWebNavigationState(canGoBack(), canGoForward())
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    url?.let { viewModel.onWebPageFinished(it, view?.title) }
                                    viewModel.updateWebNavigationState(canGoBack(), canGoForward())

                                    // Inject script to discover video, audio, and downloadable media
                                    evaluateJavascript(MEDIA_SNIFFER_JS, null)
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    val reqUrl = request?.url?.toString() ?: ""
                                    val lower = reqUrl.lowercase()
                                    val isMediaStream = lower.contains(".mp4") || lower.contains(".webm") ||
                                        lower.contains(".m3u8") || lower.contains(".mp3") || lower.contains(".m4a") ||
                                        lower.contains(".aac") || lower.contains(".wav") || lower.contains(".ogg") ||
                                        lower.contains("videoplayback") || lower.contains("/video/") ||
                                        lower.contains("mime=video") || lower.contains("mime=audio") ||
                                        lower.contains(".flv") || lower.contains(".mkv")

                                    val isFileDownload = lower.contains(".pdf") || lower.contains(".apk") ||
                                        lower.contains(".zip") || lower.contains(".rar") || lower.contains(".tar") ||
                                        lower.contains(".gz") || lower.contains(".docx") || lower.contains(".xlsx")

                                    if (isMediaStream || isFileDownload) {
                                        val mime = when {
                                            lower.contains(".mp3") || lower.contains(".m4a") || lower.contains(".aac") || lower.contains("mime=audio") -> "audio/mpeg"
                                            lower.contains(".webm") -> "video/webm"
                                            lower.contains(".m3u8") -> "application/x-mpegURL"
                                            lower.contains(".pdf") -> "application/pdf"
                                            lower.contains(".apk") -> "application/vnd.android.package-archive"
                                            lower.contains(".zip") -> "application/zip"
                                            else -> "video/mp4"
                                        }
                                        val filename = try {
                                            URLUtil.guessFileName(reqUrl, null, mime)
                                        } catch (_: Exception) {
                                            null
                                        }
                                        post {
                                            viewModel.registerDetectedMedia(reqUrl, mime, filename ?: view?.title)
                                        }
                                    }
                                    return super.shouldInterceptRequest(view, request)
                                }

                                override fun onRenderProcessGone(
                                    view: WebView?,
                                    detail: RenderProcessGoneDetail?
                                ): Boolean {
                                    // Prevent host app crashes when Mesa or isolated renderer process exits
                                    view?.let { wv ->
                                        (wv.parent as? ViewGroup)?.removeView(wv)
                                        try {
                                            wv.destroy()
                                        } catch (_: Exception) {}
                                    }
                                    webViewRef = null
                                    return true
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    super.onReceivedError(view, request, error)
                                }
                            }

                            setDownloadListener { url, _, contentDisposition, mimetype, contentLength ->
                                val guessedName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                                val lower = url.lowercase()
                                val detectedMime = if (!mimetype.isNullOrBlank()) mimetype else when {
                                    lower.contains(".mp3") || lower.contains(".m4a") -> "audio/mpeg"
                                    lower.contains(".pdf") -> "application/pdf"
                                    lower.contains(".apk") -> "application/vnd.android.package-archive"
                                    lower.contains(".zip") -> "application/zip"
                                    else -> "video/mp4"
                                }
                                post {
                                    viewModel.registerDetectedMedia(
                                        url = url,
                                        mimeType = detectedMime,
                                        title = guessedName,
                                        contentLength = if (contentLength > 0) contentLength else 0L
                                    )
                                    viewModel.toggleDetectedMediaSheet(true)
                                }
                            }

                            val initialUrl = browserUrl.ifBlank { MainViewModel.getSearchEngineHome(searchEngine) }
                            loadUrl(initialUrl)
                        }
                    },
                    update = { webView ->
                        webViewRef = webView
                        val targetUrl = browserUrl.ifBlank { MainViewModel.getSearchEngineHome(searchEngine) }
                        if (webView.url != targetUrl && targetUrl.isNotBlank() && webView.url != null) {
                            webView.loadUrl(targetUrl)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // ================= FLEXIBLE DRAGGABLE & CLICKABLE DOWNLOAD CHIP =================
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = if (detectedMediaList.isNotEmpty()) 1.12f else 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(800),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse_scale"
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 24.dp, end = 20.dp)
                .offset {
                    IntOffset(
                        dragOffsetX.roundToInt(),
                        dragOffsetY.roundToInt()
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            viewModel.toggleDetectedMediaSheet(true)
                        }
                    )
                }
                .testTag("floating_sniffer_btn")
        ) {
            val hasMedia = detectedMediaList.isNotEmpty()

            BadgedBox(
                badge = {
                    if (hasMedia) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.error,
                            modifier = Modifier.scale(pulseScale)
                        ) {
                            Text(
                                text = "${detectedMediaList.size}",
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = if (hasMedia) MaterialTheme.colorScheme.primary else Color(0xFF1E293B),
                    shadowElevation = 10.dp,
                    border = androidx.compose.foundation.BorderStroke(
                        width = 1.2.dp,
                        color = if (hasMedia) Color.White.copy(alpha = 0.5f) else Color(0xFF475569)
                    ),
                    modifier = Modifier
                        .scale(if (hasMedia) pulseScale else 1f)
                        .clickable {
                            viewModel.toggleDetectedMediaSheet(true)
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable {
                                viewModel.toggleDetectedMediaSheet(true)
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        // Drag handle - drag this to move the button anywhere
                        Box(
                            modifier = Modifier
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        val newX = dragOffsetX + dragAmount.x
                                        val newY = dragOffsetY + dragAmount.y
                                        dragOffsetX = newX.coerceIn(-screenWidthPx + 150f, 20f)
                                        dragOffsetY = newY.coerceIn(-screenHeightPx + 200f, 20f)
                                    }
                                }
                                .padding(end = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DragIndicator,
                                contentDescription = "Drag to reposition",
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Download Media",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        Text(
                            text = if (hasMedia) "Download (${detectedMediaList.size})" else "Download",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // ================= Detected Media Sheet =================
        if (showDetectedMediaSheet) {
            DetectedMediaSheet(
                mediaList = detectedMediaList,
                onDismiss = { viewModel.toggleDetectedMediaSheet(false) },
                onDownload = { media ->
                    viewModel.downloadDetectedMedia(media)
                },
                onRescan = {
                    viewModel.triggerWebMediaRescan()
                }
            )
        }

        // ================= Chrome Tab Manager Sheet =================
        if (showTabManagerSheet) {
            TabManagerSheet(
                tabs = tabs,
                activeTabId = activeTabId,
                onSelectTab = { viewModel.selectTab(it) },
                onCloseTab = { viewModel.closeTab(it) },
                onNewTab = {
                    val home = MainViewModel.getSearchEngineHome(searchEngine)
                    viewModel.createNewTab(home)
                },
                onDismiss = { viewModel.toggleTabManager(false) }
            )
        }

        // ================= Expanded Search Engine Selection Dialog =================
        if (showSearchEngineDialog) {
            AlertDialog(
                onDismissRequest = { showSearchEngineDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Choose Search Engine",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Select your preferred default search provider:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        MainViewModel.SEARCH_ENGINES.forEach { (engineName, _) ->
                            val isSelected = searchEngine == engineName
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        viewModel.setSearchEngine(engineName)
                                        showSearchEngineDialog = false
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        viewModel.setSearchEngine(engineName)
                                        showSearchEngineDialog = false
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = engineName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSearchEngineDialog = false }) {
                        Text("Done")
                    }
                }
            )
        }
    }
}
