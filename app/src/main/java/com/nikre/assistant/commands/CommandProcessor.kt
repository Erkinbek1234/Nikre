package com.nikre.assistant.commands

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Nikre javob (yoki bajarilishi kerak bo'lgan amal) qaytaradi.
 *
 * Ikki turdagi moslik ishlatiladi:
 *  - to'g'ridan-to'g'ri (substring) moslik
 *  - "fuzzy" moslik: agar tinglash noaniq bo'lsa ("alom" o'rniga "salom"),
 *    bitta-ikkita harf farq bo'lsa ham baribir tanib oladi (Levenshtein masofasi orqali)
 */
sealed class CommandResult {
    data class Speak(val text: String) : CommandResult()
    data class OpenUrl(val url: String, val speakText: String) : CommandResult()
    data class OpenDialer(val speakText: String) : CommandResult()
    data class OpenSystemAction(val action: String, val speakText: String) : CommandResult()
    data class OpenAppByName(val query: String, val speakText: String) : CommandResult()
    data class ToggleFlashlight(val speakText: String) : CommandResult()
    data class ChangeVolume(val up: Boolean, val speakText: String) : CommandResult()
}

object CommandProcessor {

    private val jokes = listOf(
        "Kompyuter nega sovuq qotdi? Chunki oynasini ochib qo'yishgan edi.",
        "Robotlar hech qachon yolg'on gapirmaydi — ular faqat noto'g'ri kod yozadi.",
        "Nega dasturchi qorong'uda ishlaydi? Chunki u \"light\" so'zini yoqishni unutgan.",
        "Wi-Fi bilan sevgi bir xil — ikkalasi ham \"ulanmadi\" deganda his qilinadi.",
        "Nega robot dorixonaga bordi? Chunki uning \"battery\"si tugab qolgandi."
    )

    private val motivations = listOf(
        "Har bir kichik qadam katta natijaga olib boradi.",
        "Bugun qilgan mehnatingiz ertangi muvaffaqiyatingiz.",
        "Qiyinchilik — bu kuchayish uchun imkoniyat.",
        "Siz o'ylaganingizdan ham kuchlisiz."
    )

    private val greetings = listOf(
        "Salom! Sizga qanday yordam bera olaman?",
        "Assalomu alaykum! Tinglovdaman.",
        "Salom, xizmatingizdaman!"
    )

    fun process(context: Context, heardText: String): CommandResult {
        val text = normalize(heardText)

        return when {
            fuzzyAny(text, "salom", "assalomu", "salam", "hey nikre") ->
                CommandResult.Speak(timeBasedGreeting())

            fuzzyAny(text, "soat nechi", "vaqt nechi", "soat necha") ->
                CommandResult.Speak("Hozir soat " + currentTime() + ".")

            fuzzyAny(text, "bugun necha sana", "bugun sana", "nechanchi sana", "qaysi kun") ->
                CommandResult.Speak(currentDate())

            fuzzyAny(text, "ismi ni", "isming nima", "sen kim", "sen kimsan") ->
                CommandResult.Speak("Men Nikre — sizning shaxsiy yordamchingizman.")

            fuzzyAny(text, "batareya", "batareyka", "zaryad") ->
                CommandResult.Speak("Batareya darajasi " + batteryLevel(context) + " foiz.")

            fuzzyAny(text, "fonarik yoq", "chiroq yoq", "fonar yoq") ->
                CommandResult.ToggleFlashlight("Fonarikni yoqyapman.")

            fuzzyAny(text, "fonarik och", "chiroq och", "fonar och") ->
                CommandResult.ToggleFlashlight("Fonarikni o'chiryapman.")

            fuzzyAny(text, "ovozni baland", "tovushni baland", "ovoz oshir") ->
                CommandResult.ChangeVolume(true, "Ovozni balandlashtiryapman.")

            fuzzyAny(text, "ovozni past", "tovushni past", "ovoz kamayt") ->
                CommandResult.ChangeVolume(false, "Ovozni pasaytiryapman.")

            fuzzyAny(text, "sozlama och", "sozlamalar och", "sozlamalarni och") ->
                CommandResult.OpenSystemAction("SETTINGS", "Sozlamalarni ochyapman.")

            fuzzyAny(text, "kamera och", "surat ol", "rasmga ol") ->
                CommandResult.OpenSystemAction("CAMERA", "Kamerani ochyapman.")

            fuzzyAny(text, "galereya och", "rasmlarni och", "suratlarni och") ->
                CommandResult.OpenSystemAction("GALLERY", "Galereyani ochyapman.")

            fuzzyAny(text, "kontakt och", "kontaktlar och", "aloqalar och") ->
                CommandResult.OpenSystemAction("CONTACTS", "Kontaktlarni ochyapman.")

            fuzzyAny(text, "sms yubor", "xabar yubor", "habar yubor") ->
                CommandResult.OpenSystemAction("SMS", "SMS yuborish oynasini ochyapman.")

            fuzzyAny(text, "brauzer och", "internet och", "google och") ->
                CommandResult.OpenUrl("https://www.google.com", "Brauzerni ochyapman.")

            fuzzyAny(text, "raqam ter", "telefon och", "qongiroq") ->
                CommandResult.OpenDialer("Raqam terish ekranini ochyapman.")

            text.contains("youtube") && text.contains("qidir") ->
                CommandResult.OpenUrl(searchUrl("https://www.youtube.com/results?search_query=", afterWord(text, "qidir")), "YouTube'dan qidiryapman.")

            fuzzyAny(text, "wikipedia", "vikipediya") ->
                CommandResult.OpenUrl(searchUrl("https://uz.wikipedia.org/wiki/Maxsus:Qidiruv?search=", afterWord(text, "qidir")), "Vikipediyadan qidiryapman.")

            fuzzyAny(text, "xarita", "karta", "joylashuv toping") ->
                CommandResult.OpenUrl(searchUrl("https://www.google.com/maps/search/", afterWord(text, "qidir")), "Xaritadan qidiryapman.")

            text.contains("qidir") ->
                CommandResult.OpenUrl(searchUrl("https://www.google.com/search?q=", afterWord(text, "qidir")), "Qidiryapman.")

            fuzzyAny(text, "tanga tashla", "tanga otish") ->
                CommandResult.Speak(if (listOf("bosh", "qiya").random() == "bosh") "Bosh tushdi." else "Qiya tushdi.")

            fuzzyAny(text, "zar tashla", "kub tashla", "zarcha tashla") ->
                CommandResult.Speak("Zarcha " + (1..6).random() + " tushdi.")

            fuzzyAny(text, "tasodifiy raqam", "random raqam") ->
                CommandResult.Speak("Tasodifiy raqam: " + (1..100).random())

            fuzzyAny(text, "hazil", "kulgili gap", "kul") ->
                CommandResult.Speak(jokes.random())

            fuzzyAny(text, "motivatsiya", "rag'bat", "ruhlantir") ->
                CommandResult.Speak(motivations.random())

            fuzzyAny(text, "rahmat", "tashakkur") ->
                CommandResult.Speak("Arzimaydi! Yana kerak bo'lsa, chaqiring.")

            fuzzyAny(text, "xayr", "hayr", "ko'rishguncha") ->
                CommandResult.Speak("Xayr! Yana ko'rishguncha.")

            calculate(text) != null ->
                CommandResult.Speak("Javob: " + calculate(text))

            text.startsWith("och ") || (text.contains("ochib ber") && !text.contains("sozlama")) ->
                CommandResult.OpenAppByName(extractAppName(text), "Ochyapman.")

            else ->
                CommandResult.Speak(pickFallback())
        }
    }

