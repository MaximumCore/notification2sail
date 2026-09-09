package com.notification2sail.app

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.firebase.messaging.FirebaseMessaging
import com.notification2sail.app.BuildConfig
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var webView: WebView
    private lateinit var btnSubscribe: Button
    private lateinit var monitorListContainer: LinearLayout
    private lateinit var cornerMenuButton: FrameLayout
    private lateinit var mainContent: View

    private val serverUrl = BuildConfig.SERVER_URL.ifEmpty { "https://notification-2-sail--MaximumCore.replit.app" }
    private lateinit var apiClient: RegattaApiClient
    private lateinit var deviceId: String
    private var vibrator: Vibrator? = null

    private var fcmToken: String? = null
    private var isUpdatingSettings = false

    private var gestureNavigationEnabled = true
    private var drawerOnRightSide = false

    private var regattasCardExpanded = false
    private var settingsCardExpanded = false

    private var prefDetails = true
    private var prefClasses = true
    private var prefEntries = true
    private var prefResults = true
    private var prefNoticeBoard = true

    private var touchX1 = 0f
    private var touchY1 = 0f
    private val minSwipeDistance = 150

    private var titleClickCount = 0
    private var lastTitleClickTime = 0L

    private var currentMonitors: List<Regatta> = emptyList()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) fetchFcmToken()
    }

    private fun getCleanBaseUrl(url: String): String = url.split("#!")[0]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Initialize our new external components
        apiClient = RegattaApiClient(serverUrl)
        deviceId = DeviceUuidManager.getDeviceId(this)
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VibratorManager::class.java)
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        val prefs = getSharedPreferences("UiSettings", MODE_PRIVATE)
        gestureNavigationEnabled = prefs.getBoolean("gestureNav", true)
        drawerOnRightSide = prefs.getBoolean("drawerRight", false)

        window.statusBarColor = Color.parseColor("#121212")

        drawerLayout = findViewById(R.id.drawerLayout)
        drawerLayout.setScrimColor(Color.parseColor("#80000000"))

        mainContent = findViewById(R.id.mainContent)
        webView = findViewById(R.id.webview)
        btnSubscribe = findViewById(R.id.btnSubscribe)
        monitorListContainer = findViewById(R.id.monitorListContainer)
        monitorListContainer.setBackgroundColor(Color.TRANSPARENT)

        setupWebViewConfiguration()
        setupCornerTriggerButton()
        setupDrawerAnimationListener()
        applyDrawerConfiguration()
        askNotificationPermission()
        handleNotificationIntent(intent)
        setupOnBackPressedDispatcher()

        rebuildMaterialDrawerUi(emptyList())

        btnSubscribe.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val currentUrl = webView.url ?: return@setOnClickListener
            val token = fcmToken
            if (token == null) {
                setButtonError("NO TOKEN")
                fetchFcmToken()
                return@setOnClickListener
            }

            if (btnSubscribe.text.toString() == "REGATTA SUBSCRIBED") {
                openNavigationDrawer()
            } else {
                setButtonLoading()

                var pageTitle = webView.title ?: ""
                pageTitle = pageTitle.replace(" - manage2sail", "").replace("manage2sail", "").trim()
                if (pageTitle.isEmpty()) pageTitle = extractRegattaName(getCleanBaseUrl(currentUrl))

                // Call via our external network client
                apiClient.executeSubscribe(currentUrl, token, deviceId, pageTitle) { success, errorMsg ->
                    if (success) {
                        setButtonSubscribed()
                        regattasCardExpanded = true
                        loadUserSettings()
                        openNavigationDrawer()
                    } else {
                        setButtonError(errorMsg ?: "ERROR")
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val targetUrl = intent?.getStringExtra("target_url")
        val targetTab = intent?.getStringExtra("target_tab") // Extracted tab name ("results", "entries" etc.)

        if (!targetUrl.isNullOrEmpty()) {
            // Deep-linking option: If we have a targetTab, we append it to the URL
            val finalUrl = if (!targetTab.isNullOrEmpty() && !targetUrl.contains("#!")) {
                "$targetUrl#!$targetTab" // e.g. https://manage2sail.com/.../event#!results
            } else {
                targetUrl
            }
            webView.loadUrl(finalUrl)
        } else if (webView.url == null) {
            webView.loadUrl("https://www.manage2sail.com")
        }
    }

    private fun setupOnBackPressedDispatcher() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    drawerLayout.isDrawerOpen(GravityCompat.START) -> drawerLayout.closeDrawer(GravityCompat.START)
                    drawerLayout.isDrawerOpen(GravityCompat.END) -> drawerLayout.closeDrawer(GravityCompat.END)
                    webView.canGoBack() -> webView.goBack()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebViewConfiguration() {
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isHorizontalScrollBarEnabled = false
        
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                
                // Inject CSS/Meta to prevent horizontal scrolling
                view?.loadUrl("javascript:(function() { " +
                        "var meta = document.createElement('meta');" +
                        "meta.name = 'viewport';" +
                        "meta.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';" +
                        "var head = document.getElementsByTagName('head')[0];" +
                        "if (head) head.appendChild(meta);" +
                        "document.body.style.overflowX = 'hidden';" +
                        "document.documentElement.style.overflowX = 'hidden';" +
                        "})()")

                if (url != null && url.contains("manage2sail.com/") && url.contains("/event/")) {
                    btnSubscribe.isVisible = true
                    checkSubscriptionStatus(url)
                } else {
                    btnSubscribe.isVisible = false
                }
            }
        }
    }

    private fun setupCornerTriggerButton() {
        val rootView = findViewById<ViewGroup>(android.R.id.content)
        val darkBg = Color.parseColor("#121212")
        val accentBlue = Color.parseColor("#00B4D8")

        cornerMenuButton = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(140, 140).apply {
                gravity = if (drawerOnRightSide) Gravity.TOP or Gravity.END else Gravity.TOP or Gravity.START
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(darkBg)
                val r = 64f
                cornerRadii = if (!drawerOnRightSide) 
                    floatArrayOf(0f, 0f, 0f, 0f, r, r, 0f, 0f) 
                    else floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, r, r)
            }
            elevation = 0f

            addView(TextView(this@MainActivity).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER }
                text = "☰"
                textSize = 26f
                setTextColor(accentBlue)
            })
            setOnClickListener { 
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                openNavigationDrawer() 
            }
        }
        rootView.addView(cornerMenuButton)
    }

    private fun setupDrawerAnimationListener() {
        var lastSlideOffset = 0f
        drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                val direction = if (drawerOnRightSide) -1 else 1
                cornerMenuButton.translationX = drawerView.width * slideOffset * direction

                // Mechanical feel: Tick every 20% of progress
                if (Math.abs(slideOffset - lastSlideOffset) > 0.2f) {
                    drawerView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    lastSlideOffset = slideOffset
                }

                // Dynamic Alpha for Frosted Glass Effect (0.45 when closed, 0.88 when open)
                val alphaPercent = 0.45f + (0.43f * slideOffset)
                drawerView.background?.mutate()?.alpha = (alphaPercent * 255).toInt()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (slideOffset > 0.005f) {
                        val blurRadius = slideOffset * 25f
                        mainContent.setRenderEffect(RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP))
                    } else {
                        mainContent.setRenderEffect(null)
                    }
                }
            }
            override fun onDrawerOpened(drawerView: View) {
                drawerView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                drawerView.background?.mutate()?.alpha = (0.88f * 255).toInt()
            }
            override fun onDrawerClosed(drawerView: View) {
                cornerMenuButton.translationX = 0f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) mainContent.setRenderEffect(null)
                drawerView.background?.mutate()?.alpha = (0.45f * 255).toInt()
            }
            override fun onDrawerStateChanged(newState: Int) {}
        })
    }

    private fun applyDrawerConfiguration() {
        val navView = findViewById<View>(R.id.navigationView)
        val darkBg = Color.parseColor("#121212")
        val radius = 80f // Stronger rounding for the sheet

        navView.background = GradientDrawable().apply {
            setColor(darkBg)
            alpha = (0.45f * 255).toInt()
            // Make the corner where the button is (Top-Start or Top-End) sharp to avoid gaps
            if (drawerOnRightSide) {
                cornerRadii = floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
            } else {
                cornerRadii = floatArrayOf(0f, 0f, radius, radius, radius, radius, 0f, 0f)
            }
        }

        for (i in 0 until drawerLayout.childCount) {
            val child = drawerLayout.getChildAt(i)
            val params = child.layoutParams
            if (params is DrawerLayout.LayoutParams) {
                if (params.gravity == GravityCompat.START || 
                    params.gravity == GravityCompat.END || 
                    params.gravity == Gravity.START || params.gravity == Gravity.END) {
                    params.gravity = if (drawerOnRightSide) GravityCompat.END else GravityCompat.START
                    child.layoutParams = params
                    break
                }
            }
        }
        drawerLayout.setDrawerLockMode(if (!gestureNavigationEnabled) DrawerLayout.LOCK_MODE_LOCKED_CLOSED else DrawerLayout.LOCK_MODE_UNLOCKED)
        val btnParams = cornerMenuButton.layoutParams as FrameLayout.LayoutParams
        btnParams.gravity = if (drawerOnRightSide) Gravity.TOP or Gravity.END else Gravity.TOP or Gravity.START
        cornerMenuButton.layoutParams = btnParams

        val shape = cornerMenuButton.background as GradientDrawable
        val r = 64f
        shape.cornerRadii = if (!drawerOnRightSide) 
            floatArrayOf(0f, 0f, 0f, 0f, r, r, 0f, 0f) 
            else floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, r, r)
    }

    private fun openNavigationDrawer() { drawerLayout.openDrawer(if (drawerOnRightSide) GravityCompat.END else GravityCompat.START) }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!gestureNavigationEnabled) return super.dispatchTouchEvent(ev)
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> { touchX1 = ev.x; touchY1 = ev.y }
            MotionEvent.ACTION_UP -> {
                val deltaX = ev.x - touchX1
                val deltaY = ev.y - touchY1
                if (abs(deltaX) > minSwipeDistance && abs(deltaX) > abs(deltaY) * 2) {
                    if (drawerOnRightSide && deltaX < 0 && !drawerLayout.isDrawerOpen(GravityCompat.END)) { drawerLayout.openDrawer(GravityCompat.END); return true }
                    else if (!drawerOnRightSide && deltaX > 0 && !drawerLayout.isDrawerOpen(GravityCompat.START)) { drawerLayout.openDrawer(GravityCompat.START); return true }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun triggerFastTransition(root: ViewGroup) {
        val fastTransition = AutoTransition().apply { duration = 150L }
        TransitionManager.beginDelayedTransition(root, fastTransition)
    }

    private fun updateMainButtonAppearance(textStr: String, bgColor: String, textColor: String, isEnabledBtn: Boolean) {
        btnSubscribe.text = textStr
        btnSubscribe.isEnabled = isEnabledBtn
        btnSubscribe.setTextColor(Color.parseColor(textColor))
        btnSubscribe.isAllCaps = false
        btnSubscribe.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

        if (btnSubscribe is MaterialButton) {
            (btnSubscribe as MaterialButton).apply {
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(bgColor))
                cornerRadius = 64
            }
        } else {
            btnSubscribe.background = GradientDrawable().apply {
                cornerRadius = 64f
                setColor(Color.parseColor(bgColor))
            }
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else fetchFcmToken()
        } else fetchFcmToken()
    }

    private fun fetchFcmToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val newToken = task.result
                if (fcmToken != null && fcmToken != newToken) {
                    // Token changed, update server with existing urls
                    fcmToken = newToken
                    apiClient.executeRegister(newToken, deviceId, currentMonitors.map { it.url }) { _, _ -> }
                } else {
                    fcmToken = newToken
                }
                loadUserSettings()
            }
        }
    }

    private fun extractRegattaName(url: String): String {
        return try {
            val parts = url.split("/event/")
            if (parts.size > 1) {
                val rawName = parts[1].split("#")[0].split("?")[0].replace("/", "")
                val decoded = java.net.URLDecoder.decode(rawName, "UTF-8").trim()
                if (decoded.length > 30) "Regatta Details" else decoded
            } else "Regatta"
        } catch (_: Exception) { "Regatta" }
    }

    private fun isRegattaActiveToday(dateString: String?): Boolean {
        if (dateString.isNullOrEmpty() || dateString == "null") return false
        return try {
            val parts = dateString.split("-")
            val format = SimpleDateFormat("dd/MM/yyyy", Locale.UK)
            val now = Calendar.getInstance()

            if (parts.size == 2) {
                val start = format.parse(parts[0].trim()) ?: return false
                val end = format.parse(parts[1].trim()) ?: return false

                // The end date should include the entire day
                val endLimit = Calendar.getInstance().apply {
                    time = end
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59)
                    set(Calendar.MILLISECOND, 999)
                }.time

                val currentTime = Calendar.getInstance().time
                currentTime.after(start) && currentTime.before(endLimit)
            } else {
                val singleDate = format.parse(dateString.trim()) ?: return false
                val cal = Calendar.getInstance().apply { time = singleDate }
                now.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
                        now.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)
            }
        } catch (_: Exception) { false }
    }

    private fun formatRegattaDate(startIso: String, endIso: String): String {
        if (startIso.isEmpty() && endIso.isEmpty()) return ""

        val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val outputFormat = SimpleDateFormat("dd/MM/yyyy", Locale.UK)

        fun parseDate(iso: String): Date? {
            return try {
                inputFormat.parse(iso)
            } catch (_: Exception) {
                // Try parsing as dd/MM/yyyy if it's already in that format
                try { outputFormat.parse(iso) } catch (_: Exception) { null }
            }
        }

        val start = parseDate(startIso)
        val end = parseDate(endIso)

        return when {
            start != null && end != null -> "${outputFormat.format(start)} - ${outputFormat.format(end)}"
            start != null -> outputFormat.format(start)
            end != null -> outputFormat.format(end)
            else -> startIso.ifEmpty { endIso }
        }
    }

    private fun checkSubscriptionStatus(url: String) {
        val token = fcmToken ?: return
        apiClient.checkSubscriptionStatus(url, token, deviceId) { isSubscribed ->
            if (isSubscribed) setButtonSubscribed() else setButtonNotSubscribed()
        }
    }

    private fun executeUnsubscribe(url: String) {
        val token = fcmToken ?: return
        apiClient.executeUnsubscribe(url, token, deviceId) {
            loadUserSettings()
            setButtonNotSubscribed()
        }
    }

    private fun loadUserSettings() {
        val token = fcmToken ?: return
        apiClient.loadUserSettings(token, deviceId, onResult = { monitors, settings ->
            try {
                currentMonitors = monitors
                if (settings != null) {
                    prefDetails = settings.optBoolean("details", true)
                    prefClasses = settings.optBoolean("classes", true)
                    prefEntries = settings.optBoolean("entries", true)
                    prefResults = settings.optBoolean("results", true)
                    prefNoticeBoard = settings.optBoolean("noticeBoard", true)
                }

                isUpdatingSettings = true
                rebuildMaterialDrawerUi(monitors)
                isUpdatingSettings = false
            } catch (e: Exception) {
                Log.e("BugHunt", "JSON crash during loading: ${e.message}")
            }
        }, onFailure = {
            // Keep existing currentMonitors on failure
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun rebuildMaterialDrawerUi(monitors: List<Regatta>) {
        try {
            monitorListContainer.removeAllViews()
            monitorListContainer.setPadding(32, 140, 32, 32) // Top padding matches button height

            val titleApp = TextView(this).apply {
                text = "notification2sail"
                textSize = 22f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#00B4D8"))
                setPadding(8, 0, 0, 32)
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastTitleClickTime < 500) {
                        titleClickCount++
                    } else {
                        titleClickCount = 1
                    }
                    lastTitleClickTime = currentTime

                    if (titleClickCount == 7) {
                        titleClickCount = 0
                        val token = fcmToken
                        if (token != null) {
                            Toast.makeText(this@MainActivity, "Test notification in 60 seconds", Toast.LENGTH_LONG).show()
                            apiClient.executeServerDelayedPush(token) { }
                        }
                    }
                }
            }
            monitorListContainer.addView(titleApp)

            val cardBackgroundDark = Color.parseColor("#1A2332")
            val accentBlue = Color.parseColor("#00B4D8")
            val whiteText = Color.parseColor("#DDDDDD")
            val grayText = Color.parseColor("#555555")

            fun createTextToggle(textName: String, isChecked: Boolean, onToggle: (Boolean) -> Unit): TextView {
                return TextView(this@MainActivity).apply {
                    text = textName
                    textSize = 15f
                    setPadding(0, 16, 0, 16)
                    var state = isChecked
                    setTextColor(if (state) accentBlue else grayText)
                    setTypeface(null, if (state) Typeface.BOLD else Typeface.NORMAL)
                    setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        state = !state
                        setTextColor(if (state) accentBlue else grayText)
                        setTypeface(null, if (state) Typeface.BOLD else Typeface.NORMAL)
                        onToggle(state)
                    }
                }
            }

            val cardRegattas = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
                background = GradientDrawable().apply { cornerRadius = 48f; setColor(cardBackgroundDark) }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 32) }
            }

            val headerRegattasLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(8, 8, 8, 8)
            }

            val txtHeaderRegattas = TextView(this).apply {
                text = "Regattas"
                textSize = 17f
                setTypeface(null, Typeface.BOLD)
                setTextColor(accentBlue)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            headerRegattasLayout.addView(txtHeaderRegattas)
            cardRegattas.addView(headerRegattasLayout)

            val bodyRegattasLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                this.isVisible = regattasCardExpanded
                setPadding(0, 16, 0, 0)
            }

            headerRegattasLayout.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                triggerFastTransition(monitorListContainer)
                regattasCardExpanded = !regattasCardExpanded
                bodyRegattasLayout.isVisible = regattasCardExpanded
            }

            if (monitors.isEmpty()) {
                bodyRegattasLayout.addView(TextView(this).apply {
                    text = "No active subscriptions."
                    textSize = 13f
                    setTextColor(Color.parseColor("#64748B"))
                    setPadding(8, 12, 0, 0)
                })
            } else {
                val currentWebUrl = getCleanBaseUrl(webView.url ?: "")
                for (monitor in monitors) {
                    val regattaUrl = monitor.url
                    val startDate = monitor.startDate ?: ""
                    val endDate = monitor.endDate ?: ""
                    val dateLegacy = monitor.dateLegacy ?: ""

                    val displayDate = if (startDate.isNotEmpty() || endDate.isNotEmpty()) {
                        formatRegattaDate(startDate, endDate)
                    } else if (dateLegacy.isNotEmpty() && dateLegacy != "null") {
                        dateLegacy
                    } else {
                        ""
                    }

                    var displayName = monitor.name ?: ""
                    if (displayName.isEmpty() || displayName == "null") displayName = extractRegattaName(regattaUrl)

                    val isLiveNow = isRegattaActiveToday(displayDate)

                    val itemContainer = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(24, 24, 24, 24)
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 12, 0, 12) }
                        background = GradientDrawable().apply { cornerRadius = 36f; setColor(if (isLiveNow) Color.parseColor("#1E3A2F") else Color.parseColor("#222A35")) }
                    }

                    val layoutDropdown = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(16, 8, 16, 8)
                        isVisible = currentWebUrl.contains(regattaUrl)
                    }

                    val txtRegatta = TextView(this).apply {
                        text = displayName
                        textSize = 15f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(if (isLiveNow) Color.parseColor("#4EDD99") else whiteText)
                    }

                    val txtRegattaDate = TextView(this).apply {
                        text = displayDate.ifEmpty { "No date provided" }
                        textSize = 12f
                        setTextColor(if (isLiveNow) Color.parseColor("#8CE0B6") else Color.parseColor("#94A3B8"))
                        setPadding(0, 4, 0, 0)
                    }

                    itemContainer.setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        webView.loadUrl(regattaUrl)
                        triggerFastTransition(monitorListContainer)
                        layoutDropdown.isVisible = !layoutDropdown.isVisible
                    }

                    val btnDeleteContainer = FrameLayout(this).apply {
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 110).apply { setMargins(0, 16, 0, 0) }
                        background = GradientDrawable().apply { cornerRadius = 32f; setColor(Color.parseColor("#2A1F2D")) }
                    }

                    val progressView = View(this).apply {
                        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        background = GradientDrawable().apply { cornerRadius = 32f; setColor(Color.parseColor("#E63946")) }
                        pivotX = 0f
                        scaleX = 0f
                    }

                    val txtDelete = TextView(this).apply {
                        text = "Hold to Remove"
                        setTextColor(Color.parseColor("#FF8A8A"))
                        textSize = 13f
                        setTypeface(null, Typeface.BOLD)
                        gravity = Gravity.CENTER
                        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }

                    btnDeleteContainer.addView(progressView)
                    btnDeleteContainer.addView(txtDelete)

                    var holdAnimator: ValueAnimator? = null
                    var isTouchCancelled = false

                    btnDeleteContainer.setOnTouchListener { v, event ->
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                isTouchCancelled = false
                                txtDelete.text = "Deleting..."
                                txtDelete.setTextColor(Color.WHITE)
                                holdAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                                    duration = 1000
                                    addUpdateListener { anim -> 
                                        val progress = anim.animatedValue as Float
                                        progressView.scaleX = progress
                                        
                                        // Increasing vibration intensity
                                        val amplitude = (progress * 255).toInt().coerceAtLeast(1)
                                        vibrator?.vibrate(VibrationEffect.createOneShot(20, amplitude))
                                    }
                                    addListener(object : AnimatorListenerAdapter() {
                                        override fun onAnimationEnd(animation: Animator) {
                                            if (!isTouchCancelled && progressView.scaleX >= 0.95f) {
                                                val haptic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
                                                v.performHapticFeedback(haptic)
                                                executeUnsubscribe(regattaUrl)
                                            }
                                        }
                                    })
                                    start()
                                }
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                isTouchCancelled = true
                                holdAnimator?.cancel()
                                progressView.scaleX = 0f
                                txtDelete.text = "Hold to Remove"
                                txtDelete.setTextColor(Color.parseColor("#FF8A8A"))
                                true
                            }
                            else -> false
                        }
                    }

                    layoutDropdown.addView(btnDeleteContainer)
                    itemContainer.addView(txtRegatta)
                    itemContainer.addView(txtRegattaDate)
                    itemContainer.addView(layoutDropdown)
                    bodyRegattasLayout.addView(itemContainer)
                }
            }
            cardRegattas.addView(bodyRegattasLayout)
            monitorListContainer.addView(cardRegattas)

            val cardSettings = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
                background = GradientDrawable().apply { cornerRadius = 48f; setColor(cardBackgroundDark) }
            }

            val headerSettingsLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(8, 8, 8, 8)
            }

            val txtHeaderSettings = TextView(this).apply {
                text = "Settings"
                textSize = 17f
                setTypeface(null, Typeface.BOLD)
                setTextColor(accentBlue)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            headerSettingsLayout.addView(txtHeaderSettings)
            cardSettings.addView(headerSettingsLayout)

            val bodySettingsLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                this.isVisible = settingsCardExpanded
                setPadding(16, 16, 16, 0)
            }

            headerSettingsLayout.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                triggerFastTransition(monitorListContainer)
                settingsCardExpanded = !settingsCardExpanded
                bodySettingsLayout.isVisible = settingsCardExpanded
            }

            bodySettingsLayout.addView(TextView(this).apply {
                text = "NOTIFICATION CATEGORIES"
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#64748B"))
                setPadding(0, 0, 0, 8)
            })

            val togDet = createTextToggle("Details", prefDetails) { state -> prefDetails = state; saveUserSettings() }
            val togCla = createTextToggle("Classes", prefClasses) { state -> prefClasses = state; saveUserSettings() }
            val togEnt = createTextToggle("Entries", prefEntries) { state -> prefEntries = state; saveUserSettings() }
            val togRes = createTextToggle("Results", prefResults) { state -> prefResults = state; saveUserSettings() }
            val togNot = createTextToggle("Notice Board", prefNoticeBoard) { state -> prefNoticeBoard = state; saveUserSettings() }

            bodySettingsLayout.addView(togDet)
            bodySettingsLayout.addView(togCla)
            bodySettingsLayout.addView(togEnt)
            bodySettingsLayout.addView(togRes)
            bodySettingsLayout.addView(togNot)

            bodySettingsLayout.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2).apply { setMargins(0, 16, 0, 16) }
                setBackgroundColor(Color.parseColor("#334155"))
            })

            val switchGestures = MaterialSwitch(this).apply {
                text = "Gesture Navigation"
                setTextColor(whiteText)
                isChecked = gestureNavigationEnabled
                thumbTintList = android.content.res.ColorStateList.valueOf(accentBlue)
                trackTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#334155"))
                setPadding(0, 16, 0, 16)
                setOnCheckedChangeListener { buttonView, isChecked1 ->
                    buttonView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    gestureNavigationEnabled = isChecked1
                    getSharedPreferences("UiSettings", MODE_PRIVATE).edit { putBoolean("gestureNav", isChecked1) }
                    applyDrawerConfiguration()
                }
            }
            bodySettingsLayout.addView(switchGestures)

            val layoutPositionToggle = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 16, 0, 16)
                gravity = Gravity.CENTER_VERTICAL
            }
            val txtSideLabel = TextView(this).apply {
                text = if (drawerOnRightSide) "Menu Side: Right" else "Menu Side: Left"
                setTextColor(whiteText)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val switchSide = MaterialSwitch(this).apply {
                isChecked = drawerOnRightSide
                thumbTintList = android.content.res.ColorStateList.valueOf(accentBlue)
                trackTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#334155"))
                setOnCheckedChangeListener { buttonView, isChecked1 ->
                    buttonView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    drawerOnRightSide = isChecked1
                    txtSideLabel.text = if (isChecked1) "Menu Side: Right" else "Menu Side: Left"
                    getSharedPreferences("UiSettings", MODE_PRIVATE).edit { putBoolean("drawerRight", isChecked1) }
                    applyDrawerConfiguration()
                }
            }
            layoutPositionToggle.addView(txtSideLabel)
            layoutPositionToggle.addView(switchSide)
            bodySettingsLayout.addView(layoutPositionToggle)

            cardSettings.addView(bodySettingsLayout)
            monitorListContainer.addView(cardSettings)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveUserSettings() {
        val token = fcmToken ?: return
        if (isUpdatingSettings) return
        apiClient.saveUserSettings(token, deviceId, prefDetails, prefClasses, prefEntries, prefResults, prefNoticeBoard)
    }

    private fun setButtonLoading() = updateMainButtonAppearance("LOADING...", "#334155", "#FFFFFF", false)
    private fun setButtonSubscribed() = updateMainButtonAppearance("REGATTA SUBSCRIBED", "#1B4332", "#A3E635", true)
    private fun setButtonNotSubscribed() = updateMainButtonAppearance("SUBSCRIBE REGATTA", "#00B4D8", "#121212", true)

    private fun setButtonError(errorMessage: String) {
        updateMainButtonAppearance(errorMessage, "#7A1C24", "#FFFFFF", true)
        Handler(Looper.getMainLooper()).postDelayed({ setButtonNotSubscribed() }, 3000)
    }
}