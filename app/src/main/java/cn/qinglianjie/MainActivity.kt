package cn.qinglianjie

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity


class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        this.webView = findViewById(R.id.webview)
        this.webView.webViewClient = MyWebViewClient()
        this.webView.settings.javaScriptEnabled = true
        this.webView.loadUrl("https://qing-dev.dist.run")
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && this.webView.canGoBack()) {
            this.webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

private class MyWebViewClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        if (Uri.parse(url).host == "qing-dev.dist.run") return false
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            view?.context?.startActivity(this)
        }
        return true
    }
}

