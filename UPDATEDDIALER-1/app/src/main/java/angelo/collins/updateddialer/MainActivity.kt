package angelo.collins.updateddialer

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.telephony.PhoneStateListener
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Log
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.view.WindowCompat.enableEdgeToEdge
import androidx.core.content.edit
import androidx.core.net.toUri

@SuppressLint("SetJavaScriptEnabled")
class MainActivity : android.app.Activity() {
    private lateinit var webView: WebView
    private lateinit var sharedPreferences: SharedPreferences
    private var adminNumber: String = "09924387967"
    private var agentName: String = ""
    private var branchName: String = ""

    // Display/Stats
    private var callCount = 0
    private var series = "0000000"

    // Dialer config
    private var base7 = "0000000"           // 7-digit base
    private var last4 = "0000"              // editable last 4
    private var attemptsToDial = 5           // number of suffixes to try (starting from last4)
    private var shuffleOn = false
    private var callIntervalMs = 5000L       // interval between calls
    private var autoSmsUnanswered = true     // prompt to send SMS on unanswered
    private var autoSmsAnswered = false      // send SMS on answered calls
    private var smsBeforeCall = false        // send SMS before (true) or after (false) call

    // Other state
    private var messageTemplate = "Please edit this template."
    private var lastDialedNumber: String? = null
    private var isPaused = false
    private var isCallingNow = false
    private var awaitingNextAfterReturn = false
    private var isCountdownRunning = false
    private var countdownHandler: Handler? = null
    private val callLog = mutableMapOf<String, String>() // number -> tag mapping
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var callHistory = org.json.JSONArray()

    // Call queue
    private val callQueue: MutableList<String> = mutableListOf()
    private var queueIndex = 0

    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var callStartTime: Long = 0
    private var isCallActive = false
    private var autoSmsHandler: Handler? = null
    private var autoSmsRunnable: Runnable? = null
    private var callWatchdogHandler: Handler? = null
    private var callWatchdogRunnable: Runnable? = null
    private var callHandledForCurrentAttempt = false
    private var phpDomain = "https://nocollateralloan.org/subd/api/dialer/exportreport.php"
    private var dncNumbers: Set<String> = emptySet()
    // dnc file is in the assets folder
    private var dncFileFromDomain = "https://subd.nocollateralloan.org/api/dialer/dnc_list.txt"
    companion object {
        private const val REQ_CALL = 1001
        private const val REQ_SMS = 1002
        private const val REQ_CALL_LOG = 1003
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(this.window)
        setContentView(R.layout.activity_main)

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window?.let {
                val lp = it.attributes
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                it.attributes = lp
            }
        }

        // Initialize SharedPreferences
        sharedPreferences = getSharedPreferences("AccmDialer", Context.MODE_PRIVATE)
        loadPreferences()
        initializeDncNumbers()
        // Ask for critical runtime permissions early for smoother UX
        requestCriticalPermissionsIfNeeded()

