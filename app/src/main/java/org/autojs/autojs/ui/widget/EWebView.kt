package org.autojs.autojs.ui.widget

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.text.InputFilter
import android.util.AttributeSet
import android.util.Log
import android.webkit.*
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.gson.Gson
import com.stardust.app.OnActivityResultDelegate
import com.stardust.app.OnActivityResultDelegate.DelegateHost
import com.stardust.autojs.execution.ScriptExecution
import com.stardust.autojs.runtime.api.Files
import com.stardust.autojs.runtime.api.SevenZip
import com.stardust.autojs.script.StringScriptSource
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import org.autojs.autojs.Pref
import org.autojs.autojs.R
import org.autojs.autojs.model.script.Scripts
import org.autojs.autojs.tool.ImageSelector
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * Created by Stardust on 2017/8/22.
 */
open class EWebView : FrameLayout, SwipeRefreshLayout.OnRefreshListener, OnActivityResultDelegate {
    private lateinit var mWebView: WebView
    private lateinit var mProgressBar: ProgressBar
    private lateinit var mSwipeRefreshLayout: SwipeRefreshLayout
    private lateinit var downloadManagerUtil: DownloadManagerUtil
    private lateinit var mWebData: WebData
    val gson = Gson()

    companion object {
        private var downloadId = 0L
        private var isRescale = false
        private var isConsole = false
        private var isTbs = true
        private val IMAGE_TYPES = listOf("png", "jpg", "bmp")
        private const val CHOOSE_IMAGE = 42222
    }


    constructor(context: Context?) : super(context!!) {
        init()
    }

    constructor(context: Context?, attrs: AttributeSet?) : super(
        context!!, attrs
    ) {
        init()
    }

    private fun init() {
        inflate(context, R.layout.ewebview, this)
        mWebView = findViewById(R.id.web_view);
        mSwipeRefreshLayout = findViewById(R.id.swipe_refresh_layout)
        mProgressBar = findViewById(R.id.progress_bar)
        mSwipeRefreshLayout.setOnRefreshListener(this)
        downloadManagerUtil = DownloadManagerUtil(context)
        if (Pref.getWebData().contains("isTbs")) {
            mWebData = gson.fromJson(
                Pref.getWebData(),
                WebData::class.java
            )
        } else {
            mWebData = WebData()
            Pref.setWebData(gson.toJson(mWebData))
        }
        setIsTbs(mWebData.isTbs)
        webInit(mWebView)
    }

    private fun webInit(mWebView: WebView) {
        with(mWebView.settings) {
            javaScriptEnabled = true  //设置支持Javascript交互
            javaScriptCanOpenWindowsAutomatically = true //支持通过JS打开新窗口
            allowFileAccess = true //设置可以访问文件
            setAllowFileAccessFromFileURLs(true);
            setAllowUniversalAccessFromFileURLs(true);
            allowContentAccess = true;
            defaultTextEncodingName = "utf-8"//设置编码格式
            setSupportMultipleWindows(false)
            layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
            setSupportZoom(true) //支持缩放，默认为true。是下面那个的前提。
            builtInZoomControls = true //设置内置的缩放控件。若为false，则该WebView不可缩放
            displayZoomControls = false //设置原生的缩放控件，启用时被leakcanary检测到内存泄露
            useWideViewPort = true //让WebView读取网页设置的viewport，pc版网页
            loadWithOverviewMode = false
            loadsImagesAutomatically = true //设置自动加载图片
            blockNetworkImage = false
            blockNetworkLoads = false;
            setNeedInitialFocus(true);
            saveFormData = true;
            cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK //使用缓存
            setAppCacheEnabled(true);
            domStorageEnabled = true
            databaseEnabled = true   //开启 database storage API 功能
            pluginState = WebSettings.PluginState.ON
            if (Build.VERSION.SDK_INT >= 26) {
                safeBrowsingEnabled = false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                mediaPlaybackRequiresUserGesture = false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // 5.0以上允许加载http和https混合的页面(5.0以下默认允许，5.0+默认禁止)
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW;
            }
        }
        mWebView.webViewClient = MyWebViewClient()
        mWebView.webChromeClient = MyWebChromeClient()
        mWebView.setDownloadListener { url: String, userAgent: String, contentDisposition: String, mimeType: String, contentLength: Long ->
            run {
                val fileName: String = android.webkit.URLUtil.guessFileName(
                    url, contentDisposition,
                    mimeType
                )
                //先移除上一个下载任务，防止重复下载

                if (downloadId != 0L) {
                    downloadManagerUtil.clearCurrentTask(downloadId)
                }
                downloadId = downloadManagerUtil.download(url, fileName, fileName)
                Toast.makeText(context, "正在后台下载：$fileName", Toast.LENGTH_LONG)
                    .show()
            }
        }
        mWebView.addJavascriptInterface(this, "_autojs")
        mWebView.addJavascriptInterface(Files(null), "\$files")
        mWebView.addJavascriptInterface(SevenZip(), "\$zips")
    }

