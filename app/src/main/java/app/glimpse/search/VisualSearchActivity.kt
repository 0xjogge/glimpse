@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.glimpse.search

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.glimpse.GlimpseApplication
import app.glimpse.core.requireSecureUrl
import app.glimpse.data.AppPreferences
import app.glimpse.ui.GlimpseTheme

/** Uses provider-owned upload pages instead of private upload APIs or a public image host. */
class VisualSearchActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var browserReady by mutableStateOf(false)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url =
            intent.getStringExtra("url")
                ?: run {
                    finish()
                    return
                }
        val crop =
            intent.getStringExtra("crop")?.let(Uri::parse)
                ?: run {
                    finish()
                    return
                }
        if (
            runCatching { requireSecureUrl(url) }.isFailure ||
                crop.authority != "$packageName.files"
        ) {
            finish()
            return
        }
        val name = intent.getStringExtra("provider") ?: "Visual search"
        enableEdgeToEdge()
        setContent {
            val prefs by
                (application as GlimpseApplication)
                    .graph
                    .preferences
                    .values
                    .collectAsStateWithLifecycle(AppPreferences())
            var progress by remember { mutableIntStateOf(0) }
            var error by remember { mutableStateOf<String?>(null) }
            var canBack by remember { mutableStateOf(false) }
            GlimpseTheme(prefs) {
                BackHandler(canBack) { webView?.goBack() }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(name) },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.Outlined.Close, "Close visual search")
                                }
                            },
                            actions = {
                                IconButton(
                                    onClick = {
                                        error = null
                                        webView?.reload()
                                    },
                                ) {
                                    Icon(Icons.Outlined.Refresh, "Reload")
                                }
                                IconButton(
                                    onClick = {
                                        runCatching {
                                            startActivity(
                                                Intent(
                                                    Intent.ACTION_VIEW,
                                                    Uri.parse(webView?.url ?: url),
                                                ),
                                            )
                                        }
                                    },
                                ) {
                                    Icon(Icons.Outlined.OpenInNew, "Open page in browser")
                                }
                            },
                        )
                    },
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(
                                "Your crop is ready. Tap the page's camera / image-upload button and choose Upload image. Glimpse supplies only your selected crop.",
                                Modifier.fillMaxWidth().padding(16.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (!browserReady) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else if (progress < 100)
                            LinearProgressIndicator(
                                progress = { progress / 100f },
                                Modifier.fillMaxWidth(),
                            )
                        error?.let { message ->
                            Text(
                                message,
                                Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(
                                onClick = {
                                    error = null
                                    webView?.loadUrl(url)
                                },
                            ) {
                                Text("Try again")
                            }
                        }
                        if (browserReady)
                            AndroidView(
                                factory = { context ->
                                    WebView(context).apply {
                                        webView = this
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.allowFileAccess = false
                                        settings.allowContentAccess = true
                                        settings.mixedContentMode =
                                            WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                        // Desktop upload controls are available without requiring
                                        // provider apps.
                                        settings.userAgentString =
                                            settings.userAgentString
                                                .replace("; wv", "")
                                                .replace("Android", "X11; Linux x86_64")
                                                .replace("Mobile", "")
                                        settings.useWideViewPort = true
                                        settings.loadWithOverviewMode = true
                                        CookieManager.getInstance()
                                            .setAcceptThirdPartyCookies(this, false)
                                        webChromeClient =
                                            object : WebChromeClient() {
                                                override fun onProgressChanged(
                                                    view: WebView?,
                                                    newProgress: Int,
                                                ) {
                                                    progress = newProgress
                                                    canBack = canGoBack()
                                                }

                                                override fun onShowFileChooser(
                                                    view: WebView?,
                                                    callback: ValueCallback<Array<Uri>>,
                                                    params: FileChooserParams?,
                                                ): Boolean {
                                                    // Return the crop asynchronously, matching
                                                    // ActivityResult and avoiding a re-entrant
                                                    // Chromium chooser callback.
                                                    view?.post {
                                                        callback.onReceiveValue(arrayOf(crop))
                                                    }
                                                    return true
                                                }
                                            }
                                        webViewClient =
                                            object : WebViewClient() {
                                                override fun shouldOverrideUrlLoading(
                                                    view: WebView?,
                                                    request: WebResourceRequest?,
                                                ): Boolean =
                                                    request?.url?.scheme !in
                                                        setOf("https", "about", "blob")

                                                override fun onReceivedError(
                                                    view: WebView?,
                                                    request: WebResourceRequest?,
                                                    failure: WebResourceError?,
                                                ) {
                                                    if (request?.isForMainFrame == true)
                                                        error =
                                                            "Could not load $name. Check your connection and try again."
                                                }
                                            }
                                        if (
                                            savedInstanceState == null ||
                                                restoreState(savedInstanceState) == null
                                        )
                                            loadUrl(url)
                                    }
                                },
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView?.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !browserReady)
            window.decorView.postOnAnimation {
                // A cold system WebView can take seconds to initialize. Complete the previous
                // window's focus handoff and draw this activity's shell before starting it.
                if (!isFinishing && !isDestroyed) browserReady = true
            }
    }

    override fun onDestroy() {
        webView?.stopLoading()
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
