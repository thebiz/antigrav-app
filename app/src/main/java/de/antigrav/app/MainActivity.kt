package de.antigrav.app

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageInstaller
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * AntiGrav: das Antigrav-Rennspiel als eigene App (früher ein Stil in VIZ).
 * Das Spiel ist eine HTML-Seite mit WebGL in einer WebView; Android liefert Vollbild, Vibration und Updates.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val HOST = "appassets.androidplatform.net"
        private const val START = "https://$HOST/assets/index.html"
    }

    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())
    private var pageReady = false
    private var immersiveOn = true
    private var pendingUpd = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        setContentView(web)
        maxRefresh()
        applyImmersive()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(function(){try{if(window.__agBack&&window.__agBack())return 'ok'}catch(e){}return 'no'})()") { r ->
                    if (r?.contains("ok") != true) finish()
                }
            }
        })

        val loader = WebViewAssetLoader.Builder().setDomain(HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web.isVerticalScrollBarEnabled = false
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            textZoom = 100
        }
        web.addJavascriptInterface(Bridge(), "AgNative")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == HOST) return false
                try { startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                deliver()
            }
        }
        handleIntent(intent)
        web.loadUrl(START)
    }

    // ---------------------------------------------------------------- Kürzel „Update“ vom App-Symbol

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == "de.antigrav.app.UPDATE") {
            pendingUpd = true
            if (pageReady) deliver()
        }
    }

    private fun deliver() {
        if (pendingUpd) { pendingUpd = false; main.postDelayed({ ev("updreq") }, 600) }
    }

    private fun js(code: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) web.evaluateJavascript(code, null)
        else main.post { web.evaluateJavascript(code, null) }
    }

    private fun ev(kind: String, data: String = "{}") = js("window.__nvEv&&window.__nvEv('$kind',$data)")

    // ---------------------------------------------------------------- Lebenszyklus

    /** höchste Bildwiederholrate anfordern (Poco X7 Pro: 120 Hz) – sonst drosselt Android WebView-Apps gern auf 60 */
    private fun maxRefresh() {
        try {
            val d = if (Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
            if (d == null) return
            val cur = d.mode
            val best = d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }.maxByOrNull { it.refreshRate } ?: return
            val lp = window.attributes
            lp.preferredDisplayModeId = best.modeId
            lp.preferredRefreshRate = best.refreshRate
            window.attributes = lp
        } catch (_: Throwable) {}
    }

    override fun onResume() {
        super.onResume()
        maxRefresh()
        if (pageReady) ev("resume")
    }

    override fun onPause() {
        if (pageReady) ev("pause")
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        web.destroy()
        super.onDestroy()
    }

    private fun applyImmersive() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (immersiveOn) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else c.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && immersiveOn) applyImmersive()
    }

    // ---------------------------------------------------------------- Brücke zur Seite

    inner class Bridge {
        @JavascriptInterface
        fun buildNr(): Int = try {
            if (Build.VERSION.SDK_INT >= 28) packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
            else @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0).versionCode
        } catch (_: Exception) { 0 }

        @JavascriptInterface
        fun immersive(on: Boolean) = main.post { immersiveOn = on; applyImmersive() }

        @JavascriptInterface
        fun exitApp() = main.post { finish() }

        @JavascriptInterface
        fun copyText(t: String) = main.post {
            try { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("AntiGrav", t)) } catch (_: Exception) {}
        }

        /** Vibration: einzelne Dauer oder Muster „an,aus,an,…“ in Millisekunden */
        @JavascriptInterface
        fun vibrate(pattern: String) = main.post {
            try {
                val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
                val p = pattern.split(',').mapNotNull { it.trim().toLongOrNull() }
                if (p.isEmpty()) return@post
                if (p.size == 1) v.vibrate(VibrationEffect.createOneShot(p[0].coerceIn(1, 1000), VibrationEffect.DEFAULT_AMPLITUDE))
                else v.vibrate(VibrationEffect.createWaveform(longArrayOf(0L) + p.toLongArray(), -1))
            } catch (_: Exception) {}
        }

        // --- Update: neueste APK aus dem GitHub-Repo holen und installieren
        @JavascriptInterface
        fun installUpdate(assetUrl: String, token: String) {
            if (!packageManager.canRequestPackageInstalls()) {
                main.post {
                    try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
                    ev("upd", "{\"e\":\"perm\"}")
                }
                return
            }
            Thread {
                try {
                    val f = File(cacheDir, "update.apk")
                    var c = URL(assetUrl).openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = false
                    if (token.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $token")
                    c.setRequestProperty("Accept", "application/octet-stream")
                    c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                    var code = c.responseCode
                    if (code in 300..399) {
                        val loc = c.getHeaderField("Location")
                        c.disconnect()
                        c = URL(loc).openConnection() as HttpURLConnection
                        code = c.responseCode
                    }
                    if (code != 200) throw Exception("HTTP $code")
                    val total = c.contentLengthLong
                    var done = 0L
                    var lastP = -1
                    c.inputStream.use { inp ->
                        FileOutputStream(f).use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val p = (done * 100 / total).toInt()
                                    if (p != lastP) { lastP = p; ev("upd", "{\"p\":$p}") }
                                }
                            }
                        }
                    }
                    c.disconnect()
                    val pi = packageManager.packageInstaller
                    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    params.setAppPackageName(packageName)
                    val sid = pi.createSession(params)
                    pi.openSession(sid).use { s ->
                        s.openWrite("antigrav.apk", 0, f.length()).use { out ->
                            f.inputStream().use { it.copyTo(out) }
                            s.fsync(out)
                        }
                        val pend = PendingIntent.getBroadcast(
                            this@MainActivity, sid,
                            Intent(this@MainActivity, InstallReceiver::class.java),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                        )
                        s.commit(pend.intentSender)
                    }
                    ev("upd", "{\"p\":100,\"ok\":true}")
                } catch (e: Exception) {
                    ev("upd", "{\"e\":${JSONObject.quote(e.message ?: e.javaClass.simpleName)}}")
                }
            }.start()
        }
    }
}
