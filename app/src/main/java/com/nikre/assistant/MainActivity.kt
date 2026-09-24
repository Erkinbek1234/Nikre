package com.nikre.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.nikre.assistant.commands.CommandProcessor
import com.nikre.assistant.commands.CommandResult
import java.util.Locale

/**
 * Nikre'ning asosiy ekrani.
 *
 * Tugmani bosib gapirish -> tizim tanib oladi -> CommandProcessor javob/amal tayyorlaydi
 * -> TTS orqali ovozda aytiladi va/yoki kerakli tizim amali bajariladi.
 */
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var statusText: TextView
    private lateinit var heardText: TextView
    private lateinit var responseText: TextView
    private lateinit var micButton: Button

    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var flashlightOn = false

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
        if (result.resultCode == RESULT_OK) {
            val results = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val said = results?.firstOrNull().orEmpty()
            if (said.isNotBlank()) {
                heardText.text = "Siz aytdingiz: \"$said\""
                handleCommand(said)
            } else {
                statusText.text = "Hech narsa eshitilmadi, qayta urining."
            }
        } else {
            statusText.text = "Tinglash bekor qilindi."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        heardText = findViewById(R.id.heardText)
        responseText = findViewById(R.id.responseText)
        micButton = findViewById(R.id.micButton)

        tts = TextToSpeech(this, this)

        micButton.setOnClickListener {
            if (hasAllPermissions()) {
                startListening()
            } else {
                ActivityCompat.requestPermissions(this, requiredPermissions, permissionRequestCode)
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale("uz"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.US)
            }
            ttsReady = true
            statusText.text = "Nikre tayyor. Mikrofon tugmasini bosing va gapiring."
        } else {
            statusText.text = "Ovozli javob (TTS) ishga tushmadi, lekin tinglash ishlaydi."
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
                if (launched) {
                    respond(result.speakText)
                } else {
                    respond("\"${result.query}\" nomli ilova topilmadi.")
                }
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
        responseText.text = text
        speak(text)
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
            Toast.makeText(this, "Fonarik topilmadi yoki qo'llab-quvvatlanmaydi.", Toast.LENGTH_SHORT).show()
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

    /**
     * Telefonda o'rnatilgan ilovalar ro'yxatidan, aytilgan nomga eng yaqinini topib ochadi.
     */
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
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "uz-UZ")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Nikre'ga ayting...")
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Ovoz tanish mavjud emas. Google ilovasi o'rnatilganini tekshiring.",
                Toast.LENGTH_LONG
            ).show()
        }
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
        super.onDestroy()
    }
}
