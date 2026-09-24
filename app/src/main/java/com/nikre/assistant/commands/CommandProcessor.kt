package com.nikre.assistant.commands

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Eng birinchi, eng sodda "aql": kalit so'zlarga qarab javob beradi.
 * Internet kerak emas, hech qanday tashqi xizmatga bog'liq emas.
 *
 * Keyingi bosqichlarda bu yerga:
 *  - vaqt asosidagi salomlashish
 *  - signal/eslatma qo'yish
 *  - qo'ng'iroq qilish, SMS yuborish
 *  - internet bo'lsa Claude API'ga yo'naltirish
 * kabi funksiyalar qo'shiladi.
 */
object CommandProcessor {

    fun process(heardText: String): String {
        val text = heardText.lowercase(Locale.getDefault()).trim()

        return when {
            containsAny(text, "salom", "assalomu", "hey nikre", "salam") ->
                "Salom! Men Nikre, sizga qanday yordam bera olaman?"

            containsAny(text, "soat nechi", "vaqt nechi", "soat necha") ->
                "Hozir soat " + currentTime() + "."

            containsAny(text, "ismi ni", "isming nima", "sen kim", "sen kimsan") ->
                "Men Nikre — sizning shaxsiy yordamchingizman."

            containsAny(text, "rahmat", "tashakkur") ->
                "Arzimaydi! Yana kerak bo'lsa, chaqiring."

            containsAny(text, "xayr", "hayr", "ko'rishguncha") ->
                "Xayr! Yana ko'rishguncha."

            else ->
                "Kechirasiz, bu buyruqni hali bilmayman. Men hali o'rganish bosqichidaman."
        }
    }

    private fun containsAny(text: String, vararg keywords: String): Boolean =
        keywords.any { text.contains(it) }

    private fun currentTime(): String {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        return formatter.format(Date())
    }
}
