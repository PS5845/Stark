package com.example.starkphone

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.os.Build
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.webkit.*
import android.widget.Button
import android.widget.FrameLayout
import androidx.webkit.WebViewAssetLoader
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var ui: WebView
    private var web: WebView? = null
    private var closeBtn: Button? = null

    private fun js(code: String) = runOnUiThread { ui.evaluateJavascript(code, null) }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { js("lock()") }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(screenOff) } catch (e: Throwable) {}
    }
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        // 本体にPIN/パターン等のロックが無い時だけ、ロック画面の上に表示する(本体のロックを迂回しないため)
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure) {
            if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
            else @Suppress("DEPRECATION") window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        root = FrameLayout(this)
        setContentView(root)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        ui = WebView(this).apply {
            setBackgroundColor(0xFF0C1418.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest) =
                    loader.shouldInterceptRequest(r.url)
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(r: PermissionRequest) {
                    runOnUiThread { r.grant(r.resources) }
                }
            }
            addJavascriptInterface(Bridge(), "Android")
            loadUrl("https://appassets.androidplatform.net/assets/index.html")
        }
        root.addView(ui, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val need = arrayOf(Manifest.permission.CAMERA, Manifest.permission.CALL_PHONE)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        closeWeb()
        ui.evaluateJavascript("go('home')", null)
    }

    @Deprecated("launcher: ignore back")
    override fun onBackPressed() {
        if (web != null) closeWeb() else ui.evaluateJavascript("go('home')", null)
    }

    private fun showWeb(u: String) {
        if (web == null) {
            web = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
            }
            root.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            closeBtn = Button(this).apply {
                text = "✕ 閉じる"
                setOnClickListener { closeWeb() }
            }
            root.addView(closeBtn, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END))
        }
        web!!.loadUrl(u)
    }

    private fun closeWeb() {
        web?.let { root.removeView(it); it.destroy() }
        closeBtn?.let { root.removeView(it) }
        web = null; closeBtn = null
    }

    private fun iconB64(d: Drawable): String {
        val s = 72
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, s, s); d.draw(Canvas(bmp))
        val o = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 90, o)
        return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)
    }

    inner class Bridge {
        @JavascriptInterface
        fun apps(): String {
            val pm = packageManager
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val arr = JSONArray()
            pm.queryIntentActivities(i, 0)
                .filter { it.activityInfo.packageName != packageName }
                .sortedBy { it.loadLabel(pm).toString().lowercase() }
                .forEach { ri ->
                    arr.put(JSONObject().apply {
                        put("n", ri.loadLabel(pm).toString())
                        put("p", ri.activityInfo.packageName)
                        put("i", iconB64(ri.loadIcon(pm)))
                    })
                }
            return arr.toString()
        }

        @JavascriptInterface
        fun launch(pkg: String) {
            runOnUiThread { packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) } }
        }

        @JavascriptInterface
        fun dial(num: String) {
            runOnUiThread {
                val ok = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
                startActivity(Intent(if (ok) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(num))))
            }
        }

        @JavascriptInterface
        fun scan(b64: String) {
            Thread {
                try {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
                        .process(InputImage.fromBitmap(bmp, 0))
                        .addOnSuccessListener { ls ->
                            val arr = JSONArray()
                            ls.take(6).forEach { arr.put(JSONObject().put("t", it.text).put("c", it.confidence.toDouble())) }
                            js("scanResult(" + arr.toString() + ")")
                        }
                        .addOnFailureListener { js("scanResult([])") }
                } catch (e: Throwable) { js("scanResult([])") }
            }.start()
        }

        @JavascriptInterface
        fun openWeb(url: String) { runOnUiThread { showWeb(url) } }
    }
}