    // ---------- YORDAMCHI FUNKSIYALAR ----------

    private fun normalize(input: String): String =
        input.lowercase(Locale.getDefault()).trim()

    private fun fuzzyAny(text: String, vararg phrases: String): Boolean =
        phrases.any { phrase ->
            if (text.contains(phrase)) return@any true
            // Fuzzy: har bir so'zni alohida solishtiramiz (kichik xatolarga chidamli)
            val phraseWords = phrase.split(" ")
            val textWords = text.split(" ")
            phraseWords.all { pw -> textWords.any { tw -> levenshtein(tw, pw) <= 1 } }
        }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[a.length][b.length]
    }

    private fun currentTime(): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    private fun currentDate(): String =
        SimpleDateFormat("dd MMMM yyyy, EEEE", Locale.getDefault()).format(Date())

    private fun timeBasedGreeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val timeOfDay = when (hour) {
            in 5..10 -> "Xayrli tong"
            in 11..16 -> "Xayrli kun"
            in 17..21 -> "Xayrli kech"
            else -> "Xayrli tun"
        }
        return "$timeOfDay! " + greetings.random()
    }

    private fun batteryLevel(context: Context): Int {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, filter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale) else -1
    }

    private fun searchUrl(base: String, query: String): String =
        base + URLEncoder.encode(query.ifBlank { " " }, "UTF-8")

    private fun afterWord(text: String, marker: String): String {
        val idx = text.indexOf(marker)
        return if (idx == -1) text else text.substring(idx + marker.length).trim()
    }

    private fun extractAppName(text: String): String =
        text.removePrefix("och").replace("ochib ber", "").trim()

    private fun pickFallback(): String = listOf(
        "Kechirasiz, bu buyruqni hali bilmayman.",
        "Buni hali o'rganmadim, keyinroq qo'shiladi.",
        "Tushunmadim, boshqacha ayting."
    ).random()

    /**
     * Juda sodda matematik ifodani hisoblaydi: "5 qo'shsa 3", "10 ayirsa 4",
     * "6 ko'paytirilsa 7", "20 bo'linsa 4". Agar mos kelmasa, null qaytaradi.
     */
    private fun calculate(text: String): Double? {
        val patterns = listOf(
            Regex("(\\d+(?:\\.\\d+)?)\\s*(qo'shsa|qoshsa|plyus|\\+)\\s*(\\d+(?:\\.\\d+)?)") to { a: Double, b: Double -> a + b },
            Regex("(\\d+(?:\\.\\d+)?)\\s*(ayirsa|minus|-)\\s*(\\d+(?:\\.\\d+)?)") to { a: Double, b: Double -> a - b },
            Regex("(\\d+(?:\\.\\d+)?)\\s*(ko'paytirilsa|kopaytirilsa|carpa|\\*|x)\\s*(\\d+(?:\\.\\d+)?)") to { a: Double, b: Double -> a * b },
            Regex("(\\d+(?:\\.\\d+)?)\\s*(bo'linsa|bolinsa|/)\\s*(\\d+(?:\\.\\d+)?)") to { a: Double, b: Double -> if (b != 0.0) a / b else Double.NaN }
        )
        for ((regex, op) in patterns) {
            val match = regex.find(text) ?: continue
            val a = match.groupValues[1].toDoubleOrNull() ?: continue
            val b = match.groupValues[3].toDoubleOrNull() ?: continue
            val result = op(a, b)
            return if (result.isNaN()) null else result
        }
        return null
    }
}
