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
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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

    private val modelPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) copyModelFile(uri)
    }

    private fun copyModelFile(uri: Uri) {
        Toast.makeText(this, "Model fayli nusxalanmoqda, biroz kuting...", Toast.LENGTH_LONG).show()
        Thread {
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    LocalAi.modelFile(this).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                val ok = LocalAi.init(this)
                runOnUiThread {
                    val msg = if (ok) "AI model tayyor! Endi savollarga javob bera oladi."
                    else "Model fayli yuklanmadi — fayl to'g'ri formatda emasligi mumkin."
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Xatolik: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
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

        if (LocalAi.isModelDownloaded(this)) {
            Thread { LocalAi.init(this) }.start()
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

    private var settingsDialog: android.app.Dialog? = null
    private lateinit var settingsRoot: FrameLayout
    private lateinit var settingsMainPanel: LinearLayout
    private lateinit var settingsDetailPanel: LinearLayout

    private val langNames = listOf("O'zbekcha", "Ruscha", "Inglizcha", "Turkcha", "Turkmancha")
    private val langCodes = listOf("uz-UZ", "ru-RU", "en-US", "tr-TR", "tk-TM")

    /** To'liq ekranli sozlamalar oynasi — bo'lim-bo'lim (menyu -> ichki sahifa). */
    private fun showSettingsDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()

        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        settingsDialog = dialog

        settingsRoot = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
        }
        settingsMainPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        settingsDetailPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            visibility = View.GONE
        }
        settingsRoot.addView(settingsMainPanel)
        settingsRoot.addView(settingsDetailPanel)
        dialog.setContentView(settingsRoot)

        buildSettingsMainPanel()

        dialog.setOnDismissListener { settingsDialog = null }
        dialog.show()
    }

    private fun closeSettingsDialog() {
        settingsDialog?.dismiss()
        settingsDialog = null
    }

    private fun showMainPanel() {
        settingsDetailPanel.visibility = View.GONE
        settingsMainPanel.visibility = View.VISIBLE
    }

    private fun showDetailPanel() {
        settingsMainPanel.visibility = View.GONE
        settingsDetailPanel.visibility = View.VISIBLE
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 22f
        setTextColor(Color.parseColor("#222222"))
        setPadding(0, 0, 0, (24 * resources.displayMetrics.density).toInt())
    }

    private fun menuRow(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 17f
        setPadding(0, 28, 0, 28)
        setTextColor(Color.parseColor("#222222"))
        isClickable = true
        isFocusable = true
        background = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.list_selector_background)
        setOnClickListener { onClick() }
    }

    private fun buildSettingsMainPanel() {
        settingsMainPanel.removeAllViews()
        settingsMainPanel.addView(sectionTitle("Sozlamalar"))

        settingsMainPanel.addView(Switch(this).apply {
            text = "\"Nikre\" deb chaqirish rejimi"
            textSize = 16f
            isChecked = wakeMode
            setOnCheckedChangeListener { _, checked -> setWakeMode(checked) }
        })

        settingsMainPanel.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (24 * resources.displayMetrics.density).toInt())
        })

        val langLabel = langNames.getOrElse(langCodes.indexOf(recognizerLanguage)) { "O'zbekcha" }
        settingsMainPanel.addView(menuRow("🌐  Til: $langLabel") { buildLanguagePanel(); showDetailPanel() })

        val voiceLabel = selectedVoiceName?.take(20) ?: "Standart"
        settingsMainPanel.addView(menuRow("🔊  Ovoz: $voiceLabel") { buildVoicePanel(); showDetailPanel() })

        val aiStatus = if (LocalAi.isReady) "tayyor" else if (LocalAi.isModelDownloaded(this)) "fayl bor, ishga tushmagan" else "yuklanmagan"
        settingsMainPanel.addView(menuRow("🤖  Offline AI model: $aiStatus") { buildAiPanel(); showDetailPanel() })

        settingsMainPanel.addView(menuRow("💬  Yangi suhbat boshlash") {
            chatContainer.removeAllViews()
            chatPrefs.edit().remove("history").apply()
            addMessage("Salom! Men Nikre. \"Nikre\" deb chaqiring yoki pastga yozing.", isUser = false)
            closeSettingsDialog()
        })

        settingsMainPanel.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (32 * resources.displayMetrics.density).toInt())
        })

        settingsMainPanel.addView(Button(this).apply {
            text = "Yopish"
            setOnClickListener { closeSettingsDialog() }
        })
    }

    private fun buildLanguagePanel() {
        settingsDetailPanel.removeAllViews()
        settingsDetailPanel.addView(menuRow("← Orqaga") { showMainPanel() })
        settingsDetailPanel.addView(sectionTitle("Tilni tanlang"))
        for (i in langNames.indices) {
            settingsDetailPanel.addView(menuRow(langNames[i]) {
                val newLang = langCodes[i]
                recognizerLanguage = newLang
                appPrefs.edit().putString("lang", newLang).apply()
                applyTtsLanguage(newLang)
                buildSettingsMainPanel()
                showMainPanel()
            })
        }
    }

    private val voiceSampleText = "Salom! Men Nikre. Sizga qanday yordam bera olaman?"

    private fun buildVoicePanel() {
        settingsDetailPanel.removeAllViews()
        settingsDetailPanel.addView(menuRow("← Orqaga (tanlanganini saqlab)") { buildSettingsMainPanel(); showMainPanel() })
        settingsDetailPanel.addView(sectionTitle("Ovozni tinglang va tanlang"))
        settingsDetailPanel.addView(TextView(this).apply {
            text = "Har birini bosing — o'sha ovozda namuna gapiradi. Yoqqanini tanlang, keyin \"Orqaga\" bosing."
            textSize = 13f
            setPadding(0, 0, 0, 24)
        })
        // Faqat joriy tilga emas — qurilmadagi BARCHA mavjud ovozlardan tanlash imkonini beramiz
        val voices = availableVoices()
        if (voices.isEmpty()) {
            settingsDetailPanel.addView(TextView(this).apply {
                text = "Qurilmada qo'shimcha ovozlar topilmadi, standart ovoz ishlatiladi."
                textSize = 14f
                setPadding(0, 16, 0, 0)
            })
            return
        }
        for ((i, v) in voices.withIndex()) {
            val isSelected = v.name == selectedVoiceName
            val mark = if (isSelected) "✅ " else "▶️ "
            val row = menuRow("$mark Ovoz ${i + 1} — ${v.locale.displayLanguage} (${v.name.take(20)})") {
                selectedVoiceName = v.name
                appPrefs.edit().putString("voice", v.name).apply()
                try {
                    tts.voice = v
                    tts.speak(voiceSampleText, TextToSpeech.QUEUE_FLUSH, null, "voice_preview")
                } catch (e: Exception) {
                    Toast.makeText(this, "Bu ovozda xatolik, boshqasini sinab ko'ring.", Toast.LENGTH_SHORT).show()
                }
                buildVoicePanel()
                showDetailPanel()
            }
            settingsDetailPanel.addView(row)
        }
    }

    private fun buildAiPanel() {
        settingsDetailPanel.removeAllViews()
        settingsDetailPanel.addView(menuRow("← Orqaga") { showMainPanel() })
        settingsDetailPanel.addView(sectionTitle("Offline AI model"))
        settingsDetailPanel.addView(TextView(this).apply {
            text = if (LocalAi.isReady) "✅ Yuklangan va tayyor"
            else if (LocalAi.isModelDownloaded(this@MainActivity)) "⚠️ Fayl bor, lekin ishga tushmadi"
            else "❌ Hali yuklanmagan"
            textSize = 15f
            setPadding(0, 0, 0, 24)
        })
        settingsDetailPanel.addView(Button(this).apply {
            text = if (LocalAi.isModelDownloaded(this@MainActivity)) "Model faylini almashtirish" else "Model faylini tanlash"
            setOnClickListener { modelPickerLauncher.launch(arrayOf("*/*")) }
        })
    }

    // O'zbekcha talaffuzga yaqinroq keladigan (lotin/krill yozuvli, fonetik yaqin) tillar.
    // Yapon, xitoy, arab, koreys va h.k. kabi talaffuzi butunlay boshqacha tillar chiqarib tashlanadi.
    private val compatibleVoiceLanguages = setOf(
        "uz", "tr", "az", "kk", "ky", "tt", "tg", "ru", "en", "de", "nl", "sv", "no"
    )

    /** Qurilmadagi, o'zbekcha talaffuzga mos keladigan TTS ovozlarini sifat bo'yicha saralab qaytaradi. */
    private fun availableVoices(): List<Voice> {
        return try {
            tts.voices
                ?.filterNotNull()
                ?.filter { it.locale.language in compatibleVoiceLanguages }
                ?.sortedByDescending { it.quality }
                ?.take(15)
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

            is CommandResult.AskAi -> {
                if (LocalAi.isReady) {
                    Toast.makeText(this, "O'ylayapman...", Toast.LENGTH_SHORT).show()
                    Thread {
                        val answer = LocalAi.ask(result.prompt)
                        runOnUiThread { respond(answer) }
                    }.start()
                } else {
                    respond(result.fallbackText)
                }
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
    private fun levenshteinLocal(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[a.length][b.length]
    }

    /**
     * Gapirilgan matnda "Nikre" so'ziga o'xshash so'zni qidiradi — ovoz tanish
     * noaniq eshitgan bo'lsa ham (masalan "nikra", "nikri", "nikr") baribir
     * taniydi. Topilmasa null, buyruq bo'sh bo'lsa "" qaytaradi.
     */
    private fun extractAfterWakeWord(said: String): String? {
        val lower = said.lowercase(Locale.getDefault())
        val words = lower.split(" ", ",", ".", "!", "?").filter { it.isNotBlank() }
        for (w in words) {
            val tolerance = if (w.length <= 5) 2 else 3
            if (levenshteinLocal(w, "nikre") <= tolerance) {
                // Shu so'zdan keyingi qismni original matndan ajratib olamiz
                val wordStart = lower.indexOf(w)
                val afterIdx = wordStart + w.length
                return if (afterIdx < said.length) said.substring(afterIdx).trim() else ""
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
