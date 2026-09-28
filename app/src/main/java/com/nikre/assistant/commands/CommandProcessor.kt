package com.nikre.assistant.commands

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
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
    data class StartTimer(val seconds: Int, val speakText: String) : CommandResult()
    data class ClearChat(val speakText: String) : CommandResult()
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

    private val wiseSayings = listOf(
        "Bilim — cheksiz xazina, undan qancha olsang ham kamaymaydi.",
        "Vaqtni behuda o'tkazgan, umrini behuda o'tkazadi.",
        "Kichik ishni ham sidqidildan qilgan katta ishga erishadi.",
        "Sabr — muvaffaqiyatning kalitidir.",
        "Har bir tugagan kun — yangi boshlanishning ibtidosi."
    )

    fun process(context: Context, heardText: String): CommandResult {
        val text = normalize(heardText)

        return when {
            fuzzyAny(text, "salom", "assalomu", "salam", "hey nikre") ->
                CommandResult.Speak(timeBasedGreeting())

            fuzzyAny(text, "nima qila olasan", "sen nima qilasan", "sen nimalar bilasan",
                "imkoniyating nima", "nimalar bilasan", "yordam bera olasanmi") ->
                CommandResult.Speak(capabilitiesText())

            fuzzyAny(text, "soat nechi", "vaqt nechi", "soat necha") ->
                CommandResult.Speak("Hozir soat " + currentTime() + ".")

            fuzzyAny(text, "bugun necha sana", "bugun sana", "nechanchi sana", "qaysi kun") ->
                CommandResult.Speak(currentDate())

            fuzzyAny(text, "ismi ni", "isming nima", "sen kim", "sen kimsan") ->
                CommandResult.Speak("Men Nikre — sizning shaxsiy yordamchingizman.")

            fuzzyAny(text, "batareya", "batareyka", "zaryad", "akkumulyator") ->
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

            fuzzyAny(text, "wifi och", "wifi sozlama", "wi-fi och") ->
                CommandResult.OpenSystemAction("WIFI", "Wi-Fi sozlamalarini ochyapman.")

            fuzzyAny(text, "bluetooth och", "blyutuz och") ->
                CommandResult.OpenSystemAction("BLUETOOTH", "Bluetooth sozlamalarini ochyapman.")

            fuzzyAny(text, "tinch rejim", "bezovta qilmang", "dnd yoq") ->
                CommandResult.OpenSystemAction("DND", "Tinch rejim sozlamalarini ochyapman.")

            fuzzyAny(text, "parvoz rejimi", "samolyot rejimi") ->
                CommandResult.OpenSystemAction("AIRPLANE", "Parvoz rejimi sozlamalarini ochyapman.")

            fuzzyAny(text, "batareya tejash", "quvvat tejash") ->
                CommandResult.OpenSystemAction("BATTERY_SAVER", "Batareya tejash sozlamalarini ochyapman.")

            fuzzyAny(text, "ekran sozlama", "displey sozlama", "yorqinlik sozlama") ->
                CommandResult.OpenSystemAction("DISPLAY", "Ekran sozlamalarini ochyapman.")

            fuzzyAny(text, "joylashuv sozlama", "gps sozlama", "lokatsiya sozlama") ->
                CommandResult.OpenSystemAction("LOCATION", "Joylashuv sozlamalarini ochyapman.")

            fuzzyAny(text, "til sozlama", "tilni ozgartir") ->
                CommandResult.OpenSystemAction("LANGUAGE", "Til sozlamalarini ochyapman.")

            fuzzyAny(text, "xotira qancha", "xotira holati", "diskda joy") ->
                CommandResult.Speak(storageInfo())

            fuzzyAny(text, "internet bormi", "aloqa bormi", "wifi ulanganmi") ->
                CommandResult.Speak(internetStatus(context))

            fuzzyAny(text, "hikmatli gap", "naql ayt", "donolik") ->
                CommandResult.Speak(wiseSayings.random())

            fuzzyAny(text, "qaysi yil", "necha yil", "yil nechan") ->
                CommandResult.Speak("Hozir " + currentYear() + "-yil.")

            fuzzyAny(text, "yangi yilgacha necha kun", "yangi yilgacha qancha qoldi") ->
                CommandResult.Speak(daysUntilNewYear())

            fuzzyAny(text, "sinxronizatsiya sozlama", "akkaunt sozlama") ->
                CommandResult.OpenSystemAction("SYNC", "Sinxronizatsiya sozlamalarini ochyapman.")

            fuzzyAny(text, "nechta ilova bor", "ilovalar soni", "qancha ilova ornatilgan") ->
                CommandResult.Speak(installedAppsCount(context))

            fuzzyAny(text, "tasodifiy harf") ->
                CommandResult.Speak("Tasodifiy harf: " + ('a'..'z').random().uppercase())

            repeatCount(text, "tanga") != null ->
                CommandResult.Speak(repeatCoinFlips(repeatCount(text, "tanga")!!))

            repeatCount(text, "zar") != null ->
                CommandResult.Speak(repeatDiceRolls(repeatCount(text, "zar")!!))

            fuzzyAny(text, "chatni tozala", "suhbatni tozala", "hammasini ochir") ->
                CommandResult.ClearChat("Suhbat tozalandi.")

            text.contains("play market") || (text.contains("ilova") && text.contains("qidir")) ->
                CommandResult.OpenUrl(searchUrl("https://play.google.com/store/search?q=", afterWord(text, "qidir")) + "&c=apps", "Play Marketdan qidiryapman.")

            text.startsWith("sozga aylantir") || text.startsWith("so'zga aylantir") ->
                CommandResult.Speak(numberToWords(text))

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

            fuzzyAny(text, "km ni milga", "kilometrni milga") ->
                CommandResult.Speak(convertUnit(text, "km_to_mile"))

            fuzzyAny(text, "milni kmga", "milni kilometrga") ->
                CommandResult.Speak(convertUnit(text, "mile_to_km"))

            fuzzyAny(text, "kgni funtga", "kilogrammni funtga") ->
                CommandResult.Speak(convertUnit(text, "kg_to_lb"))

            fuzzyAny(text, "funtni kgga", "funtni kilogrammga") ->
                CommandResult.Speak(convertUnit(text, "lb_to_kg"))

            fuzzyAny(text, "selsiyni farengeytga", "selsiydan farengeytga") ->
                CommandResult.Speak(convertUnit(text, "c_to_f"))

            fuzzyAny(text, "farengeytni selsiyga", "farengeytdan selsiyga") ->
                CommandResult.Speak(convertUnit(text, "f_to_c"))

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

            timerSeconds(text) != null ->
                CommandResult.StartTimer(timerSeconds(text)!!, "Taymer o'rnatildi.")

            text.contains("eslatma yoz") || text.contains("eslatma qosh") ->
                CommandResult.Speak(saveNote(context, afterWord(text, if (text.contains("eslatma yoz")) "eslatma yoz" else "eslatma qosh")))

            fuzzyAny(text, "eslatmalarim", "eslatmalarimni oqi", "eslatmalarni oqi") ->
                CommandResult.Speak(readNotes(context))

            fuzzyAny(text, "eslatmalarni ochir", "eslatmalarni tozala") ->
                CommandResult.Speak(clearNotes(context))

            percent(text) != null ->
                CommandResult.Speak("Javob: " + percent(text))

            sqrt(text) != null ->
                CommandResult.Speak("Javob: " + sqrt(text))

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

    private fun capabilitiesText(): String =
        "Men soat va sanani ayta olaman, batareya darajasini tekshiraman, " +
        "fonarikni yoqib-o'chiraman, ovozni sozlayman, sozlamalar, kamera, galereya, " +
        "kontaktlar va SMS'ni ochib beraman, brauzerda va YouTube'da qidiraman, " +
        "hazil va rag'batlantiruvchi gaplar aytaman, oddiy hisob-kitob qilaman, " +
        "taymer qo'yaman, eslatmalar saqlayman va istalgan ilovani ochib bera olaman."

    /**
     * "5 daqiqa taymer", "10 daqiqadan keyin eslat" kabi buyruqlardan soniyani ajratadi.
     */
    private fun timerSeconds(text: String): Int? {
        if (!text.contains("taymer") && !text.contains("eslat")) return null
        val match = Regex("(\\d+)\\s*(soniya|daqiqa|minut)").find(text) ?: return null
        val amount = match.groupValues[1].toIntOrNull() ?: return null
        val unit = match.groupValues[2]
        return if (unit == "soniya") amount else amount * 60
    }

    private const val NOTES_PREF = "nikre_notes"
    private const val NOTES_KEY = "notes_list"

    private fun saveNote(context: Context, note: String): String {
        if (note.isBlank()) return "Eslatma matnini eshitmadim, qaytadan ayting."
        val prefs = context.getSharedPreferences(NOTES_PREF, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(NOTES_KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add("${current.size + 1}. $note")
        prefs.edit().putStringSet(NOTES_KEY, current).apply()
        return "Eslatma saqlandi: $note"
    }

    private fun readNotes(context: Context): String {
        val prefs = context.getSharedPreferences(NOTES_PREF, Context.MODE_PRIVATE)
        val notes = prefs.getStringSet(NOTES_KEY, emptySet())?.toList()?.sorted() ?: emptyList()
        return if (notes.isEmpty()) "Sizda hozircha eslatmalar yo'q." else notes.joinToString(". ")
    }

    private fun clearNotes(context: Context): String {
        val prefs = context.getSharedPreferences(NOTES_PREF, Context.MODE_PRIVATE)
        prefs.edit().remove(NOTES_KEY).apply()
        return "Barcha eslatmalar o'chirildi."
    }

    /**
     * "50 ning 20 foizi" kabi iboralarni hisoblaydi.
     */
    private fun percent(text: String): Double? {
        val match = Regex("(\\d+(?:\\.\\d+)?)\\s*ning\\s*(\\d+(?:\\.\\d+)?)\\s*foizi").find(text) ?: return null
        val base = match.groupValues[1].toDoubleOrNull() ?: return null
        val pct = match.groupValues[2].toDoubleOrNull() ?: return null
        return base * pct / 100.0
    }

    /**
     * "36 ning kvadrat ildizi" kabi iboralarni hisoblaydi.
     */
    private fun sqrt(text: String): Double? {
        val match = Regex("(\\d+(?:\\.\\d+)?)\\s*ning\\s*kvadrat\\s*ildizi").find(text) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        return if (value >= 0) Math.sqrt(value) else null
    }

    private fun storageInfo(): String {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val totalGb = (stat.blockCountLong * stat.blockSizeLong) / (1024.0 * 1024.0 * 1024.0)
            val freeGb = (stat.availableBlocksLong * stat.blockSizeLong) / (1024.0 * 1024.0 * 1024.0)
            "Jami xotiradan %.1f GB bo'sh, umumiy hajm %.1f GB.".format(freeGb, totalGb)
        } catch (e: Exception) {
            "Xotira haqida ma'lumot olib bo'lmadi."
        }
    }

    private fun internetStatus(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            val connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            if (connected) "Ha, internetga ulangansiz." else "Yo'q, internet aloqasi mavjud emas."
        } catch (e: Exception) {
            "Internet holatini tekshirib bo'lmadi."
        }
    }

    private fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

    private fun daysUntilNewYear(): String {
        val now = Calendar.getInstance()
        val newYear = Calendar.getInstance().apply {
            set(now.get(Calendar.YEAR) + 1, Calendar.JANUARY, 1, 0, 0, 0)
        }
        val diffMs = newYear.timeInMillis - now.timeInMillis
        val days = diffMs / (1000 * 60 * 60 * 24)
        return "Yangi yilgacha $days kun qoldi."
    }

    private fun installedAppsCount(context: Context): String {
        return try {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            val count = pm.queryIntentActivities(mainIntent, 0).size
            "Telefoningizda taxminan $count ta ilova bor."
        } catch (e: Exception) {
            "Ilovalar sonini hisoblab bo'lmadi."
        }
    }

    /**
     * "3 marta tanga tashla" kabi iboralardan takrorlar sonini ajratadi.
     */
    private fun repeatCount(text: String, keyword: String): Int? {
        if (!text.contains(keyword)) return null
        val match = Regex("(\\d+)\\s*marta\\s*$keyword").find(text)
            ?: Regex("$keyword.*?(\\d+)\\s*marta").find(text)
        val count = match?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return count.coerceIn(1, 20)
    }

    private fun repeatCoinFlips(count: Int): String {
        val results = (1..count).map { if (listOf("bosh", "qiya").random() == "bosh") "bosh" else "qiya" }
        return "Natijalar: " + results.joinToString(", ")
    }

    private fun repeatDiceRolls(count: Int): String {
        val results = (1..count).map { (1..6).random() }
        return "Natijalar: " + results.joinToString(", ")
    }

    private val onesUz = listOf(
        "nol", "bir", "ikki", "uch", "to'rt", "besh", "olti", "yetti", "sakkiz", "to'qqiz"
    )
    private val tensUz = listOf(
        "", "o'n", "yigirma", "o'ttiz", "qirq", "ellik", "oltmish", "yetmish", "sakson", "to'qson"
    )

    /**
     * 0-999 oralig'idagi butun sonni o'zbekcha so'zga aylantiradi.
     */
    private fun numberToWords(text: String): String {
        val match = Regex("(\\d+)").find(text) ?: return "Raqamni topa olmadim."
        val number = match.groupValues[1].toIntOrNull() ?: return "Raqamni topa olmadim."
        if (number > 999) return "Faqat 0 dan 999 gacha bo'lgan sonlarni so'zga aylantira olaman."
        if (number == 0) return "Nol"
        val hundreds = number / 100
        val remainder = number % 100
        val tens = remainder / 10
        val ones = remainder % 10
        val parts = mutableListOf<String>()
        if (hundreds > 0) parts.add("${onesUz[hundreds]} yuz")
        if (tens > 0) parts.add(tensUz[tens])
        if (ones > 0) parts.add(onesUz[ones])
        return parts.joinToString(" ").replaceFirstChar { it.uppercase() }
    }

    /**
     * Oddiy o'lchov birliklarini o'zaro o'giradi (km/mil, kg/funt, selsiy/farengeyt).
     */
    private fun convertUnit(text: String, type: String): String {
        val match = Regex("(\\d+(?:\\.\\d+)?)").find(text)
            ?: return "Raqamni topa olmadim, masalan: \"10 km ni milga aylantir\"."
        val value = match.groupValues[1].toDoubleOrNull() ?: return "Raqamni topa olmadim."
        val (result, unit) = when (type) {
            "km_to_mile" -> (value * 0.621371) to "mil"
            "mile_to_km" -> (value * 1.60934) to "km"
            "kg_to_lb" -> (value * 2.20462) to "funt"
            "lb_to_kg" -> (value / 2.20462) to "kg"
            "c_to_f" -> (value * 9.0 / 5.0 + 32) to "Farengeyt"
            "f_to_c" -> ((value - 32) * 5.0 / 9.0) to "Selsiy"
            else -> return "Noma'lum o'lchov."
        }
        return "%.2f %s.".format(result, unit)
    }

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
