package angelo.collins.updateddialer

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import android.content.SharedPreferences
import android.telephony.SmsManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import android.view.WindowManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.os.Build
import java.text.SimpleDateFormat
import java.util.*
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var sharedPreferences: SharedPreferences
    private var adminNumber: String = "09924387967"
    private var agentName: String = ""
    private var branchName: String = ""

    // Display/Stats
    private var callCount = 0
    private var series = "0992438"

    // Dialer config
    private var base7 = "0912123"           // 7-digit base
    private var last4 = "0000"              // editable last 4
    private var attemptsToDial = 5           // number of suffixes to try (starting from last4)
    private var shuffleOn = false
    private var callIntervalMs = 5000L       // interval between calls
    private var autoSmsUnanswered = true     // prompt to send SMS on unanswered

    // Other state
    private var messageTemplate = "Thank you! Your reference: {{last4}}"
    private var lastDialedNumber: String? = null
    private var isPaused = false
    private var isCallingNow = false
    private var awaitingNextAfterReturn = false
    private var isCountdownRunning = false
    private var countdownHandler: android.os.Handler? = null
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
    private var expressOnVercel = "https://express-api-public-v1-tf.vercel.app/"


    companion object {
        private const val REQ_CALL = 1001
        private const val REQ_SMS = 1002
        private const val REQ_CALL_LOG = 1003
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Edge-to-edge and immersive fullscreen
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        enterImmersive()

        // Initialize SharedPreferences
        sharedPreferences = getSharedPreferences("AccmDialer", Context.MODE_PRIVATE)
        loadPreferences()
        // Ask for critical runtime permissions early for smoother UX
        requestCriticalPermissionsIfNeeded()

        webView = findViewById(R.id.webView)
        setupWebView()
    }

    private fun loadPreferences() {
        series = sharedPreferences.getString("series", "0992438") ?: "0992438"
        base7 = sharedPreferences.getString("base7", "0912123") ?: "0912123"
        last4 = sharedPreferences.getString("last4", "0000") ?: "0000"
        attemptsToDial = sharedPreferences.getInt("attemptsToDial", 5)
        shuffleOn = sharedPreferences.getBoolean("shuffleOn", false)
        messageTemplate = sharedPreferences.getString("messageTemplate", "Thank you! Your reference: {{last4}}") ?: "Thank you! Your reference: {{last4}}"
        autoSmsUnanswered = sharedPreferences.getBoolean("autoSmsUnanswered", true)
        adminNumber = sharedPreferences.getString("adminNumber", "09924387967") ?: "09924387967"
        callCount = sharedPreferences.getInt("callCount", 0)
        isPaused = sharedPreferences.getBoolean("isPaused", false)
        agentName = sharedPreferences.getString("agentName", "") ?: ""
        branchName = sharedPreferences.getString("branchName", "") ?: ""
        // Daily reset check
        val today = dateFormat.format(Date())
        val lastDate = sharedPreferences.getString("lastDate", null)
        if (lastDate != today) {
            // New day, reset persisted state that is day-specific
            callHistory = org.json.JSONArray()
            callLog.clear()
            sharedPreferences.edit()
                .putString("callHistory", callHistory.toString())
                .putString("callTags", org.json.JSONObject().toString())
                .putInt("callCount", 0)
                .putString("lastDate", today)
                .apply()
            callCount = 0
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
            sharedPreferences.edit().putString("lastDate", today).apply()
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
                        callStartTime = System.currentTimeMillis()
                        Log.d("MainActivity", "Call started: $phoneNumber")
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        // Call ended
                        if (isCallActive) {
                            isCallActive = false
                            val callDuration = System.currentTimeMillis() - callStartTime
                            Log.d("MainActivity", "Call ended. Duration: ${callDuration}ms")

                            // Handle post-call flow
                            runOnUiThread {
                                isCallingNow = false
                                handlePostCallFlowImproved(callDuration)
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

        if (!wasAnswered && autoSmsUnanswered) {
            Log.d("MainActivity", "Call was unanswered. Auto SMS enabled: $autoSmsUnanswered")

            // Send auto SMS for unanswered call
            if (ensureSmsPermission()) {
                android.os.Handler(mainLooper).postDelayed({
                    sendAutoUnansweredSms(number)
                }, 500) // Small delay to ensure call is fully ended
            } else {
                showNativeToast("SMS permission needed for auto-send")
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


    private fun initializeUI() {
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
                adminNumber: '$adminNumber'
            });
            """.trimIndent(),
            null
        )
    }

    /**
     * JavaScript Interface for communication between WebView and Android
     */
    inner class AndroidBridge {

        @JavascriptInterface
        fun setAutoSmsUnanswered(enabled: Int) {
            runOnUiThread {
                autoSmsUnanswered = enabled == 1
                sharedPreferences.edit().putBoolean("autoSmsUnanswered", autoSmsUnanswered).apply()
                webView.evaluateJavascript(
                    "window.updateFromAndroid({autoSmsUnanswered: $autoSmsUnanswered});",
                    null
                )
                showNativeToast("Auto SMS ${if (autoSmsUnanswered) "enabled" else "disabled"}")
            }
        }

        @JavascriptInterface
        fun startCall() {
            runOnUiThread {
                try {
                    if (isPaused) {
                        showNativeToast("System is paused. Resume to make calls.")
                        return@runOnUiThread
                    }
                    if (!ensureCallPermission()) {
                        showNativeToast("Grant phone permission to proceed")
                        return@runOnUiThread
                    }
                    if (base7.length != 7 || !base7.all { it.isDigit() }) {
                        showNativeToast("Base must be 7 digits")
                        return@runOnUiThread
                    }
                    if (last4.length != 4 || !last4.all { it.isDigit() }) {
                        showNativeToast("Last 4 must be 4 digits")
                        return@runOnUiThread
                    }

                    // Build queue if empty
                    if (callQueue.isEmpty() || queueIndex >= callQueue.size) {
                        buildQueue()
                    }

                    if (queueIndex < callQueue.size) {
                        val suffix = callQueue[queueIndex]
                        val fullNumber = base7 + suffix
                        lastDialedNumber = fullNumber

                        val callIntent = Intent(Intent.ACTION_CALL).apply {
                            data = Uri.parse("tel:${fullNumber}")
                        }

                        // Update stats
                        callCount++
                        sharedPreferences.edit().putInt("callCount", callCount).apply()

                        webView.evaluateJavascript(
                            "window.updateFromAndroid({attempts: $attemptsToDial, lastDialed: '$fullNumber', last4: '$suffix'});",
                            null
                        )

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

                        try {
                            if (isCallingNow) {
                                showNativeToast("A call is already in progress")
                                return@runOnUiThread
                            }

                            isCallingNow = true
                            startActivity(callIntent)
                            showNativeToast("Calling $fullNumber")
                            queueIndex++
                            awaitingNextAfterReturn = true

                            // *** NEW AUTO SMS LOGIC - SCHEDULE CHECK AFTER 15 SECONDS ***
                            scheduleAutoSmsCheck(fullNumber)

                        } catch (e: SecurityException) {
                            Log.e("MainActivity", "Missing CALL_PHONE permission", e)
                            showNativeToast("Missing permission to make calls")
                        }
                    } else {
                        showNativeToast("Queue complete")
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error starting call", e)
                    showNativeToast("Error: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun pauseDialing(paused: Int) {
            runOnUiThread {
                isPaused = paused == 1
                sharedPreferences.edit().putBoolean("isPaused", isPaused).apply()

                val status = if (isPaused) "PAUSED" else "ACTIVE"
                showNativeToast("System $status")

                webView.evaluateJavascript(
                    "window.updateFromAndroid({paused: $isPaused, countdown: 0});",
                    null
                )

                if (isPaused) {
                    cancelCountdown()
                    awaitingNextAfterReturn = false
                }
            }
        }

        @JavascriptInterface
        fun openEditBase(newBase: String) {
            runOnUiThread {
                base7 = newBase
                series = newBase
                sharedPreferences.edit().putString("base7", newBase).apply()
                webView.evaluateJavascript("window.updateFromAndroid({series: '$series'});", null)
            }
        }


        @JavascriptInterface
        fun exportReport() {
            runOnUiThread {
                exportReportCsv()
            }
        }

        @JavascriptInterface
        fun updateLast4(value: String) {
            runOnUiThread {
                val v = value.filter { it.isDigit() }.padStart(4, '0').takeLast(4)
                last4 = v
                sharedPreferences.edit().putString("last4", last4).apply()
                webView.evaluateJavascript(
                    "window.updateFromAndroid({last4: '$last4'});",
                    null
                )
                resetQueue()
            }
        }

        @JavascriptInterface
        fun updateAttempts(value: Int) {
            runOnUiThread {
                attemptsToDial = value.coerceIn(1, 10)
                sharedPreferences.edit().putInt("attemptsToDial", attemptsToDial).apply()
                webView.evaluateJavascript(
                    "window.updateFromAndroid({attempts: $attemptsToDial});",
                    null
                )
                resetQueue()
            }
        }

        @JavascriptInterface
        fun setShuffle(enabled: Int) {
            runOnUiThread {
                shuffleOn = enabled == 1
                sharedPreferences.edit().putBoolean("shuffleOn", shuffleOn).apply()
                resetQueue()
            }
        }

        @JavascriptInterface
        fun saveMessageTemplate(template: String) {
            runOnUiThread {
                try {
                    if (template.trim().isEmpty()) {
                        showNativeToast("Template cannot be empty")
                        return@runOnUiThread
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
            runOnUiThread {
                val sec = seconds.coerceIn(1, 300)
                callIntervalMs = sec * 1000L
                sharedPreferences.edit().putLong("callIntervalMs", callIntervalMs).apply()
                webView.evaluateJavascript(
                    "window.updateFromAndroid({callIntervalSec: $sec});",
                    null
                )
            }
        }

        @JavascriptInterface
        fun updateAdminNumber(number: String) {
            runOnUiThread {
                val digits = number.filter { it.isDigit() }.takeLast(11)
                if (digits.length == 11) {
                    adminNumber = digits
                    sharedPreferences.edit().putString("adminNumber", adminNumber).apply()
                    webView.evaluateJavascript(
                        "window.updateFromAndroid({adminNumber: '$adminNumber'});",
                        null
                    )
                    showNativeToast("Admin number saved")
                } else {
                    showNativeToast("Admin number must be 11 digits")
                }
            }
        }

        @JavascriptInterface
        fun sendSms(number: String, message: String) {
            runOnUiThread {
                try {
                    if (number.isEmpty() || message.isEmpty()) {
                        showNativeToast("Number or message is empty")
                        return@runOnUiThread
                    }

                    if (!ensureSmsPermission()) {
                        showNativeToast("Grant SMS permission to proceed")
                        return@runOnUiThread
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
                    webView.evaluateJavascript(
                        "window.updateFromAndroid({toast: 'SMS sent successfully to $cleanNumber'});",
                        null
                    )
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error sending SMS", e)
                    showNativeToast("Error: ${e.message}")
                    webView.evaluateJavascript(
                        "window.updateFromAndroid({toast: 'Failed to send SMS: ${e.message}'});",
                        null
                    )
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
            android.widget.Toast.makeText(
                this@MainActivity,
                message,
                android.widget.Toast.LENGTH_SHORT
            ).show()
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
            // Build CSV content
            val csvBuilder = StringBuilder()
            csvBuilder.append("Time,Number,Status,Duration\n")
            for (i in 0 until callHistory.length()) {
                val obj = callHistory.getJSONObject(i)
                val time = obj.optString("time")
                val number = obj.optString("number")
                val status = obj.optString("status")
                val duration = obj.optString("duration")
                csvBuilder.append("$time,$number,$status,$duration\n")
            }
            val csvString = csvBuilder.toString()
            val csvBase64 = Base64.encodeToString(csvString.toByteArray(), Base64.NO_WRAP)
            val today = dateFormat.format(Date())
            val filename = "$agentName - $today - $branchName.csv"
            sendReportToServer(csvBase64, filename)
        } catch (e: Exception) {
            showNativeToast("Failed to export report: ${e.message}")
        }
    }

    private fun sendReportToServer(csvBase64: String, filename: String) {
        Thread {
            try {
                val client = OkHttpClient()
                val json = """{
                \"agentName\": \"$agentName\",
                \"branch\": \"$branchName\",
                \"date\": \"${dateFormat.format(Date())}\",
                \"csv\": \"$csvBase64\"
            }"""
                val body = RequestBody.create("application/json".toMediaTypeOrNull(), json)
                val request = Request.Builder()
                    .url(expressOnVercel + "reports")
                    .post(body)
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    runOnUiThread { showNativeToast("Failed to send report: ${response.message}") }
                } else {
                    runOnUiThread { showNativeToast("Report sent successfully!") }
                }
            } catch (e: Exception) {
                runOnUiThread { showNativeToast("Error sending report: ${e.message}") }
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

            webView.evaluateJavascript(
                "window.updateFromAndroid({toast: '✓ Auto SMS sent'});",
                null
            )

            Log.d("MainActivity", "=== SMS SENT SUCCESSFULLY ===")

        } catch (e: Exception) {
            Log.e("MainActivity", "FAILED TO SEND SMS", e)
            showNativeToast("SMS failed: ${e.message}")
        }
    }

    private fun enterImmersive() {
        try {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } catch (_: Exception) {}
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
    }

    private fun runCountdownThenDial() {
        if (isCountdownRunning) return
        var seconds = (callIntervalMs / 1000).coerceAtLeast(1)
        isCountdownRunning = true
        countdownHandler = android.os.Handler(mainLooper)
        val runnable = object : Runnable {
            override fun run() {
                if (isPaused) {
                    isCountdownRunning = false
                    webView.evaluateJavascript("window.updateFromAndroid({countdown: 0});", null)
                    return
                }
                webView.evaluateJavascript("window.updateFromAndroid({countdown: " + seconds + "});", null)
                if (seconds <= 0L) {
                    isCountdownRunning = false
                    webView.evaluateJavascript("window.updateFromAndroid({countdown: 0});", null)
                    if (!isPaused && queueIndex < callQueue.size) {
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
        webView.evaluateJavascript("window.updateFromAndroid({countdown: 0});", null)
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
            webView.evaluateJavascript(
                "window.updateFromAndroid({toast: 'Auto SMS sent to last number'});",
                null
            )
        } catch (e: Exception) {
            Log.e("MainActivity", "Error sending auto SMS", e)
            showNativeToast("Auto SMS failed: ${e.message}")
        }
    }

    private fun showNativeToast(message: String) {
        val toast = Toast.makeText(this, message, Toast.LENGTH_SHORT)
        toast.show()
        }

    override fun onDestroy() {
        super.onDestroy()
        try {
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error unregistering phone state listener", e)
        }
    }
}