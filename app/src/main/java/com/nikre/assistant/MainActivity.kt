package com.nikre.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.nikre.assistant.commands.CommandProcessor
import com.nikre.assistant.commands.CommandResult
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var chatScroll: ScrollView
    private lateinit var chatContainer: LinearLayout
    private lateinit var textInput: EditText
    private lateinit var micButton: FrameLayout
    private lateinit var liveCaption: TextView
    private lateinit var waveform: WaveformView
    private lateinit var menuButton: TextView

    private val appPrefs by lazy { getSharedPreferences("nikre_settings", Context.MODE_PRIVATE) }
    private val chatPrefs by lazy { getSharedPreferences("nikre_chat", Context.MODE_PRIVATE) }
    private var recognizerLanguage = "uz-UZ"
    private var selectedVoiceName: String? = null

    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var flashlightOn = false

    private val requiredPermissions = mutableListOf(
        Manifest.permission.RECORD_AUDIO
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permissionRequestCode = 1001

    private lateinit var speechRecognizer: SpeechRecognizer
    private var isListening = false

    // "Nikre" deb chaqirish rejimi
    private var wakeMode = false
    private var expectCommand = false
    private var pendingWakeEnable = false
    private val wakeRestart = Runnable { restartWakeListening() }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onBeginningOfSpeech() {
            if (wakeMode && !expectCommand) return
            liveCaption.text = ""
            liveCaption.visibility = View.VISIBLE
            waveform.start()
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Ovoz balandligiga qarab mikrofon tugmasi "nafas olganday" kattalashadi —
            // Nikre'ning o'ziga xos, tovushga reaktiv effekti (tayyor animatsiya nusxasi emas)
            val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            val scale = 1f + level * 0.35f
            micButton.animate().scaleX(scale).scaleY(scale).setDuration(80).start()
            waveform.setLevel(level)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            resetMicVisual()
            liveCaption.visibility = View.GONE
            waveform.stop()
        }

        override fun onError(error: Int) {
            isListening = false
            resetMicVisual()
            liveCaption.visibility = View.GONE
            waveform.stop()
            if (wakeMode) {
                // Chaqirish rejimida "jim" xatolar (hech narsa eshitilmadi) e'tiborsiz qoldiriladi
                expectCommand = false
                val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500L else 400L
                scheduleWakeRestart(delay)
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    Toast.makeText(this@MainActivity, "Mikrofonga ruxsat kerak.", Toast.LENGTH_SHORT).show()
                }
                return
            }
            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "Tushunmadim, qaytadan ayting."
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Ovoz eshitilmadi."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    "Internet aloqasini tekshiring."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofonga ruxsat kerak."
                else -> "Xatolik yuz berdi, qaytadan urinib ko'ring."
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            resetMicVisual()
            liveCaption.visibility = View.GONE
            waveform.stop()
            val said = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()

            if (wakeMode && !expectCommand) {
                // Faqat "Nikre" so'zini kutamiz
                val afterWake = extractAfterWakeWord(said)
                if (afterWake == null) {
                    scheduleWakeRestart(200)
                } else if (afterWake.isBlank()) {
                    vibrate(60)
                    expectCommand = true
                    respond("Eshitaman")
                    scheduleWakeRestart(900)
                } else {
                    vibrate(60)
                    addMessage(said, isUser = true)
                    handleCommand(afterWake)
                    scheduleWakeRestart(900)
                }
                return
            }

            expectCommand = false
            if (said.isNotBlank()) {
                addMessage(said, isUser = true)
                handleCommand(said)
            }
            if (wakeMode) scheduleWakeRestart(900)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (wakeMode && !expectCommand) return
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (partial.isNotBlank()) {
                liveCaption.text = partial
                liveCaption.visibility = View.VISIBLE
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        chatScroll = findViewById(R.id.chatScroll)
        chatContainer = findViewById(R.id.chatContainer)
        textInput = findViewById(R.id.textInput)
        micButton = findViewById(R.id.micButton)
        liveCaption = findViewById(R.id.liveCaption)
        waveform = findViewById(R.id.waveform)
        menuButton = findViewById(R.id.menuButton)

        recognizerLanguage = appPrefs.getString("lang", "uz-UZ") ?: "uz-UZ"
        selectedVoiceName = appPrefs.getString("voice", null)
        wakeMode = appPrefs.getBoolean("wake", true)

        tts = TextToSpeech(this, this)

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(recognitionListener)

        micButton.setOnClickListener {
            if (!hasAllPermissions()) {
                ActivityCompat.requestPermissions(this, requiredPermissions, permissionRequestCode)
                return@setOnClickListener
            }
            if (wakeMode) {
                Toast.makeText(this, "\"Nikre\" rejimi yoqilgan, gapirishingiz kifoya.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isListening) {
                speechRecognizer.stopListening()
                isListening = false
                resetMicVisual()
                liveCaption.visibility = View.GONE
            } else {
                startListening()
            }
        }

        menuButton.setOnClickListener { showSettingsDialog() }

        textInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTypedMessage()
                true
            } else {
                false
            }
        }

        val history = loadChatHistory()
        if (history.isEmpty()) {
            addMessage("Salom! Men Nikre. \"Nikre\" deb chaqiring yoki pastga yozing.", isUser = false)
        } else {
            for ((text, isUser) in history) {
                addMessage(text, isUser, save = false)
            }
        }

        if (wakeMode) {
            if (hasAllPermissions()) {
                startWakeListening()
            } else {
                pendingWakeEnable = true
                ActivityCompat.requestPermissions(this, requiredPermissions, permissionRequestCode)
            }
        }
    }

    private fun setWakeMode(enabled: Boolean) {
        wakeMode = enabled
        appPrefs.edit().putBoolean("wake", enabled).apply()
        if (enabled) {
            expectCommand = false
            if (hasAllPermissions()) {
                startWakeListening()
            } else {
                pendingWakeEnable = true
                ActivityCompat.requestPermissions(this, requiredPermissions, permissionRequestCode)
            }
        } else {
            handler.removeCallbacks(wakeRestart)
            speechRecognizer.cancel()
            isListening = false
            resetMicVisual()
            liveCaption.visibility = View.GONE
            waveform.stop()
        }
    }

    private fun showSettingsDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }

        val wakeRow = Switch(this).apply {
            text = "\"Nikre\" deb chaqirish rejimi"
            isChecked = wakeMode
        }
        container.addView(wakeRow)

        container.addView(TextView(this).apply {
            text = "Til:"
            setPadding(0, pad, 0, 8)
        })
        val langNames = listOf("O'zbekcha", "Ruscha", "Inglizcha", "Turkcha", "Turkmancha")
        val langCodes = listOf("uz-UZ", "ru-RU", "en-US", "tr-TR", "tk-TM")
        val langSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, langNames)
            val idx = langCodes.indexOf(recognizerLanguage)
            setSelection(if (idx >= 0) idx else 0)
        }
        container.addView(langSpinner)

        container.addView(TextView(this).apply {
            text = "Ovoz:"
            setPadding(0, pad, 0, 8)
        })
        val voices = availableVoicesForLanguage(recognizerLanguage.substring(0, 2))
        val voiceLabels = if (voices.isEmpty()) listOf("Standart ovoz") else voices.mapIndexed { i, v -> "Ovoz ${i + 1} (${v.name.take(18)})" }
        val voiceSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, voiceLabels)
            val savedIdx = voices.indexOfFirst { it.name == selectedVoiceName }
            setSelection(if (savedIdx >= 0) savedIdx else 0)
        }
        container.addView(voiceSpinner)

        if (voices.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "Bu til uchun qo'shimcha ovozlar topilmadi, standart ovoz ishlatiladi."
                textSize = 12f
                setPadding(0, 8, 0, 0)
            })
        }

        AlertDialog.Builder(this)
            .setTitle("Sozlamalar")
            .setView(container)
            .setPositiveButton("Saqlash") { _, _ ->
                val newLang = langCodes[langSpinner.selectedItemPosition]
                recognizerLanguage = newLang
                appPrefs.edit().putString("lang", newLang).apply()
                applyTtsLanguage(newLang)

                if (voices.isNotEmpty()) {
                    val chosen = voices[voiceSpinner.selectedItemPosition.coerceIn(0, voices.size - 1)]
                    selectedVoiceName = chosen.name
                    appPrefs.edit().putString("voice", chosen.name).apply()
                    try { tts.voice = chosen } catch (e: Exception) { }
                }

                setWakeMode(wakeRow.isChecked)
            }
            .setNegativeButton("Bekor qilish", null)
            .show()
    }

    private fun availableVoicesForLanguage(langPrefix: String): List<Voice> {
        return try {
            tts.voices?.filter { it.locale.language == langPrefix }
                ?.sortedByDescending { it.quality }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun applyTtsLanguage(tag: String) {
        try {
            val locale = Locale.forLanguageTag(tag)
            val result = tts.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "Bu til uchun ovoz mavjud emas, standart til ishlatiladi.", Toast.LENGTH_SHORT).show()
                tts.setLanguage(Locale.US)
            }
            selectBestVoice()
        } catch (e: Exception) {
            // standart tilda qoldiramiz
        }
    }

    private fun sendTypedMessage() {
        val text = textInput.text.toString().trim()
        if (text.isNotBlank()) {
            addMessage(text, isUser = true)
            textInput.setText("")
            handleCommand(text)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.forLanguageTag(recognizerLanguage))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.US)
            }
            val savedVoice = selectedVoiceName
            val restored = savedVoice != null && tts.voices?.any { it.name == savedVoice } == true
            if (restored) {
                try { tts.voice = tts.voices.first { it.name == savedVoice } } catch (e: Exception) { selectBestVoice() }
            } else {
                selectBestVoice()
            }
            tts.setSpeechRate(0.98f)
            tts.setPitch(1.0f)
            ttsReady = true
        }
    }

    /**
     * Google TTS mavjud bo'lsa, eng sifatli (Google Assistant'dagiga o'xshash tabiiy)
     * ovozni tanlaydi. Tarmoq talab qiladigan ovozlar odatda offline'ga qaraganda
     * ancha tabiiy va yoqimli chiqadi.
     */
    private fun selectBestVoice() {
        try {
            val currentLocale = tts.voice?.locale ?: return
            val candidates = tts.voices?.filter { v ->
                v.locale.language == currentLocale.language && !v.isNetworkConnectionRequired
            }?.ifEmpty {
                tts.voices?.filter { v -> v.locale.language == currentLocale.language }
            } ?: return

            val best = candidates
                .filter { it.name.contains("google", ignoreCase = true) }
                .maxByOrNull { it.quality }
                ?: candidates.maxByOrNull { it.quality }

            if (best != null) {
                tts.voice = best
            }
        } catch (e: Exception) {
            // Ovoz tanlashda xatolik bo'lsa, standart ovoz ishlatiladi
        }
    }

    private fun handleCommand(said: String) {
        when (val result = CommandProcessor.process(applicationContext, said)) {
            is CommandResult.Speak -> respond(result.text)

            is CommandResult.OpenUrl -> {
                respond(result.speakText)
                safeStart(Intent(Intent.ACTION_VIEW, Uri.parse(result.url)))
            }

            is CommandResult.OpenDialer -> {
                respond(result.speakText)
                safeStart(Intent(Intent.ACTION_DIAL))
            }

            is CommandResult.OpenSystemAction -> {
                respond(result.speakText)
                val intent = when (result.action) {
                    "SETTINGS" -> Intent(Settings.ACTION_SETTINGS)
                    "CAMERA" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                    "GALLERY" -> Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                    "CONTACTS" -> Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
                    "SMS" -> Intent(Intent.ACTION_VIEW, Uri.parse("sms:"))
                    "WIFI" -> Intent(Settings.ACTION_WIFI_SETTINGS)
                    "BLUETOOTH" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    "DND" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    "AIRPLANE" -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                    "BATTERY_SAVER" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                    "DISPLAY" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
                    "LOCATION" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    "LANGUAGE" -> Intent(Settings.ACTION_LOCALE_SETTINGS)
                    "SYNC" -> Intent(Settings.ACTION_SYNC_SETTINGS)
                    else -> null
                }
                intent?.let { safeStart(it) }
            }

            is CommandResult.OpenAppByName -> {
                val launched = openAppByName(result.query)
                respond(if (launched) result.speakText else "\"${result.query}\" nomli ilova topilmadi.")
            }

            is CommandResult.ToggleFlashlight -> {
                respond(result.speakText)
                toggleFlashlight()
            }

            is CommandResult.ChangeVolume -> {
                respond(result.speakText)
                changeVolume(result.up)
            }

            is CommandResult.StartTimer -> {
                respond(result.speakText)
                handler.postDelayed({
                    respond("⏰ Taymer tugadi!")
                    vibrate(300)
                }, result.seconds * 1000L)
            }

            is CommandResult.ClearChat -> {
                chatContainer.removeAllViews()
                chatPrefs.edit().remove("history").apply()
                respond(result.speakText)
            }
        }
    }

    private fun vibrate(ms: Long) {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(ms)
            }
        } catch (e: Exception) {
            // Vibratsiya mavjud bo'lmasa, e'tiborsiz qoldiramiz
        }
    }

    private fun respond(text: String) {
        addMessage(text, isUser = false)
        vibrate(40)
        speak(text)
    }

    private fun addMessage(text: String, isUser: Boolean, save: Boolean = true) {
        if (save) saveChatMessage(text, isUser)
        val bubble = TextView(this).apply {
            this.text = text
            textSize = 15f
            setPadding(28, 20, 28, 20)
            setTextColor(if (isUser) Color.WHITE else Color.parseColor("#222222"))
            background = ContextCompat.getDrawable(
                this@MainActivity,
                if (isUser) R.drawable.bubble_user else R.drawable.bubble_nikre
            )
            alpha = 0f
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (isUser) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12; bottomMargin = 12 }
        }
        row.addView(bubble)
        chatContainer.addView(row)
        bubble.animate().alpha(1f).setDuration(220).start()
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun saveChatMessage(text: String, isUser: Boolean) {
        try {
            val arr = JSONArray(chatPrefs.getString("history", "[]"))
            val obj = JSONObject()
            obj.put("t", text)
            obj.put("u", isUser)
            arr.put(obj)
            // Faqat oxirgi 200 ta xabarni saqlaymiz
            val trimmed = if (arr.length() > 200) {
                val newArr = JSONArray()
                for (i in (arr.length() - 200) until arr.length()) newArr.put(arr.get(i))
                newArr
            } else arr
            chatPrefs.edit().putString("history", trimmed.toString()).apply()
        } catch (e: Exception) {
            // saqlash muvaffaqiyatsiz bo'lsa, e'tiborsiz qoldiramiz
        }
    }

    private fun loadChatHistory(): List<Pair<String, Boolean>> {
        return try {
            val arr = JSONArray(chatPrefs.getString("history", "[]"))
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                o.getString("t") to o.getBoolean("u")
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun speak(text: String) {
        if (ttsReady) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nikre_response")
        }
    }

    private fun safeStart(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Bu amalni bajarib bo'lmadi.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleFlashlight() {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return
            flashlightOn = !flashlightOn
            cameraManager.setTorchMode(cameraId, flashlightOn)
        } catch (e: Exception) {
            Toast.makeText(this, "Fonarik topilmadi.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun changeVolume(up: Boolean) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
    }

    private fun openAppByName(query: String): Boolean {
        if (query.isBlank()) return false
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(mainIntent, 0)
        val normalizedQuery = query.lowercase(Locale.getDefault()).trim()

        val match = apps.firstOrNull { info ->
            val label = info.loadLabel(pm).toString().lowercase(Locale.getDefault())
            label.contains(normalizedQuery) || normalizedQuery.contains(label)
        } ?: return false

        val launchIntent = pm.getLaunchIntentForPackage(match.activityInfo.packageName) ?: return false
        safeStart(launchIntent)
        return true
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Bu qurilmada ovoz tanish mavjud emas.", Toast.LENGTH_LONG).show()
            return
        }
        vibrate(30)
        val intent = buildRecognizerIntent()
        try {
            isListening = true
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            isListening = false
            resetMicVisual()
            Toast.makeText(this, "Ovoz tanishni boshlab bo'lmadi.", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildRecognizerIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognizerLanguage)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
    }

    /**
     * "Nikre" rejimi: doimiy tinglaydi (ilova ochiq turganda), gapirilgan matnda
     * "nikre" so'zi bormi tekshiradi. Google popup chiqmaydi, bari shu ekranda.
     */
    private fun startWakeListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Bu qurilmada ovoz tanish mavjud emas.", Toast.LENGTH_LONG).show()
            setWakeMode(false)
            return
        }
        try {
            isListening = true
            speechRecognizer.startListening(buildRecognizerIntent())
        } catch (e: Exception) {
            isListening = false
            scheduleWakeRestart(800)
        }
    }

    private fun restartWakeListening() {
        if (!wakeMode) return
        try {
            speechRecognizer.cancel()
        } catch (e: Exception) {
            // e'tiborsiz qoldiramiz
        }
        startWakeListening()
    }

    private fun scheduleWakeRestart(delayMs: Long) {
        if (!wakeMode) return
        handler.removeCallbacks(wakeRestart)
        handler.postDelayed(wakeRestart, delayMs)
    }

    /**
     * Gapirilgan matnda "nikre" so'zini qidiradi. Topilmasa null, so'zdan keyingi
     * buyruq bo'sh bo'lsa "" (faqat chaqirilgan), aks holda buyruq matnini qaytaradi.
     */
    private fun extractAfterWakeWord(said: String): String? {
        val lower = said.lowercase(Locale.getDefault())
        val wakeWords = listOf("nikre", "nikra", "nikri")
        for (w in wakeWords) {
            val idx = lower.indexOf(w)
            if (idx >= 0) {
                return said.substring(idx + w.length).trim()
            }
        }
        return null
    }

    // Mikrofon tugmasini asl holatiga (1.0x) yumshoq qaytaradi
    private fun resetMicVisual() {
        micButton.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
    }

    private fun hasAllPermissions(): Boolean =
        requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCode && hasAllPermissions()) {
            if (pendingWakeEnable) {
                pendingWakeEnable = false
                setWakeMode(true)
            } else {
                startListening()
            }
        }
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        handler.removeCallbacksAndMessages(null)
        speechRecognizer.destroy()
        super.onDestroy()
    }
}