    fun evalJavaScript(script: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            mWebView.evaluateJavascript(script, null)
        } else {
            mWebView.loadUrl("javascript:$script")
        }
    }


    @JavascriptInterface
    fun saveSource(content: String?, fileName: String?) {
        if (content != null) {
            if (!File(context.getExternalFilesDir(null)!!.path).isDirectory) {
                File(context.getExternalFilesDir(null)!!.path).mkdirs()
            }
            File(
                context.getExternalFilesDir(null)!!.path,
                "$fileName.txt"
            ).writeText(
                content,
                Charset.defaultCharset()
            )
        }
    }

    @SuppressLint("CheckResult")
    override fun onRefresh() {
        mSwipeRefreshLayout!!.isRefreshing = false
        mWebView!!.reload()
        Observable.timer(2, TimeUnit.SECONDS)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { t: Long? -> mSwipeRefreshLayout!!.isRefreshing = false }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {}

    protected open inner class MyWebViewClient() : WebViewClient() {
        override fun onLoadResource(view: WebView, url: String?) {
            super.onLoadResource(view, url)
        }

        override fun onPageStarted(
            view: WebView,
            url: String,
            favicon: Bitmap?
        ) {
            mWebData = if (Pref.getWebData().contains("isTbs")) {
                gson.fromJson(Pref.getWebData(), WebData::class.java)
            } else {
                WebData()
            }
            Pref.setWebData(gson.toJson(mWebData))
            with(mWebView.settings) {
                userAgentString = mWebData?.userAgent
            }
            super.onPageStarted(view, url, favicon)
            mProgressBar.progress = 0
            mProgressBar.visibility = VISIBLE
            if (getIsConsole()) {
                var jsCode =
                    "javascript: " + readAssetsTxt(context, "modules/vconsole.min.js")
                Log.i("onPageStarted", jsCode)
                view.evaluateJavascript(
                    jsCode,
                    com.tencent.smtt.sdk.ValueCallback<String> {
                        Log.i("evaluateJavascript", "JS　return:  $it")
                    })
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            view.settings.blockNetworkImage = false
            super.onPageFinished(view, url)
            mProgressBar.visibility = GONE
            mSwipeRefreshLayout.isRefreshing = false
            view.evaluateJavascript(
                "javascript: window._autojs.saveSource('<html>' + document.getElementsByTagName('html')[0].innerHTML + '</html>', 'html_source');",
                null
            )
            if (isRescale) {
                var jsCode =
                    "javascript: " + readAssetsTxt(context, "modules/rescale.js")
                view.evaluateJavascript(
                    jsCode,
                    ValueCallback<String> {
                        Log.i("evaluateJavascript", "JS　return:  $it")
                    })
            }
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            return shouldOverrideUrlLoading(view, request.url.toString())
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            url: String
        ): Boolean {

            if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://")) {
                view.loadUrl(url)
            } else if (url.indexOf("mobile_web") < 0) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
            return true
        }
    }

    protected open inner class MyWebChromeClient : WebChromeClient() {
        //设置响应js 的Alert()函数
        override fun onJsAlert(
            p0: WebView?,
            url: String?,
            message: String?,
            result: JsResult?
        ): Boolean {
            val b: android.app.AlertDialog.Builder =
                android.app.AlertDialog.Builder(context)
            b.setTitle("Alert")
            b.setMessage(message)
            b.setPositiveButton(
                R.string.ok,
                DialogInterface.OnClickListener { _, _ -> result?.confirm() })
            b.setCancelable(false)
            b.create().show()
            return true
        }

        //设置响应js 的Confirm()函数
        override fun onJsConfirm(
            p0: WebView?,
            url: String?,
            message: String?,
            result: JsResult?
        ): Boolean {
            val b: android.app.AlertDialog.Builder =
                android.app.AlertDialog.Builder(context)
            b.setTitle("Confirm")
            b.setMessage(message)
            b.setPositiveButton(
                R.string.ok,
                DialogInterface.OnClickListener { _, _ -> result?.confirm() })
            b.setNegativeButton(
                R.string.cancel,
                DialogInterface.OnClickListener { _, _ -> result?.cancel() })
            b.create().show()
            return true
        }

        //设置响应js 的Prompt()函数
        override fun onJsPrompt(
            p0: WebView?,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: JsPromptResult
        ): Boolean {
            val b: android.app.AlertDialog.Builder =
                android.app.AlertDialog.Builder(context)
            val inputServer = EditText(context)
            inputServer.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(255))
            inputServer.setText(defaultValue)
            b.setTitle("Prompt")
            b.setMessage(message)
            b.setView(inputServer)
            b.setPositiveButton(
                R.string.ok,
                DialogInterface.OnClickListener { _, _ ->
                    val value = inputServer.text.toString()
                    result.confirm(value)
                })
            b.setNegativeButton(
                R.string.cancel,
                DialogInterface.OnClickListener { _, _ -> result.cancel() })
            b.create().show()
            return true
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            mProgressBar.progress = newProgress
        }

        //For Android  >= 4.1
        fun openFileChooser(uploadFile: ValueCallback<Uri>, acceptType: String, capture: String) {
            if (acceptType == null) {
                openFileChooser(uploadFile, acceptType)
            } else {
                openFileChooser(uploadFile, acceptType.split(",").toTypedArray())
            }
        }

        open fun openFileChooser(
            valueCallback: ValueCallback<Uri>,
            acceptType: Array<String>
        ): Boolean {
            if (context is DelegateHost &&
                context is Activity && isImageType(acceptType)
            ) {
                chooseImage(valueCallback)
                return true
            }
            return false
        }
    }

    private fun chooseImage(valueCallback: ValueCallback<Uri>) {
        val delegateHost = context as DelegateHost
        val mediator = delegateHost.onActivityResultDelegateMediator
        val activity = context as Activity
        ImageSelector(
            activity,
            mediator
        ) { selector: ImageSelector?, uri: Uri? -> valueCallback.onReceiveValue(uri) }
            .disposable()
            .select()
    }

    private fun isImageType(acceptTypes: Array<String>?): Boolean {
        if (acceptTypes == null) {
            return false
        }
        for (acceptType in acceptTypes) {
            for (imageType in IMAGE_TYPES) {
                if (acceptType.contains(imageType)) {
                    return true
                }
            }
        }
        return false
    }

    fun getWebView(): WebView {
        return mWebView
    }

    fun getIsRescale(): Boolean {
        return isRescale
    }

    fun switchRescale() {
        isRescale = !isRescale;
    }

    fun getIsConsole(): Boolean {
        return isConsole
    }

    fun switchConsole() {
        isConsole = !isConsole;
    }

    fun getIsTbs(): Boolean {
        return isTbs
    }

    fun setIsTbs(flag: Boolean) {
        isTbs = flag
    }

    fun getSwipeRefreshLayout(): SwipeRefreshLayout {
        return mSwipeRefreshLayout
    }

    private var execution: ScriptExecution? = null

    @JavascriptInterface
    fun run(code: String?, name: String?): String {
        stop(execution)
        execution = Scripts.run(StringScriptSource(name, code))
        return if (execution == null) "Fail! Code: " + code.toString() else "Success! Code: " + code.toString()
    }

    @JavascriptInterface
    fun run(code: String?): String {
        stop(execution)
        execution = Scripts.run(StringScriptSource("", code))
        return if (execution == null) "Fail! Code: " + code.toString() else "Success! Code: " + code.toString()
    }

    @JavascriptInterface
    fun stop(execution: ScriptExecution?) {
        execution?.engine?.forceStop()
    }

    fun readAssetsTxt(context: Context, fileName: String): String? {
        try {
            //Return an AssetManager instance for your application's package
            val `is`: InputStream = context.assets.open("$fileName")
            val size: Int = `is`.available()

            val buffer = ByteArray(size)
            `is`.read(buffer)
            `is`.close()
            return String(buffer, Charsets.UTF_8)
        } catch (e: IOException) {
            e.message?.let { Log.e("", it) }
        }
        return "读取错误，请检查文件名"
    }

}