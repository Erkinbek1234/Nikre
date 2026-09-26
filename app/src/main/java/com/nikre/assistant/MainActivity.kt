package com.nikre.assistant

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.nikre.assistant.commands.CommandProcessor
import com.nikre.assistant.commands.CommandResult
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var chatScroll: ScrollView
    private lateinit var chatContainer: LinearLayout
    private lateinit var textInput: EditText
    private lateinit var micButton: FrameLayout

    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var flashlightOn = false
    private var pulseAnimator: ValueAnimator? = null

    private val requiredPermissions = mutableListOf(
        Manifest.permission.RECORD_AUDIO
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permissionRequestCode = 1001

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        stopPulseAnimation()
        if (result.resultCode == RESULT_OK) {
            val results = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val said = results?.firstOrNull().orEmpty()
            if (said.isNotBlank()) {
                addMessage(said, isUser = true)
                handleCommand(said)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        chatScroll = findViewById(R.id.chatScroll)
        chatContainer = findViewById(R.id.chatContainer)
        textInput = findViewById(R.id.textInput)
        micButton = findViewById(R.id.micButton)

        tts = TextToSpeech(this, this)

        micButton.setOnClickListener {
            if (hasAllPermissions()) {
                startListening()
            } else {
                ActivityCompat.requestPermissions(this, requiredPermissions, permissionRequestCode)
            }
        }

        textInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTypedMessage()
                true
            } else {
                false
            }
        }

        addMessage("Salom! Men Nikre. Mikrofon tugmasini bosing yoki pastga yozing.", isUser = false)
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
            val result = tts.setLanguage(Locale("uz"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.US)
            }
            ttsReady = true
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
        }
    }

    private fun respond(text: String) {
        addMessage(text, isUser = false)
        speak(text)
    }

    private fun addMessage(text: String, isUser: Boolean) {
        val bubble = TextView(this).apply {
            this.text = text
            textSize = 15f
            setPadding(28, 20, 28, 20)
            setTextColor(if (isUser) Color.WHITE else Color.parseColor("#222222"))
            background = ContextCompat.getDrawable(
                this@MainActivity,
                if (isUser) R.drawable.bubble_user else R.drawable.bubble_nikre
            )
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
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
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
        startPulseAnimation()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "uz-UZ")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Nikre'ga ayting...")
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            stopPulseAnimation()
            Toast.makeText(
                this,
                "Ovoz tanish mavjud emas. Google ilovasi o'rnatilganini tekshiring.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Siri uslubidagi "pulslash" animatsiyasi: tinglayotganda mikrofon tugmasi kattalashib-kichraydi
    private fun startPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(1.0f, 1.25f).apply {
            duration = 500
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animation ->
                val scale = animation.animatedValue as Float
                micButton.scaleX = scale
                micButton.scaleY = scale
            }
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        micButton.scaleX = 1.0f
        micButton.scaleY = 1.0f
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
            startListening()
        }
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        stopPulseAnimation()
        super.onDestroy()
    }
}
