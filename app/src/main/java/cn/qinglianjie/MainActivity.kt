package cn.qinglianjie

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import net.gotev.cookiestore.SharedPreferencesCookieStore
import okhttp3.*
import org.json.JSONObject
import org.json.JSONStringer
import java.io.IOException
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URLDecoder

const val DEBUG = true
const val INJECT_OBJECT_NAME = "AppFetcher"
const val INJECT_JS = """
window.dict = {}
window.callback = function(id, method, value) {
  window.dict[id][method](value);
  delete window.dict[id];
}
window.Fetcher = async (url, options) => {
return new Promise(function (resolve, reject) {
  const randomStr = (len) => {
    let str = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    let result = "";
    while(len) {
      let index = Math.floor(Math.random() * str.length);
      result += str[index];
      --len;
    }
    return result;
  }
  let id = randomStr(32);
  window.dict[id] = {
    resolve: resolve,
    reject: reject,
  };
  ${INJECT_OBJECT_NAME}.fetch(url, JSON.stringify(options), id)
});
};
"""

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 开启 WebView 远程调试
        if (DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        setContentView(R.layout.activity_main)
        this.webView = findViewById(R.id.webview)
        this.webView.webViewClient = WebViewClient(this.webView)
        this.webView.settings.javaScriptEnabled = true
        this.webView.addJavascriptInterface(WebAppInterface(this, this.webView), INJECT_OBJECT_NAME)
        this.webView.loadUrl("https://test.cors-with-cookie.qinglianjie.cn/fetch-test/index.html")
    }

    /**
     * 按下回退键时返回
     * @param keyCode Int
     * @param event KeyEvent
     * @return Boolean
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && this.webView.canGoBack()) {
            this.webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

private class WebViewClient(private val webview: WebView) : WebViewClient() {
    /**
     * 限制 WebView 可访问的 url
     * @param view WebView
     * @param url String
     * @return Boolean
     */
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        return false
//        if (Uri.parse(url).host == "qing-dev.dist.run") return false
//        Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
//            view?.context?.startActivity(this)
//        }
//        return true
    }

    /**
     * 页面加载时注入 window.Fetcher 对象
     * @param view WebView
     * @param url String
     * @param favicon Bitmap
     */
    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        this.webview.evaluateJavascript(INJECT_JS, null)
    }
}

/**
 * 用于向 WebView 中注入 fetch 方法
 * @property mContext Context
 * @property webview WebView
 * @property cookieManager CookieManager
 * @property client OkHttpClient
 * @constructor
 */
class WebAppInterface(private val mContext: Context, private val webview: WebView) {
    private val cookieManager = CookieManager(
        SharedPreferencesCookieStore(mContext, "qinglianjie"),
        CookiePolicy.ACCEPT_ALL
    )
    private val client = OkHttpClient.Builder()
        .cookieJar(JavaNetCookieJar(cookieManager))
        .build()

    /**
     * 注入到 WebView 中的方法，用于跨域获取数据，大致原理如下:
     *      1. 由该方法向 WebView 中注入 INJECT_OBJECT_NAME.fetch 方法
     *      2. window.Fetcher 中调用改方法并生成随机 callbackId
     *      3. window.Fetcher 将 Promise 的 resolve 和 reject 绑定在 window.dict[callbackId] 上
     *      4. 请求完成时向 WebView 注入 'window.dict[callbackId].resolve(result)' 回传结果
     * @param url String
     * @param jsonOptions String
     * @param callbackId String 随机生成的字符串，用于回调 Promise 的 resolve 或 reject
     */
    @JavascriptInterface
    fun fetch(url: String, jsonOptions: String, callbackId: String) {
        val options = JSONObject(jsonOptions)
        val method: String = options.getString("method")
        val headers: JSONObject = options.getJSONObject("headers")
        val request: Request = Request.Builder()
            .url(url)
            .method(method,
                if (method.lowercase() == "post") {
                    val form: String = options.getString("form")
                    buildFormData(form)
                } else { null }
            )
            .headers(buildHeaders(headers))
            .build()
        
        client.newCall(request).enqueue(object : Callback {
            /**
             * 请求成功时的回调函数
             * @param call Call
             * @param response Response
             */
            override fun onResponse(call: Call, response: Response) {
                val handler: Handler = Handler(mContext.mainLooper)
                if (response.isSuccessful) {
                    val bodyString = response.body!!.string()
                    // 用 JSONStringer 来转义字符，或许有更好的办法...？
                    val value = JSONStringer().array().value(bodyString).endArray()
                    response.close()
                    handler.post {
                        // 通过注入 callbackId 来回调 Promise.resolve，需要在主线程执行
                        webview.evaluateJavascript(
                            "window.callback('${callbackId}','resolve',${value}[0])",
                            null
                        )
                    }
                    Log.d("DEBUG", bodyString)
                } else {
                    val value = JSONStringer().`object`()
                        .key("status").value(response.code)
                        .key("message").value(response.message)
                        .endObject()
                    handler.post {
                        // 同上
                        webview.evaluateJavascript(
                            "window.callback('${callbackId}','reject',${value})",
                            null
                        )
                    }
                    Log.d("DEBUG", value.toString())
                }
            }

            /**
             * 请求失败时的回调函数
             * @param call Call
             * @param e IOException
             */
            override fun onFailure(call: Call, e: IOException) {
                val handler: Handler = Handler(mContext.mainLooper)
                val value = JSONStringer().`object`()
                    .key("status").value(400)
                    .key("message").value(e.toString())
                    .endObject()
                handler.post {
                    webview.evaluateJavascript(
                        "window.callback('${callbackId}','reject',${value})",
                        null
                    )
                }
                Log.d("ERROR", e.stackTraceToString())
            }
        })
    }

    /**
     * 将 "key1=value1&key2=value2" 的字符串转为表单
     * @param form String
     * @return RequestBody
     */
    private fun buildFormData(form: String): RequestBody {
        val builder = FormBody.Builder()
        form.split("&").forEach {
            val t = it.split("=")
            builder.add(t[0], URLDecoder.decode(if (t.size > 1) { t[1] } else { "" }))
        }
        return builder.build()
    }

    /**
     * 将 "{header1:value1, header2:value2}" 添加到 header
     * @param headers JSONObject
     * @return Headers
     */
    private fun buildHeaders(headers: JSONObject): Headers {
        val b = Headers.Builder()
        headers.keys().forEach {
            b.add(it, headers.getString(it))
        }
        return b.build()
    }
}


