package com.nikre.assistant

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference

/**
 * Telefon ichida (internetsiz) ishlaydigan kichik AI model bilan ishlash.
 * Google'ning MediaPipe LLM Inference kutubxonasidan foydalanadi — bu
 * tayyor, oldindan qurilgan kutubxona, o'zimiz C++ kod yozishimiz shart emas.
 *
 * DIQQAT: bu kod MediaPipe'ning taxminiy (xotiradan yozilgan) API'siga
 * asoslangan. Birinchi qurishda (build) kichik moslashtirishlar kerak
 * bo'lishi mumkin — bu kutilgan holat, xato emas.
 */
object LocalAi {

    private var llm: LlmInference? = null

    var isReady: Boolean = false
        private set

    /** Model faylining ilova ichidagi doimiy joylashuvi. */
    fun modelFile(context: Context): java.io.File =
        java.io.File(context.filesDir, "nikre_model.task")

    fun isModelDownloaded(context: Context): Boolean = modelFile(context).exists()

    /**
     * Model faylidan AI dvigatelini ishga tushiradi. Og'ir operatsiya —
     * albatta fon oqimida (background thread) chaqirilishi kerak.
     */
    @Synchronized
    fun init(context: Context): Boolean {
        return try {
            unload()
            val file = modelFile(context)
            if (!file.exists()) return false

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(file.absolutePath)
                .setMaxTokens(512)
                .build()

            llm = LlmInference.createFromOptions(context, options)
            isReady = true
            true
        } catch (e: Exception) {
            isReady = false
            llm = null
            false
        }
    }

    /**
     * Savolga javob so'raydi. Og'ir operatsiya — fon oqimida chaqiriladi.
     * Bir necha soniya davom etishi mumkin.
     */
    @Synchronized
    fun ask(prompt: String): String {
        val engine = llm ?: return "AI model hali yuklanmagan."
        return try {
            val raw = engine.generateResponse(prompt)
            raw?.trim()?.ifBlank { "Javob topa olmadim." } ?: "Javob topa olmadim."
        } catch (e: Exception) {
            "AI javob berishda xatolik yuz berdi: ${e.message ?: "noma'lum xato"}"
        }
    }

    @Synchronized
    fun unload() {
        try {
            llm?.close()
        } catch (e: Exception) {
            // e'tiborsiz qoldiramiz
        }
        llm = null
        isReady = false
    }
}