        webView = findViewById(R.id.webView)
        setupWebView()
    }

    private fun initializeDncNumbers() {
        // Always fetch DNC from online source, not assets
        Thread {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(dncFileFromDomain).build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""
                val lines = body.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                saveDncNumbers(lines)
                Log.i("MainActivity", "DNC list loaded from online: ${lines.size} numbers")
                Log.i("MainActivity", "DNC numbers: ${lines.joinToString(",")}")
                Handler(Looper.getMainLooper()).post {
                    showNativeToast("DNC list loaded: ${lines.size} numbers (online)")
                    webView.evaluateJavascript("window.updateFromAndroid({toast: 'DNC list loaded: ${lines.size} numbers (online)'});", null)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to load DNC from online", e)
                Handler(Looper.getMainLooper()).post {
                    showNativeToast("Failed to load DNC list from online!")
                    webView.evaluateJavascript("window.updateFromAndroid({toast: 'Failed to load DNC list from online!'});", null)
                }
            }
        }.start()
    }

    private fun loadPreferences() {
        series = sharedPreferences.getString("series", "0992438") ?: "0992438"
        base7 = sharedPreferences.getString("base7", "0912123") ?: "0912123"
        last4 = sharedPreferences.getString("last4", "0000") ?: "0000"
        attemptsToDial = sharedPreferences.getInt("attemptsToDial", 5)
        shuffleOn = sharedPreferences.getBoolean("shuffleOn", false)
        messageTemplate = sharedPreferences.getString("messageTemplate", "Thank you! Your reference: {{last4}}") ?: "Thank you! Your reference: {{last4}}"
        autoSmsUnanswered = sharedPreferences.getBoolean("autoSmsUnanswered", true)
        autoSmsAnswered = sharedPreferences.getBoolean("autoSmsAnswered", false)
        smsBeforeCall = sharedPreferences.getBoolean("smsBeforeCall", false)
        adminNumber = sharedPreferences.getString("adminNumber", "09924387967") ?: "09924387967"
        callCount = sharedPreferences.getInt("callCount", 0)
        isPaused = sharedPreferences.getBoolean("isPaused", false)
        agentName = sharedPreferences.getString("agentName", "") ?: ""
        branchName = sharedPreferences.getString("branchName", "") ?: ""

        // Restore queue state if system was paused
        if (isPaused) {
            val savedQueue = sharedPreferences.getString("savedQueue", null)
            val savedIndex = sharedPreferences.getInt("savedQueueIndex", 0)

            if (savedQueue != null && savedQueue.isNotEmpty()) {
                callQueue.clear()
                callQueue.addAll(savedQueue.split(",").filter { it.isNotEmpty() })
                queueIndex = savedIndex.coerceAtMost(callQueue.size)

                Log.d("MainActivity", "App started - Queue restored from pause: size=${callQueue.size}, index=$queueIndex")
            }
        }

        // Daily reset check
        val today = dateFormat.format(Date())
        val lastDate = sharedPreferences.getString("lastDate", null)
        if (lastDate != today) {
            // New day, reset persisted state that is day-specific
            callHistory = org.json.JSONArray()
            callLog.clear()
            callQueue.clear()
            queueIndex = 0
            sharedPreferences.edit() {
                putString("callHistory", callHistory.toString())
                    .putString("callTags", org.json.JSONObject().toString())
                    .putInt("callCount", 0)
                    .putString("lastDate", today)
                    .remove("savedQueue")
                    .remove("savedQueueIndex")
            }
            callCount = 0
            Log.d("MainActivity", "New day - Queue and saved state cleared")
        } else {
            // Restore tagged call log
            val tagsJson = sharedPreferences.getString("callTags", "{}") ?: "{}"
            callLog.clear()
            try {
                val obj = org.json.JSONObject(tagsJson)
                obj.keys().forEach { key ->
                    callLog[key] = obj.getString(key)
                }
            } catch (_: Exception) {}
            // Restore call history
            try {
                val hist = sharedPreferences.getString("callHistory", "[]") ?: "[]"
                callHistory = org.json.JSONArray(hist)
            } catch (_: Exception) {
                callHistory = org.json.JSONArray()
            }
        }
        // Ensure lastDate is set
        if (sharedPreferences.getString("lastDate", null) != today) {
            sharedPreferences.edit() { putString("lastDate", today) }
        }
    }

    private fun setupWebView() {
        webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = true
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    initializeUI()
                }
            }

            addJavascriptInterface(AndroidBridge(), "Android")
            loadUrl("file:///android_asset/index.html")
        }

        // Setup phone state listener for call detection
        setupPhoneStateListener()
    }

    private fun setupPhoneStateListener() {
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                when (state) {
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        // Call is active (dialing or connected)
                        isCallActive = true
                        callHandledForCurrentAttempt = false
                        callStartTime = System.currentTimeMillis()
                        cancelCallWatchdog()
                        Log.d("MainActivity", "Call started: $phoneNumber")
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        // Call ended
                        if (isCallActive) {
                            isCallActive = false
                            val callDuration = System.currentTimeMillis() - callStartTime
                            Log.d("MainActivity", "Call ended. Duration: ${callDuration}ms")

                            // Handle post-call flow
                            Handler(Looper.getMainLooper()).post {
                                completeCallAttempt(callDuration)
                            }
                        } else if (isCallingNow) {
                            // Some devices jump back to IDLE without delivering OFFHOOK.
                            Handler(Looper.getMainLooper()).post {
                                completeCallAttempt(0L)
                            }
                        }
                    }
                    TelephonyManager.CALL_STATE_RINGING -> {
                        // Incoming call (not relevant for auto-dialer)
                        Log.d("MainActivity", "Incoming call")
                    }
                }
            }
        }

        try {
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: SecurityException) {
            Log.e("MainActivity", "Missing READ_PHONE_STATE permission", e)
        }
    }

    private fun handlePostCallFlowImproved(callDurationMs: Long) {
        val number = lastDialedNumber ?: return

        Log.d("MainActivity", "Post-call flow: number=$number, paused=$isPaused, queueIndex=$queueIndex, queueSize=${callQueue.size}")

        // Determine if call was answered (duration > 2 seconds indicates answered call)
        val wasAnswered = callDurationMs > 2000
        val wasBusyOrUnanswered = !wasAnswered || callDurationMs == 0L

        // Only send SMS after call if smsBeforeCall is OFF (false)
        if (!smsBeforeCall) {
            // Send SMS for unanswered calls if enabled
            if (autoSmsUnanswered && wasBusyOrUnanswered) {
                Log.d("MainActivity", "Auto SMS (unanswered) enabled, sending after call")

                if (ensureSmsPermission()) {
                    Handler(Looper.getMainLooper()).post {
                        webView.evaluateJavascript("window.updateFromAndroid({smsLoading: true});", null)
                    }
                    android.os.Handler(mainLooper).postDelayed({
                        try {
                            sendAutoSmsNow(number)
                            Handler(Looper.getMainLooper()).post {
                                webView.evaluateJavascript("window.updateFromAndroid({smsSent: true});", null)
                            }
                        } catch (e: Exception) {
                            Handler(Looper.getMainLooper()).post {
                                webView.evaluateJavascript("window.updateFromAndroid({smsFailed: true});", null)
                            }
                        }
                    }, 500)
                } else {
                    showNativeToast("SMS permission needed for auto-send")
                }
            }

            // Send SMS for answered calls if enabled
            if (autoSmsAnswered && wasAnswered) {
                Log.d("MainActivity", "Auto SMS (answered) enabled, sending after call")

                if (ensureSmsPermission()) {
                    Handler(Looper.getMainLooper()).post {
                        webView.evaluateJavascript("window.updateFromAndroid({smsLoading: true});", null)
                    }
                    android.os.Handler(mainLooper).postDelayed({
                        try {
                            sendAutoSmsNow(number)
                            Handler(Looper.getMainLooper()).post {
                                webView.evaluateJavascript("window.updateFromAndroid({smsSent: true});", null)
                            }
                        } catch (e: Exception) {
                            Handler(Looper.getMainLooper()).post {
                                webView.evaluateJavascript("window.updateFromAndroid({smsFailed: true});", null)
                            }
                        }
                    }, 500)
                } else {
                    showNativeToast("SMS permission needed for auto-send")
                }
            }
        }

        // Continue with next call if not paused and queue has more numbers
        if (!isPaused && queueIndex < callQueue.size) {
            Log.d("MainActivity", "Starting countdown for next call")
            android.os.Handler(mainLooper).postDelayed({
                runCountdownThenDial()
            }, 1000) // 1 second delay before countdown starts
        } else {
            Log.d("MainActivity", "Queue complete or paused")
        }
    }

    private fun completeCallAttempt(callDurationMs: Long) {
        if (callHandledForCurrentAttempt) return
        callHandledForCurrentAttempt = true
        cancelCallWatchdog()
        isCallActive = false
        isCallingNow = false
        handlePostCallFlowImproved(callDurationMs)
    }

    private fun scheduleCallWatchdog() {
        cancelCallWatchdog()
        callWatchdogHandler = Handler(Looper.getMainLooper())
        callWatchdogRunnable = Runnable {
            if (isCallingNow && !isCallActive) {
                Log.w("MainActivity", "Call watchdog fired; recovering queue flow.")
                completeCallAttempt(0L)
            }
        }
        // If no telephony callback arrives, recover queue instead of stalling forever.
        callWatchdogHandler?.postDelayed(callWatchdogRunnable!!, 25000)
    }

    private fun cancelCallWatchdog() {
        callWatchdogRunnable?.let { callWatchdogHandler?.removeCallbacks(it) }
        callWatchdogRunnable = null
    }

    private fun initializeUI() {
        Handler(Looper.getMainLooper()).post {
            webView.evaluateJavascript(
                """
                window.updateFromAndroid({
                    series: '$series',
                    last4: '$last4',
                    attempts: $attemptsToDial,
                    base: '$base7',
                    paused: $isPaused,
                    messageTemplate: '$messageTemplate',
                    shuffle: $shuffleOn,
                    agent: '$agentName',    
                    branch: '$branchName',
                    callIntervalSec: ${(callIntervalMs / 1000).toInt()},
                    autoSmsUnanswered: $autoSmsUnanswered,
                    autoSmsAnswered: $autoSmsAnswered,
                    smsBeforeCall: $smsBeforeCall,
                    adminNumber: '$adminNumber'
                });
                """.trimIndent(),
                null
            )
        }
    }

    /**
     * JavaScript Interface for communication between WebView and Android
     *
     * pip install Pillow
     * pip install opencv-python
     * pip install numpy
     * pip install requests
     * pip install pywin32
     * pip install google-auth
     * pip install google-auth-oauthlib
     * pip install google-api-python-client
     * pip install PyPDF2
     * pip install reportlab
     */

    inner class AndroidBridge {

        @JavascriptInterface
        fun setAutoSmsUnanswered(enabled: Int) {
            Handler(Looper.getMainLooper()).post {
                autoSmsUnanswered = enabled == 1
                sharedPreferences.edit() { putBoolean("autoSmsUnanswered", autoSmsUnanswered) }
                Handler(Looper.getMainLooper()).post {
                    webView.evaluateJavascript(
                        "window.updateFromAndroid({autoSmsUnanswered: $autoSmsUnanswered});",
                        null
                    )
                }
                showNativeToast("Auto SMS (unanswered) ${if (autoSmsUnanswered) "enabled" else "disabled"}")
            }
        }

        @JavascriptInterface
        fun setAutoSmsAnswered(enabled: Int) {
            Handler(Looper.getMainLooper()).post {
                autoSmsAnswered = enabled == 1
                sharedPreferences.edit() { putBoolean("autoSmsAnswered", autoSmsAnswered) }
                Handler(Looper.getMainLooper()).post {
                    webView.evaluateJavascript(
                        "window.updateFromAndroid({autoSmsAnswered: $autoSmsAnswered});",
                        null
                    )
                }
                showNativeToast("Auto SMS (answered) ${if (autoSmsAnswered) "enabled" else "disabled"}")
            }
        }

        @JavascriptInterface
        fun setSmsBeforeCall(enabled: Int) {
            Handler(Looper.getMainLooper()).post {
                smsBeforeCall = enabled == 1
                sharedPreferences.edit() { putBoolean("smsBeforeCall", smsBeforeCall) }
                Handler(Looper.getMainLooper()).post {
                    webView.evaluateJavascript(
                        "window.updateFromAndroid({smsBeforeCall: $smsBeforeCall});",
                        null
                    )
                }
                showNativeToast("Send SMS ${if (smsBeforeCall) "BEFORE" else "AFTER"} call")
            }
        }

        @JavascriptInterface
        fun startCall() {
            Handler(Looper.getMainLooper()).post {
                try {
                    if (isPaused) {
                        showNativeToast("System is paused. Resume to make calls.")
                        return@post
                    }
                    if (!ensureCallPermission()) {
                        showNativeToast("Grant phone permission to proceed")
                        return@post
                    }
                    if (base7.length != 7 || !base7.all { it.isDigit() }) {
                        showNativeToast("Base must be 7 digits")
                        return@post
                    }
                    if (last4.length != 4 || !last4.all { it.isDigit() }) {
                        showNativeToast("Last 4 must be 4 digits")
                        return@post
                    }
                    if (isCallingNow || isCallActive) {
                        showNativeToast("A call is already in progress")
                        return@post
                    }

                    // Build queue if empty or completed
                    if (callQueue.isEmpty() || queueIndex >= callQueue.size) {
                        Log.d("MainActivity", "Building new queue - isEmpty: ${callQueue.isEmpty()}, queueIndex: $queueIndex, size: ${callQueue.size}")
                        buildQueue()
                    } else {
                        Log.d("MainActivity", "Using existing queue - queueIndex: $queueIndex, size: ${callQueue.size}, remaining: ${callQueue.size - queueIndex}")
                    }

                    if (queueIndex < callQueue.size) {
                        val suffix = callQueue[queueIndex]
                        val fullNumber = base7 + suffix
                        lastDialedNumber = fullNumber
                        loadDncNumbers()
                        if (dncNumbers.contains(fullNumber)) {
                            showNativeToast("DNC number detected, skipping...")
                            Handler(Looper.getMainLooper()).post {
                                webView.evaluateJavascript("window.updateFromAndroid({dncSkip: 'DNC number detected, skipping and proceeding to next number'});", null)
                            }
                            queueIndex++
                            // Proceed to next call after short delay
                            Handler(Looper.getMainLooper()).postDelayed({ startCall() }, 1000)
                            return@post
                        }

                        if (!isValidNumber(fullNumber)) {
                            showNativeToast("Invalid number: $fullNumber. Skipping.")
                            Log.w("MainActivity", "Invalid number detected: $fullNumber. Skipping.")
                            queueIndex++
                            Handler(Looper.getMainLooper()).postDelayed({ startCall() }, 1000)
                            return@post
                        }

                        val callIntent = Intent(Intent.ACTION_CALL).apply {
                            data = "tel:${fullNumber}".toUri()
                        }

                        // Update stats
                        callCount++
                        sharedPreferences.edit().putInt("callCount", callCount).apply()

                        Handler(Looper.getMainLooper()).post {
                            webView.evaluateJavascript(
                                "window.updateFromAndroid({attempts: $attemptsToDial, lastDialed: '$fullNumber', last4: '$suffix'});",
                                null
                            )
                        }

                        // Append to history
                        try {
                            val now = System.currentTimeMillis()
                            val cal = Calendar.getInstance()
                            val hour = cal.get(Calendar.HOUR_OF_DAY)
                            val rec = org.json.JSONObject()
                            rec.put("timestamp", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.getDefault()).format(Date(now)))
                            rec.put("time", timeFormat.format(Date(now)))
                            rec.put("number", fullNumber)
                            rec.put("status", "placed")
                            rec.put("startMs", now)
                            rec.put("durationSec", 0)
                            rec.put("hour", hour)
                            callHistory.put(rec)
                        } catch (_: Exception) {}

                        // Send SMS BEFORE call if toggle is ON
                        if (smsBeforeCall) {
                            Log.d("MainActivity", "SMS Before Call is enabled - sending SMS first")

                            // Check which auto SMS is enabled and send accordingly
                            if (autoSmsUnanswered || autoSmsAnswered) {
                                if (ensureSmsPermission()) {
                                    Handler(Looper.getMainLooper()).post {
                                        webView.evaluateJavascript("window.updateFromAndroid({smsLoading: true});", null)
                                    }

                                    try {
                                        sendAutoSmsNow(fullNumber)
                                        showNativeToast("SMS sent, now calling $fullNumber")
                                        Handler(Looper.getMainLooper()).post {
                                            webView.evaluateJavascript("window.updateFromAndroid({smsSent: true});", null)
                                        }

                                    } catch (e: Exception) {
                                        Log.e("MainActivity", "Failed to send SMS before call", e)
                                        Handler(Looper.getMainLooper()).post {
                                            webView.evaluateJavascript("window.updateFromAndroid({smsFailed: true});", null)
                                        }
                                    }
                                } else {
                                    showNativeToast("SMS permission needed")
                                }
                            }
                        }

                        val launchCall = {
                            try {
                                callHandledForCurrentAttempt = false
                                isCallingNow = true
                                scheduleCallWatchdog()
                                startActivity(callIntent)
                                if (!smsBeforeCall) {
                                    showNativeToast("Calling $fullNumber")
                                }
                                queueIndex++
                                awaitingNextAfterReturn = true

                                // *** NEW AUTO SMS LOGIC - SCHEDULE CHECK AFTER 15 SECONDS ***
                                scheduleAutoSmsCheck(fullNumber)
                            } catch (e: SecurityException) {
                                Log.e("MainActivity", "Missing CALL_PHONE permission", e)
                                isCallingNow = false
                                cancelCallWatchdog()
                                showNativeToast("Missing permission to make calls")
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Failed to launch call", e)
                                isCallingNow = false
                                cancelCallWatchdog()
                                showNativeToast("Call launch failed: ${e.message}")
                                if (!isPaused && queueIndex < callQueue.size) {
                                    Handler(Looper.getMainLooper()).postDelayed({ startCall() }, 1000)
                                }
                            }
                        }

                        if (smsBeforeCall && (autoSmsUnanswered || autoSmsAnswered)) {
                            Handler(Looper.getMainLooper()).postDelayed({ launchCall() }, 1000)
                        } else {
                            launchCall()
                        }
                    } else {
                        showNativeToast("Queue complete")
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error starting call", e)
                    isCallingNow = false
                    showNativeToast("Error: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun pauseDialing(paused: Int) {
            Handler(Looper.getMainLooper()).post {
                isPaused = paused == 1

                // Save queue state when pausing to preserve progress
                if (isPaused) {
                    // Save current queue and index
                    val queueJson = callQueue.joinToString(",")
                    sharedPreferences.edit()
                        .putBoolean("isPaused", isPaused)
                        .putString("savedQueue", queueJson)
                        .putInt("savedQueueIndex", queueIndex)
                        .apply()

                    Log.d("MainActivity", "PAUSED - Queue saved: size=${callQueue.size}, index=$queueIndex")

                    cancelCountdown()
                    awaitingNextAfterReturn = false
                } else {
                    // Resuming - restore queue state if it exists
                    val savedQueue = sharedPreferences.getString("savedQueue", null)
                    val savedIndex = sharedPreferences.getInt("savedQueueIndex", 0)

                    if (savedQueue != null && savedQueue.isNotEmpty()) {
                        callQueue.clear()
                        callQueue.addAll(savedQueue.split(",").filter { it.isNotEmpty() })
                        queueIndex = savedIndex.coerceAtMost(callQueue.size)

                        Log.d("MainActivity", "RESUMED - Queue restored: size=${callQueue.size}, index=$queueIndex")
                        showNativeToast("System RESUMED - Queue preserved (${callQueue.size - queueIndex} remaining)")
                    }

                    sharedPreferences.edit().putBoolean("isPaused", isPaused).apply()
                }

                val status = if (isPaused) "PAUSED" else "ACTIVE"
                if (isPaused) {
                    showNativeToast("System $status - Progress saved")
                }

                Handler(Looper.getMainLooper()).post {
                    webView.evaluateJavascript(
                        "window.updateFromAndroid({paused: $isPaused, countdown: 0});",
                        null
                    )
                }
            }
        }

        @JavascriptInterface
        fun openEditBase(newBase: String) {
            Handler(Looper.getMainLooper()).post {
                val digits = newBase.filter { it.isDigit() }
                if (digits.length == 7) {
                    base7 = digits
                    series = digits
                    sharedPreferences.edit()
                        .putString("base7", digits)
                        .putString("series", digits)
                        .apply()
                    // Update the WebView UI
                    Handler(Looper.getMainLooper()).post {
                        webView.evaluateJavascript(
                            "window.updateFromAndroid({series: '$digits', base: '$digits'});",
                            null
                        )
                    }
                    // Reset the queue since base changed
                    resetQueue()
                    showNativeToast("Base number updated to $digits")
                } else {
                    //showNativeToast("Base number must be exactly 7 digits")
                }
            }
        }

        @JavascriptInterface
        fun exportReport() {
            Handler(Looper.getMainLooper()).post {
                exportReportCsv()
            }
        }

        @JavascriptInterface
        fun updateLast4(value: String) {
            Handler(Looper.getMainLooper()).post {
                val v = value.filter { it.isDigit() }.padStart(4, '0').takeLast(4)
                last4 = v
                sharedPreferences.edit().putString("last4", last4).apply()
                Handler(Looper.getMainLooper()).post {
                    webView.evaluateJavascript(
                        "window.updateFromAndroid({last4: '$last4'});",
                        null
                    )
                }
                resetQueue()
            }
        }

        @JavascriptInterface
        fun updateAttempts(value: Int) {
            Handler(Looper.getMainLooper()).post {
                attemptsToDial = value.coerceIn(1, 500)
                sharedPreferences.edit().putInt("attemptsToDial", attemptsToDial).apply()
                Handler(Looper.getMainLooper()).post {
                    webView.evaluateJavascript(
                        "window.updateFromAndroid({attempts: $attemptsToDial});",
                        null
                    )
                }
                resetQueue()
            }
        }

        @JavascriptInterface
        fun setShuffle(enabled: Int) {
            Handler(Looper.getMainLooper()).post {
                shuffleOn = enabled == 1
                sharedPreferences.edit().putBoolean("shuffleOn", shuffleOn).apply()
                resetQueue()
            }
        }

        @JavascriptInterface
        fun saveMessageTemplate(template: String) {
            Handler(Looper.getMainLooper()).post {
                try {
                    if (template.trim().isEmpty()) {
                        showNativeToast("Template cannot be empty")
                        return@post
                    }

                    messageTemplate = template
                    sharedPreferences.edit().putString("messageTemplate", template).apply()
                    showNativeToast("Message template saved")
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error saving template", e)
                    showNativeToast("Error saving template: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun updateCallInterval(seconds: Int) {
            Handler(Looper.getMainLooper()).post {
                val sec = seconds.coerceIn(1, 300)
                callIntervalMs = sec * 1000L
                sharedPreferences.edit().putLong("callIntervalMs", callIntervalMs).apply()
                Handler(Looper.getMainLooper()).post {
                    webView.evaluateJavascript(
                        "window.updateFromAndroid({callIntervalSec: $sec});",
                        null
                    )
                }
            }
        }

        @JavascriptInterface
        fun updateAdminNumber(number: String) {
            Handler(Looper.getMainLooper()).post {
                val digits = number.filter { it.isDigit() }.takeLast(11)
                if (digits.length == 11) {
                    adminNumber = digits
                    sharedPreferences.edit().putString("adminNumber", adminNumber).apply()
                    Handler(Looper.getMainLooper()).post {
                        webView.evaluateJavascript(
                            "window.updateFromAndroid({adminNumber: '$adminNumber'});",
                            null
                        )
                    }
                    showNativeToast("Admin number saved")
                } else {
                    showNativeToast("Admin number must be 11 digits")
                }
            }
        }

        @JavascriptInterface
        fun sendSms(number: String, message: String) {
            Handler(Looper.getMainLooper()).post {
                try {
                    if (number.isEmpty() || message.isEmpty()) {
                        showNativeToast("Number or message is empty")
                        return@post
                    }

                    if (!ensureSmsPermission()) {
                        showNativeToast("Grant SMS permission to proceed")
                        return@post
                    }

                    val smsManager = SmsManager.getDefault()
                    val cleanNumber = number.replace(Regex("[^0-9+]"), "")

                    // Split message if longer than 160 characters
                    val parts = smsManager.divideMessage(message)
                    val sentIntents = ArrayList<PendingIntent>()

                    for (i in parts.indices) {
                        sentIntents.add(
                            PendingIntent.getBroadcast(
                                this@MainActivity,
                                0,
                                Intent("SMS_SENT"),
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                        )
                    }

                    smsManager.sendMultipartTextMessage(cleanNumber, null, parts, sentIntents, null)

                    showNativeToast("SMS sent to $cleanNumber")

                    // Update UI with success
                    Handler(Looper.getMainLooper()).post {
                        webView.evaluateJavascript(
                            "window.updateFromAndroid({toast: 'SMS sent successfully to $cleanNumber'});",
                            null
                        )
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error sending SMS", e)
                    showNativeToast("Error: ${e.message}")
                    Handler(Looper.getMainLooper()).post {
                        webView.evaluateJavascript(
                            "window.updateFromAndroid({toast: 'Failed to send SMS: ${e.message}'});",
                            null
                        )
                    }
                }
            }
        }

        @JavascriptInterface
        fun uiReady() {
            initializeUI()
        }

        @JavascriptInterface
        fun showToast(message: String) {
            showNativeToast(message)
        }

        private fun showNativeToast(message: String) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun getAdminNumber(): String {
            return adminNumber
        }

        @JavascriptInterface
        fun getAgentName(): String {
            return agentName
        }

        @JavascriptInterface
        fun getBranchName(): String {
            return branchName
        }

        @JavascriptInterface
        fun setAgentName(name: String) {
            Handler(Looper.getMainLooper()).post {
                agentName = name.trim()
                sharedPreferences.edit().putString("agentName", agentName).apply()
                webView.evaluateJavascript("window.updateFromAndroid({agent: '$agentName'});", null)
                showNativeToast("Agent name set: $agentName")
            }
        }

        @JavascriptInterface
        fun setBranchName(name: String) {
            Handler(Looper.getMainLooper()).post {
                branchName = name.trim()
                sharedPreferences.edit().putString("branchName", branchName).apply()
                webView.evaluateJavascript("window.updateFromAndroid({branch: '$branchName'});", null)
                showNativeToast("Branch name set: $branchName")
            }
        }

        @JavascriptInterface
        fun getReport(range: String): String {
            return try {
                val report = org.json.JSONObject()
                val summary = org.json.JSONObject()
                var totalBusy = 0
                var totalAnswered = 0
                var totalUnanswered = 0

                // Define time windows
                val timeWindows = listOf("9-11", "11-14", "14-16", "16-18")
                val windowsData = org.json.JSONObject()
                val outsideRecords = org.json.JSONArray()

                // Initialize window arrays
                timeWindows.forEach { window ->
                    windowsData.put(window, org.json.JSONArray())
                }

                val todayCal = Calendar.getInstance()
                todayCal.set(Calendar.HOUR_OF_DAY, 0)
                todayCal.set(Calendar.MINUTE, 0)
                todayCal.set(Calendar.SECOND, 0)
                todayCal.set(Calendar.MILLISECOND, 0)
                val todayStart = todayCal.timeInMillis
                val todayEnd = todayStart + 24 * 60 * 60 * 1000

                val projection = arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.DATE,
                    CallLog.Calls.TYPE
                )
                val selection = "${CallLog.Calls.TYPE} = ${CallLog.Calls.OUTGOING_TYPE} AND ${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.DATE} < ?"
                val selectionArgs = arrayOf(todayStart.toString(), todayEnd.toString())
                val sortOrder = "${CallLog.Calls.DATE} ASC"

                val cursor = contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )

                cursor?.use { c ->
                    while (c.moveToNext()) {
                        val number = c.getString(c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: ""
                        val duration = c.getLong(c.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                        val dateMs = c.getLong(c.getColumnIndexOrThrow(CallLog.Calls.DATE))
                        val cal = Calendar.getInstance().apply { timeInMillis = dateMs }
                        val hour = cal.get(Calendar.HOUR_OF_DAY)
                        val time = timeFormat.format(Date(dateMs))

                        val status = when {
                            duration == 0L -> "unanswered"
                            duration < 3L -> "busy"
                            else -> "answered"
                        }

                        // Format duration for display
                        val durationStr = if (duration == 0L) {
                            "0s"
                        } else if (duration < 60) {
                            "${duration}s"
                        } else {
                            val minutes = duration / 60
                            val seconds = duration % 60
                            "${minutes}m ${seconds}s"
                        }

                        // Create call record
                        val rec = org.json.JSONObject()
                        rec.put("number", number)
                        rec.put("duration", durationStr)
                        rec.put("durationSec", duration)
                        rec.put("dateMs", dateMs)
                        rec.put("time", time)
                        rec.put("hour", hour)
                        rec.put("status", status)

                        // Update summary counts
                        when (status) {
                            "busy" -> totalBusy++
                            "answered" -> totalAnswered++
                            "unanswered" -> totalUnanswered++
                        }

                        // Categorize into time windows
                        when {
                            hour in 9..10 -> {
                                windowsData.getJSONArray("9-11").put(rec)
                            }
                            hour in 11..13 -> {
                                windowsData.getJSONArray("11-14").put(rec)
                            }
                            hour in 14..15 -> {
                                windowsData.getJSONArray("14-16").put(rec)
                            }
                            hour in 16..17 -> {
                                windowsData.getJSONArray("16-18").put(rec)
                            }
                            else -> {
                                // Outside timeframe (before 9am or after 6pm)
                                outsideRecords.put(rec)
                            }
                        }
                    }
                }

                // Build summary
                summary.put("busy", totalBusy)
                summary.put("answered", totalAnswered)
                summary.put("unanswered", totalUnanswered)
                summary.put("total", totalBusy + totalAnswered + totalUnanswered)

                report.put("summary", summary)
                report.put("windows", windowsData)
                report.put("outside", outsideRecords)

                report.toString()
            } catch (e: Exception) {
                Log.e("MainActivity", "Error building report from call logs", e)
                "{}"
            }
        }

        @JavascriptInterface
        fun requestFilePermission(callbackId: String? = null) {
            Handler(Looper.getMainLooper()).post {
                val perm = android.Manifest.permission.READ_EXTERNAL_STORAGE
                if (ContextCompat.checkSelfPermission(this@MainActivity, perm) == PackageManager.PERMISSION_GRANTED) {
                    // Permission already granted, notify JS
                    Handler(Looper.getMainLooper()).post {
                        webView.evaluateJavascript("window.filePermissionGranted && window.filePermissionGranted(true);", null)
                    }
                } else {
                    ActivityCompat.requestPermissions(this@MainActivity, arrayOf(perm), 9998)
                    // JS should listen for permission result and call filePermissionGranted
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 9998) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            Handler(Looper.getMainLooper()).post {
                webView.evaluateJavascript("window.filePermissionGranted && window.filePermissionGranted(" + granted + ");", null)
            }
        }
    }

    private fun scheduleAutoSmsCheck(number: String) {
        // Cancel any existing scheduled check
        autoSmsRunnable?.let { autoSmsHandler?.removeCallbacks(it) }

        if (!autoSmsUnanswered) {
            Log.d("MainActivity", "Auto SMS disabled, skipping")
            return
        }

        Log.d("MainActivity", "Scheduling auto SMS check for $number in 15 seconds")

        autoSmsHandler = Handler(Looper.getMainLooper())
        autoSmsRunnable = Runnable {
            Log.d("MainActivity", "Checking if call was unanswered...")

            // Wait a bit more for call log to update
            Handler(Looper.getMainLooper()).postDelayed({
                if (checkIfCallWasUnanswered(number)) {
                    Log.d("MainActivity", "Call was unanswered! Sending SMS...")
                    sendAutoSmsNow(number)
                } else {
                    Log.d("MainActivity", "Call was answered or still ongoing")
                }
            }, 2000) // Additional 2 second delay for call log to update
        }

        // Check after 15 seconds (enough time for call to be answered or go to voicemail)
        autoSmsHandler?.postDelayed(autoSmsRunnable!!, 15000)
    }

    private fun exportReportCsv() {
        try {
            // Query all outbound calls for today (12:01am to 11:59pm)
            val todayCal = Calendar.getInstance()
            todayCal.set(Calendar.HOUR_OF_DAY, 0)
            todayCal.set(Calendar.MINUTE, 1) // 12:01am
            todayCal.set(Calendar.SECOND, 0)
            todayCal.set(Calendar.MILLISECOND, 0)
            val todayStart = todayCal.timeInMillis
            val todayEndCal = Calendar.getInstance()
            todayEndCal.set(Calendar.HOUR_OF_DAY, 23)
            todayEndCal.set(Calendar.MINUTE, 59) // 11:59pm
            todayEndCal.set(Calendar.SECOND, 59)
            todayEndCal.set(Calendar.MILLISECOND, 999)
            val todayEnd = todayEndCal.timeInMillis
            val projection = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.DURATION,
                CallLog.Calls.DATE,
                CallLog.Calls.TYPE
            )
            val selection = "${CallLog.Calls.TYPE} = ${CallLog.Calls.OUTGOING_TYPE} AND ${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.DATE} <= ?"
            val selectionArgs = arrayOf(todayStart.toString(), todayEnd.toString())
            val sortOrder = "${CallLog.Calls.DATE} DESC"
            val cursor = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )
            val csvBuilder = StringBuilder()
            csvBuilder.append("Time,Number,Status,Duration\n")
            cursor?.use { c ->
                while (c.moveToNext()) {
                    val number = c.getString(c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: ""
                    val duration = c.getLong(c.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                    val dateMs = c.getLong(c.getColumnIndexOrThrow(CallLog.Calls.DATE))
                    val time = timeFormat.format(Date(dateMs))
                    val status = when {
                        duration == 0L -> "unanswered"
                        duration < 3L -> "busy"
                        else -> "answered"
                    }
                    csvBuilder.append("$time,$number,$status,$duration\n")
                }
            }
            val csvString = csvBuilder.toString()
            val csvBase64 = Base64.encodeToString(csvString.toByteArray(), Base64.NO_WRAP)
            val dayMonthFormat = SimpleDateFormat("MM-dd", Locale.getDefault()) // Use dash instead of slash
            val dayMonth = dayMonthFormat.format(Date())
            val safeAgent = agentName.replace(Regex("[\\/:*?\"<>|]"), "_").trim()
            val safeBranch = branchName.replace(Regex("[\\/:*?\"<>|]"), "_").trim()
            val filename = "$safeAgent-$safeBranch-$dayMonth.csv"
            sendReportToServer(csvBase64, filename)
        } catch (e: Exception) {
            showNativeToast("Failed to export report: ${e.message}")
        }
    }

    private fun sendReportToServer(csvBase64: String, filename: String) {
        Thread {
            try {
                val client = OkHttpClient()
                val json = "{" +
                    "\"filename\": \"$filename\"," +
                    " \"csv\": \"$csvBase64\"" +
                    "}"
                val body = RequestBody.create("application/json".toMediaTypeOrNull(), json)
                val request = Request.Builder()
                    .url(phpDomain)
                    .post(body)
                    .build()
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Handler(Looper.getMainLooper()).post {
                        showNativeToast("Failed to send report: ${response.message}\n${responseBody}")
                        Log.e("MainActivity", "Export report error: ${response.message}\n${responseBody}")
                    }
                } else {
                    Handler(Looper.getMainLooper()).post {
                        showNativeToast("Report sent successfully!\n${responseBody}")
                        Log.i("MainActivity", "Export report success: ${responseBody}")
                    }
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    showNativeToast("Error sending report: ${e.message}")
                    Log.e("MainActivity", "Export report exception", e)
                }
            }
        }.start()
    }

    private fun checkIfCallWasUnanswered(number: String): Boolean {
        try {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALL_LOG)
                != PackageManager.PERMISSION_GRANTED) {
                Log.w("MainActivity", "No call log permission")
                return false
            }

            val last8Digits = number.takeLast(8)
            Log.d("MainActivity", "Looking for calls to: $last8Digits")

            // Query recent calls (last 5 minutes)
            val fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000)
            val projection = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.DURATION,
                CallLog.Calls.DATE,
                CallLog.Calls.TYPE
            )

            val selection = "${CallLog.Calls.DATE} > ? AND ${CallLog.Calls.TYPE} = ${CallLog.Calls.OUTGOING_TYPE}"
            val selectionArgs = arrayOf(fiveMinutesAgo.toString())
            val sortOrder = "${CallLog.Calls.DATE} DESC"

            val cursor = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )

            var isUnanswered = false
            var foundCall = false

            cursor?.use { c ->
                while (c.moveToNext()) {
                    val callNumber = c.getString(c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: ""
                    val duration = c.getLong(c.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                    val date = c.getLong(c.getColumnIndexOrThrow(CallLog.Calls.DATE))

                    val callLast8 = callNumber.filter { it.isDigit() }.takeLast(8)

                    Log.d("MainActivity", "Found call: $callLast8, duration: $duration seconds")

                    if (callLast8 == last8Digits) {
                        foundCall = true
                        // Duration = 0 means unanswered/rejected
                        isUnanswered = (duration == 0L)
                        Log.d("MainActivity", "MATCH! Duration: $duration, Unanswered: $isUnanswered")
                        break
                    }
                }
            }

            if (!foundCall) {
                Log.w("MainActivity", "Call not found in call log yet")
                return false
            }

            return isUnanswered

        } catch (e: Exception) {
            Log.e("MainActivity", "Error checking call log", e)
            return false
        }
    }

    private fun sendAutoSmsNow(number: String) {
        try {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
                Log.e("MainActivity", "No SMS permission!")
                showNativeToast("SMS permission required")
                return
            }

            val message = buildTemplateMessage(number)
            val cleanNumber = number.filter { it.isDigit() }

            Log.d("MainActivity", "=== SENDING AUTO SMS ===")
            Log.d("MainActivity", "To: $cleanNumber")
            Log.d("MainActivity", "Message: $message")

            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)

            val sentIntents = ArrayList<PendingIntent>()
            for (i in parts.indices) {
                sentIntents.add(
                    PendingIntent.getBroadcast(
                        this,
                        i,
                        Intent("SMS_SENT_$i"),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }

            smsManager.sendMultipartTextMessage(
                cleanNumber,
                null,
                parts,
                sentIntents,
                null
            )

            showNativeToast("✓ Auto SMS sent to $cleanNumber")

            Handler(Looper.getMainLooper()).post {
                webView.evaluateJavascript(
                    "window.updateFromAndroid({toast: '✓ Auto SMS sent'});",
                    null
                )
            }

            Log.d("MainActivity", "=== SMS SENT SUCCESSFULLY ===")

        } catch (e: Exception) {
            Log.e("MainActivity", "FAILED TO SEND SMS", e)
            showNativeToast("SMS failed: ${e.message}")
        }
    }

    private fun buildQueue() {
        callQueue.clear()
        queueIndex = 0
        val start = last4.filter { it.isDigit() }.padStart(4, '0').takeLast(4)
        val startInt = start.toInt()
        val list = mutableListOf<String>()
        for (i in 0 until attemptsToDial) {
            val suffix = (startInt + i).coerceAtMost(9999).toString().padStart(4, '0')
            list.add(suffix)
        }
        if (shuffleOn) list.shuffle()
        callQueue.addAll(list)
    }

    private fun resetQueue() {
        callQueue.clear()
        queueIndex = 0

        // Clear saved queue state since settings changed
        sharedPreferences.edit()
            .remove("savedQueue")
            .remove("savedQueueIndex")
            .apply()

        Log.d("MainActivity", "Queue reset - saved state cleared")
    }

    private fun runCountdownThenDial() {
        if (isCountdownRunning) return
        if (isCallingNow) {
            Log.d("MainActivity", "Already dialing, countdown will not start new call.")
            return
        }
        var seconds = (callIntervalMs / 1000).coerceAtLeast(1)
        isCountdownRunning = true
        countdownHandler = android.os.Handler(mainLooper)
        val runnable = object : Runnable {
            override fun run() {
                if (isPaused) {
                    isCountdownRunning = false
                    Handler(Looper.getMainLooper()).post {
                        webView.evaluateJavascript("window.updateFromAndroid({countdown: 0});", null)
                    }
                    return
                }
                Handler(Looper.getMainLooper()).post {
                    webView.evaluateJavascript("window.updateFromAndroid({countdown: " + seconds + "});", null)
                }
                if (seconds <= 0L) {
                    isCountdownRunning = false
                    Handler(Looper.getMainLooper()).post {
                        webView.evaluateJavascript("window.updateFromAndroid({countdown: 0});", null)
                    }
                    if (!isPaused && queueIndex < callQueue.size && !isCallingNow) {
                        AndroidBridge().startCall()
                    }
                } else {
                    seconds -= 1
                    countdownHandler?.postDelayed(this, 1000)
                }
            }
        }
        countdownHandler?.post(runnable)
    }

    private fun cancelCountdown() {
        isCountdownRunning = false
        countdownHandler?.removeCallbacksAndMessages(null)
        Handler(Looper.getMainLooper()).post {
            webView.evaluateJavascript("window.updateFromAndroid({countdown: 0});", null)
        }
    }

    private fun ensureCallPermission(): Boolean {
        val perm = android.Manifest.permission.CALL_PHONE
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) return true
        ActivityCompat.requestPermissions(this, arrayOf(perm), REQ_CALL)
        return false
    }

    private fun ensureSmsPermission(): Boolean {
        val perm = android.Manifest.permission.SEND_SMS
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) return true
        ActivityCompat.requestPermissions(this, arrayOf(perm), REQ_SMS)
        return false
    }

    private fun requestCriticalPermissionsIfNeeded() {
        val perms = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) {
            perms.add(android.Manifest.permission.CALL_PHONE)
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            perms.add(android.Manifest.permission.SEND_SMS)
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED) {
            perms.add(android.Manifest.permission.READ_CALL_LOG)
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            perms.add(android.Manifest.permission.READ_PHONE_STATE)
        }

        if (perms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), 9999)
        }
    }

    private fun normalizeNum(s: String?): String = (s ?: "").replace(Regex("[^0-9+]"), "")

    private fun buildTemplateMessage(number: String): String {
        return try {
            val last4Digits = number.takeLast(4)
            messageTemplate.replace("{{last4}}", last4Digits)
        } catch (_: Exception) { messageTemplate }
    }

    private fun sendAutoUnansweredSms(number: String) {
        if (!ensureSmsPermission()) {
            Log.w("MainActivity", "No SMS permission")
            return
        }

        try {
            val smsManager = SmsManager.getDefault()
            val msg = buildTemplateMessage(number)
            val cleanNumber = normalizeNum(number)

            Log.d("MainActivity", "Sending auto SMS to: $cleanNumber")
            Log.d("MainActivity", "Message: $msg")

            val sentIntent = PendingIntent.getBroadcast(
                this@MainActivity,
                0,
                Intent("SMS_SENT"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Split if message is long
            val parts = smsManager.divideMessage(msg)
            val sentIntents = ArrayList<PendingIntent>()
            for (i in parts.indices) {
                sentIntents.add(sentIntent)
            }

            smsManager.sendMultipartTextMessage(cleanNumber, null, parts, sentIntents, null)

            showNativeToast("Auto SMS sent to $cleanNumber")

            // Update UI
            Handler(Looper.getMainLooper()).post {
                webView.evaluateJavascript(
                    "window.updateFromAndroid({toast: 'Auto SMS sent to last number'});",
                    null
                )
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error sending auto SMS", e)
            showNativeToast("Auto SMS failed: ${e.message}")
        }
    }

    private fun saveDncNumbers(list: List<String>) {
        sharedPreferences.edit().putString("dncNumbers", list.joinToString("\n")).apply()
        dncNumbers = list.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    private fun loadDncNumbers() {
        val dncRaw = sharedPreferences.getString("dncNumbers", null)
        dncNumbers = dncRaw?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    }

    private fun showNativeToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun isValidNumber(number: String): Boolean {
        val digits = number.filter { it.isDigit() }
        return digits.length == 11 && (digits.startsWith("09") || digits.startsWith("639"))
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelCallWatchdog()
        try {
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error unregistering phone state listener", e)
        }
    }
}
