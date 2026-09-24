package com.nikre.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import java.util.Locale

/**
 * Nikre'ning asosiy ekrani.
 *
 * Hozirgi bosqichda: tugmani bosib gapirish -> tizim tanib oladi (online/offline,
 * telefon sozlamalariga bog'liq) -> CommandProcessor javob tayyorlaydi -> TTS orqali
 * ovozda aytiladi.
 *
 * Keyingi bosqichlarda: "Hey Nikre" wake word va fonda doim tinglash qo'shiladi.
 */
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var statusText: TextView
    private lateinit var heardText: TextView
    private lateinit var responseText: TextView
    private lateinit var micButton: Button

    private lateinit var tts: TextToSpeech
    private var ttsReady = false

    private val requiredPermissions = mutableListOf(
        Manifest.permission.RECORD_AUDIO
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permissionRequestCode = 1001

    // Ovoz tanish natijasini qabul qilish
    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val results = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val said = results?.firstOrNull().orEmpty()
            if (said.isNotBlank()) {
                heardText.text = "Siz aytdingiz: \"$said\""
                val response = CommandProcessor.process(said)
                responseText.text = response
                speak(response)
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

    // TTS tayyor bo'lganda chaqiriladi
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale("uz"))
            // Agar o'zbek tili qurilmada bo'lmasa, ruscha yoki inglizchaga tushamiz
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.US)
            }
            ttsReady = true
            statusText.text = "Nikre tayyor. Mikrofon tugmasini bosing va gapiring."
        } else {
            statusText.text = "Ovozli javob (TTS) ishga tushmadi, lekin tinglash ishlaydi."
        }
    }

    private fun speak(text: String) {
        if (ttsReady) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nikre_response")
        }
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
