package io.github.hatake716.sekaishiearth.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** アプリ内ブラウザ。Wikipedia と世界史の窓の記事を表示する。 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebScreen(url: String, title: String, onClose: () -> Unit) {
    val context = LocalContext.current
    var progress by remember { mutableFloatStateOf(0f) }
    var pageTitle by remember { mutableStateOf(title) }
    var canGoBack by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        val w = webView
        if (w != null && w.canGoBack()) w.goBack() else onClose()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(pageTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = { val w = webView; if (w != null && w.canGoBack()) w.goBack() else onClose() }) {
                        Icon(if (canGoBack) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Close, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { webView?.reload() }) { Icon(Icons.Default.Refresh, contentDescription = "再読み込み") }
                    IconButton(onClick = {
                        val current = webView?.url ?: url
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(current))) }
                    }) { Icon(Icons.Default.OpenInBrowser, contentDescription = "ブラウザで開く") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (progress in 0.01f..0.99f) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            setBackgroundColor(0xFF0B0F1A.toInt())
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val u = request.url
                                    // http(s) はアプリ内で開く。それ以外(mailto 等)は外部へ
                                    return if (u.scheme == "http" || u.scheme == "https") false else {
                                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, u)) }
                                        true
                                    }
                                }

                                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                    error = null
                                    canGoBack = view.canGoBack()
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    progress = 1f
                                    canGoBack = view.canGoBack()
                                    view.title?.takeIf { it.isNotBlank() }?.let { pageTitle = it }
                                }

                                override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
                                    if (request.isForMainFrame) error = err.description?.toString() ?: "読み込みエラー"
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    progress = newProgress / 100f
                                }

                                override fun onReceivedTitle(view: WebView, t: String?) {
                                    if (!t.isNullOrBlank()) pageTitle = t
                                }
                            }
                            loadUrl(url)
                            webView = this
                        }
                    },
                )
                val err = error
                if (err != null) {
                    Column(
                        Modifier.align(Alignment.Center).background(MaterialTheme.colorScheme.surface).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("ページを読み込めませんでした", style = MaterialTheme.typography.titleMedium)
                        Text("インターネット接続を確認してください\n$err", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
                        Button(onClick = { error = null; webView?.reload() }) { Text("再試行") }
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                destroy()
            }
            webView = null
        }
    }
}
