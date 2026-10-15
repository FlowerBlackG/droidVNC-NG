package net.christianbeier.droidvnc_ng.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import net.christianbeier.droidvnc_ng.R

/** Embedded browsing is retained for TVs without a browser. Compose owns the screen chrome. */
class WebViewActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var loading by mutableStateOf(true)
    private var canGoBack by mutableStateOf(false)

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    loading = true
                    canGoBack = view.canGoBack()
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    loading = false
                    canGoBack = view.canGoBack()
                }
            }
            if (savedInstanceState?.getBundle("webView")?.let { restoreState(it) } == null) loadUrl(url)
        }
        setContent {
            VncTheme {
                BackHandler(enabled = canGoBack) { webView.goBack() }
                Scaffold(topBar = {
                    TopAppBar(title = { Text(intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name)) }, navigationIcon = {
                        BackNavigationButton(onClick = { finish() })
                    })
                }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) outState.putBundle("webView", Bundle().also { webView.saveState(it) })
    }
    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }
    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }
    override fun onDestroy() {
        if (::webView.isInitialized) {
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}
