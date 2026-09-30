package com.ckapp.checkin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.app.AlertDialog
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var root: android.widget.FrameLayout
    private lateinit var webView: WebView
    private lateinit var pairing: LinearLayout
    private lateinit var pairingStore: PairingStore
    private lateinit var bridge: CKAppBridge
    private lateinit var cameraExecutor: ExecutorService

    // M5 / C4：父母 PIN 门禁 + 原生设置页
    private lateinit var pinStore: PinStore
    private lateinit var btnSettings: Button
    private lateinit var settingsPanel: LinearLayout
    private lateinit var btnPinSet: Button
    private lateinit var btnPinClear: Button
    private lateinit var swKeepScreen: Switch
    private lateinit var swBattery: Switch
    private lateinit var tvAbout: TextView
    private lateinit var btnExit: Button
    private lateinit var btnSettingsClose: Button

    /** 当前 App 形态：smallestScreenWidthDp >= 600 视为 Pad（横屏），否则手机（直屏）。 */
    private val platform: String by lazy {
        if (resources.configuration.smallestScreenWidthDp >= 600) "pad" else "phone"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private val CAMERA_PERM_CODE = 1001

    /** 接收 SyncService 转发的 SSE 推送，交给页面 window.CKApp.onPush。 */
    private val pushReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val json = i?.getStringExtra("json") ?: return
            val js = "try{if(window.CKApp&&typeof window.CKApp.onPush==='function'){window.CKApp.onPush($json);}}catch(e){}"
            webView.post { webView.evaluateJavascript(js, null) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersive()

        // 形态方向锁定：手机=固定竖屏，Pad=固定横屏，都不允许旋转。
        // 屏幕方向是 Activity 的原生属性，网页无法控制；放在 setContentView 之前避免闪一下错误方向。
        requestedOrientation = if (platform == "pad") {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE   // 0：固定横屏（不含反向）
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT    // 1：固定竖屏（不含反向）
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        pairingStore = PairingStore(this)

        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        pairing = findViewById(R.id.pairing)
        // WebView 必须在 setContentView 后 findViewById 取得
        webView = findViewById(R.id.webView)
        bridge = CKAppBridge(this, webView, platform, BuildConfig.VERSION_NAME)

        // M5 / C4：父母门禁 + 设置页
        pinStore = PinStore(this)
        btnSettings = findViewById(R.id.btnSettings)
        settingsPanel = findViewById(R.id.settings)
        btnPinSet = findViewById(R.id.btnPinSet)
        btnPinClear = findViewById(R.id.btnPinClear)
        swKeepScreen = findViewById(R.id.swKeepScreen)
        swBattery = findViewById(R.id.swBattery)
        tvAbout = findViewById(R.id.tvAbout)
        btnExit = findViewById(R.id.btnExit)
        btnSettingsClose = findViewById(R.id.btnSettingsClose)
        btnSettings.setOnClickListener { openSettings() }
        btnPinSet.setOnClickListener { onPinSetClicked() }
        btnPinClear.setOnClickListener { onPinClearClicked() }
        swKeepScreen.setOnCheckedChangeListener { _, on -> applyKeepScreen(on) }
        swBattery.setOnCheckedChangeListener { _, on -> if (on) requestBatteryExemption() else toast("如需恢复电池优化请到系统设置") }
        btnExit.setOnClickListener { onExitClicked() }
        btnSettingsClose.setOnClickListener { closeSettings() }

        ContextCompat.registerReceiver(
            this, pushReceiver,
            IntentFilter(SyncService.ACTION_SYNC_PUSH),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        if (pairingStore.isPaired) openApp() else showPairing()

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersive()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(pushReceiver) }
        cameraExecutor.shutdown()
        webView.destroy()
        super.onDestroy()
    }

    // ---- 沉浸式全屏（无地址栏 / 系统栏，IMMERSIVE_STICKY） ----
    private fun enableImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    // ---- 配对意图（ckapp:// 深链 / http(s) 二维码链接） ----
    private fun handleIntent(intent: Intent?) {
        val uri = when {
            intent?.scheme == "ckapp" -> intent.data
            intent?.action == Intent.ACTION_VIEW && intent.data != null -> intent.data
            else -> null
        }
        if (uri != null && pairingStore.parseIntent(uri) && pairingStore.isPaired) {
            openApp()
        }
    }

    // ---- 未配对：展示扫码 / 手动配对浮层 ----
    private fun showPairing() {
        webView.visibility = View.GONE
        pairing.visibility = View.VISIBLE

        val etBase = findViewById<EditText>(R.id.etBase)
        val etToken = findViewById<EditText>(R.id.etToken)
        val etServer = findViewById<EditText>(R.id.etServerId)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnScan = findViewById<Button>(R.id.btnScan)
        val hint = findViewById<TextView>(R.id.pairHint)

        btnSave.setOnClickListener {
            val b = etBase.text.toString().trim()
            val t = etToken.text.toString().trim()
            val s = etServer.text.toString().trim()
            if (b.isNotEmpty() && t.isNotEmpty() && s.isNotEmpty()) {
                pairingStore.save(b, t, s)
                openApp()
            } else {
                hint.text = "三项均需填写"
                hint.setTextColor(Color.RED)
            }
        }
        btnScan.setOnClickListener { startScan() }
    }

    // ---- 已配对：加载入口网页 ----
    private fun openApp() {
        pairing.visibility = View.GONE
        webView.visibility = View.VISIBLE
        btnSettings.visibility = View.VISIBLE
        setupWebView()
        val url = pairingStore.entryUrl(platform)
        if (url.isNotEmpty()) webView.loadUrl(url)
    }

    private fun setupWebView() {
        val settings: WebSettings = webView.settings
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 三层禁用缩放之一：原生 WebView 缩放控件
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            useWideViewPort = false
            loadWithOverviewMode = true
            // 服务器走局域网 http，允许混合内容
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            // WebView 安全硬化（F 组发布阻断项）：禁文件/内容越权访问，防网页读本地存储
            allowFileAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            allowContentAccess = false
        }
        webView.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY

        val baseHost = runCatching { Uri.parse(pairingStore.baseUrl).host }.getOrNull()
        webView.webViewClient = object : WebViewClient() {
            // 锁定地址：仅允许同源导航，跨站一律拦截（防网页跳走）
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                if (u.scheme == "ckapp") return false // 深链交给系统
                return !isSameOrigin(u, baseHost)
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                injectCkApp() // 尽早注入，争取早于 phone.js init
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectCkApp()
            }
        }
        // JS Bridge：原生方法绑定在 CKAppBridge（不被 window.CKApp 覆盖），由 injectCkApp 委托
        webView.addJavascriptInterface(bridge, "CKAppBridge")
        // 启动前台同步（M4 SSE 保活）
        startSyncService()
    }

    /**
     * 注入 window.CKApp 探测标志（契约 §1 / §3）。
     * 关键：不能让 window.CKApp = {...} 覆盖 addJavascriptInterface 绑定的桥对象，
     * 故桥方法绑定在 window.CKAppBridge，这里只把 getDraft/setDraft/clearDraft 委托到它，
     * 同时保留页面可能已设置的 window.CKApp.onPush。
     */
    private fun injectCkApp() {
        val js = """
        (function(){
          if (typeof CKAppBridge === 'undefined') return;
          window.CKApp = window.CKApp || {};
          window.CKApp.isApp = true;
          window.CKApp.platform = '$platform';
          window.CKApp.version = '${BuildConfig.VERSION_NAME}';
          window.CKApp.getDraft = function(k){ return CKAppBridge.getDraft(k); };
          window.CKApp.setDraft = function(k,j){ CKAppBridge.setDraft(k,j); };
          window.CKApp.clearDraft = function(k){ CKAppBridge.clearDraft(k); };
        })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun isSameOrigin(uri: Uri, baseHost: String?): Boolean {
        if (baseHost == null) return true
        return uri.host == baseHost
    }

    private fun startSyncService() {
        val intent = Intent(this, SyncService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    // ---- 返回键：退后台而非退出，保护进行中计时（R4） ----
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (settingsPanel.visibility == View.VISIBLE) { closeSettings(); return }
        if (webView.visibility == View.VISIBLE && webView.canGoBack()) {
            webView.goBack()
        } else {
            moveTaskToBack(true)
        }
    }

    // ---- 扫码配对（M3，CameraX + ML Kit） ----
    private fun startScan() {
        // 运行时申请相机权限（manifest 仅声明，Android 6+ 需动态授权）
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERM_CODE)
            return
        }
        val preview = findViewById<PreviewView>(R.id.preview)
        preview.visibility = View.VISIBLE
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            val previewBuilder = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { imageProxy -> processBarcode(imageProxy) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewBuilder, analysis)
            } catch (_: Exception) { }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun processBarcode(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) { imageProxy.close(); return }
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        BarcodeScanning.getClient().process(input)
            .addOnSuccessListener { barcodes ->
                for (b in barcodes) {
                    val raw = b.rawValue ?: b.displayValue ?: continue
                    val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: continue
                    if (pairingStore.parseIntent(uri) && pairingStore.isPaired) {
                        cameraProvider?.unbindAll()
                        findViewById<PreviewView>(R.id.preview).visibility = View.GONE
                        openApp()
                        break
                    }
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == CAMERA_PERM_CODE && res.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startScan()
        }
    }

    // ---- M5 / C4：父母 PIN 门禁 + 原生设置页 ----
    private fun openSettings() {
        if (pinStore.hasPin()) {
            promptPin("输入父母 PIN 以打开设置") { pin ->
                if (pinStore.verify(pin)) showSettingsOverlay() else toast("PIN 错误")
            }
        } else {
            showSettingsOverlay()
        }
    }

    private fun showSettingsOverlay() {
        btnSettings.visibility = View.GONE
        settingsPanel.visibility = View.VISIBLE
        val has = pinStore.hasPin()
        btnPinSet.text = if (has) "修改 父母门禁 PIN" else "设置 父母门禁 PIN"
        btnPinClear.isEnabled = has
        tvAbout.text = "三端打卡 ${BuildConfig.VERSION_NAME} · ${if (platform == "pad") "Pad" else "手机"}"
        swKeepScreen.isChecked = (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        val pm = getSystemService(PowerManager::class.java)
        swBattery.isChecked = pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun closeSettings() {
        settingsPanel.visibility = View.GONE
        if (webView.visibility == View.VISIBLE) btnSettings.visibility = View.VISIBLE
    }

    private fun onPinSetClicked() {
        if (pinStore.hasPin()) {
            promptPin("输入当前 PIN") { cur ->
                if (!pinStore.verify(cur)) { toast("PIN 错误"); return@promptPin }
                promptPin("设置新 PIN（至少 4 位）") { new -> pinStore.setPin(new); toast("已更新"); showSettingsOverlay() }
            }
        } else {
            promptPin("设置父母门禁 PIN（至少 4 位）") { new -> pinStore.setPin(new); toast("已设置"); showSettingsOverlay() }
        }
    }

    private fun onPinClearClicked() {
        promptPin("输入 PIN 以清除") { cur ->
            if (pinStore.verify(cur)) { pinStore.clearPin(); toast("已清除"); showSettingsOverlay() }
            else toast("PIN 错误")
        }
    }

    private fun onExitClicked() {
        if (pinStore.hasPin()) {
            promptPin("输入父母 PIN 以退出") { pin ->
                if (pinStore.verify(pin)) finishAffinity() else toast("PIN 错误")
            }
        } else {
            finishAffinity()
        }
    }

    private fun promptPin(title: String, onOk: (String) -> Unit) {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "请输入父母 PIN"
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(et)
            .setPositiveButton("确定") { _, _ ->
                val pin = et.text.toString()
                if (pin.length >= 4) onOk(pin) else toast("PIN 至少 4 位")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyKeepScreen(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun requestBatteryExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$packageName"))
        try { startActivity(intent) } catch (_: Exception) { toast("无法打开电池设置") }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
