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
    data class AskAi(val prompt: String, val fallbackText: String) : CommandResult()
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

            text.contains(" javobi ") ->
                CommandResult.Speak(teachAnswer(context, text))

            fuzzyAny(text, "nechta narsa organgansan", "qancha narsa bilasan", "organganlaring nechta") ->
                CommandResult.Speak(learnedCount(context))

            text.endsWith(" ni unut") || text.endsWith(" ni unutib yubor") ->
                CommandResult.Speak(forgetAnswer(context, text.removeSuffix(" ni unutib yubor").removeSuffix(" ni unut")))

            findLearnedAnswer(context, text) != null ->
                CommandResult.Speak(findLearnedAnswer(context, text)!!)

            findBuiltinKnowledge(text) != null ->
                CommandResult.Speak(findBuiltinKnowledge(text)!!)

            else ->
                CommandResult.AskAi(
                    prompt = text,
                    fallbackText = pickFallback() + " Agar bilsangiz, \"" + text + " javobi ...\" deb o'rgating."
                )
        }
    }

    /**
     * Xotiradan (foydalanuvchi o'rgatgan javoblardan) qidiradi, topilmasa
     * tayyor 200+ bilim bazasidan izlaydi. Foydalanuvchi o'rgatgan javob
     * har doim ustunlik qiladi (xato tayyor javobni tuzatish imkoniyati uchun).
     */
    private fun findBuiltinKnowledge(text: String): String? {
        builtinKnowledge[text]?.let { return it }
        for ((question, answer) in builtinKnowledge) {
            if (fuzzyAny(text, question)) return answer
        }
        return null
    }

    /**
     * Oldindan tayyorlangan 200+ ta savol-javob — geografiya, tarix, fan,
     * umumiy bilim. DIQQAT: bu ma'lumotlar internetga ulanmasdan, xotiradan
     * yozilgan — ayrim raqam/sana xato bo'lishi mumkin. Xato topsangiz,
     * "[savol] javobi [to'g'ri javob]" deb aytib tuzatishingiz mumkin —
     * sizning tuzatishingiz doim bu ro'yxatdan ustun turadi.
     */
    private val builtinKnowledge: Map<String, String> = mapOf(
        // --- Dunyo davlatlari poytaxtlari ---
        "ozbekiston poytaxti qayer" to "Toshkent",
        "qozogiston poytaxti qayer" to "Astana",
        "qirgiziston poytaxti qayer" to "Bishkek",
        "tojikiston poytaxti qayer" to "Dushanbe",
        "turkmaniston poytaxti qayer" to "Ashxabod",
        "rossiya poytaxti qayer" to "Moskva",
        "xitoy poytaxti qayer" to "Pekin",
        "aqsh poytaxti qayer" to "Vashington",
        "angliya poytaxti qayer" to "London",
        "fransiya poytaxti qayer" to "Parij",
        "germaniya poytaxti qayer" to "Berlin",
        "italiya poytaxti qayer" to "Rim",
        "ispaniya poytaxti qayer" to "Madrid",
        "yaponiya poytaxti qayer" to "Tokio",
        "janubiy koreya poytaxti qayer" to "Seul",
        "hindiston poytaxti qayer" to "Yangi Dehli",
        "turkiya poytaxti qayer" to "Anqara",
        "eron poytaxti qayer" to "Tehron",
        "pokiston poytaxti qayer" to "Islomobod",
        "afgoniston poytaxti qayer" to "Kobul",
        "saudiya arabistoni poytaxti qayer" to "Er-Riyod",
        "biriktgan arab amirliklari poytaxti qayer" to "Abu-Dabi",
        "misr poytaxti qayer" to "Qohira",
        "braziliya poytaxti qayer" to "Brazilia",
        "argentina poytaxti qayer" to "Buenos-Ayres",
        "kanada poytaxti qayer" to "Ottava",
        "avstraliya poytaxti qayer" to "Kanberra",
        "meksika poytaxti qayer" to "Mexiko",
        "indoneziya poytaxti qayer" to "Jakarta",
        "gollandiya poytaxti qayer" to "Amsterdam",
        "belgiya poytaxti qayer" to "Bryussel",
        "shveysariya poytaxti qayer" to "Bern",
        "avstriya poytaxti qayer" to "Vena",
        "shvetsiya poytaxti qayer" to "Stokgolm",
        "norvegiya poytaxti qayer" to "Oslo",
        "finlyandiya poytaxti qayer" to "Xelsinki",
        "daniya poytaxti qayer" to "Kopengagen",
        "polsha poytaxti qayer" to "Varshava",
        "ukraina poytaxti qayer" to "Kiyev",
        "gretsiya poytaxti qayer" to "Afina",
        "portugaliya poytaxti qayer" to "Lissabon",
        "nigeriya poytaxti qayer" to "Abuja",
        "keniya poytaxti qayer" to "Nayrobi",
        "marokash poytaxti qayer" to "Rabat",
        "malayziya poytaxti qayer" to "Kuala-Lumpur",
        "tailand poytaxti qayer" to "Bangkok",
        "vetnam poytaxti qayer" to "Xanoy",
        "filippin poytaxti qayer" to "Manila",
        "yangi zelandiya poytaxti qayer" to "Vellington",
        "iroq poytaxti qayer" to "Bag'dod",

        // --- O'zbekiston viloyatlari markazlari ---
        "toshkent viloyati markazi qayer" to "Nurafshon shahri",
        "andijon viloyati markazi qayer" to "Andijon shahri",
        "fargona viloyati markazi qayer" to "Farg'ona shahri",
        "namangan viloyati markazi qayer" to "Namangan shahri",
        "sirdaryo viloyati markazi qayer" to "Guliston shahri",
        "jizzax viloyati markazi qayer" to "Jizzax shahri",
        "samarqand viloyati markazi qayer" to "Samarqand shahri",
        "buxoro viloyati markazi qayer" to "Buxoro shahri",
        "navoiy viloyati markazi qayer" to "Navoiy shahri",
        "qashqadaryo viloyati markazi qayer" to "Qarshi shahri",
        "surxondaryo viloyati markazi qayer" to "Termiz shahri",
        "xorazm viloyati markazi qayer" to "Urganch shahri",
        "qoraqalpogiston poytaxti qayer" to "Nukus shahri",
        "ozbekistonda nechta viloyat bor" to "O'zbekistonda 12 ta viloyat, Toshkent shahri va Qoraqalpog'iston Respublikasi bor.",

        // --- O'zbekiston haqida ---
        "ozbekiston qachon mustaqillikka erishgan" to "O'zbekiston 1991-yil 1-sentyabrda mustaqillikka erishgan.",
        "ozbekiston milliy valyutasi nima" to "O'zbekiston milliy valyutasi — so'm.",
        "ozbekiston bayrogida qanday ranglar bor" to "Ko'k, oq, yashil ranglar va qizil chiziqlar, oy va 12 ta yulduz.",
        "ozbekistonning davlat tili nima" to "O'zbekistonning davlat tili — o'zbek tili.",
        "amir temur kim bolgan" to "Amir Temur — Temuriylar sulolasining asoschisi, buyuk sarkarda va davlat arbobi.",
        "mirzo ulugbek kim bolgan" to "Mirzo Ulug'bek — olim, astronom, Samarqandda rasadxona qurdirgan hukmdor.",
        "alisher navoiy kim bolgan" to "Alisher Navoiy — buyuk o'zbek shoiri, mutafakkiri va davlat arbobi.",
        "ibn sino kim bolgan" to "Ibn Sino — buyuk olim va tabib, \"Tib qonunlari\" asarining muallifi.",
        "al xorazmiy kim bolgan" to "Al-Xorazmiy — matematik va astronom, algebra fanining asoschilaridan biri.",
        "zahiriddin muhammad bobur kim bolgan" to "Bobur — shoir, davlat arbobi, Boburiylar sulolasining asoschisi.",
        "imom buxoriy kim bolgan" to "Imom Buxoriy — buyuk muhaddis olim, hadis to'plovchi.",

        // --- Fan va tabiat ---
        "quyosh tizimida nechta sayyora bor" to "Quyosh tizimida 8 ta sayyora bor.",
        "eng katta sayyora qaysi" to "Eng katta sayyora — Yupiter.",
        "eng kichik sayyora qaysi" to "Eng kichik sayyora — Merkuriy.",
        "yerning yoldoshi nima" to "Yerning yo'ldoshi — Oy.",
        "suvning kimyoviy formulasi nima" to "Suvning formulasi — H2O.",
        "osh tuzining kimyoviy formulasi nima" to "Osh tuzining formulasi — NaCl.",
        "inson tanasida nechta suyak bor" to "Kattalar tanasida taxminan 206 ta suyak bor.",
        "yurak necha kameradan iborat" to "Yurak 4 ta kameradan iborat — ikkita yurakcha va ikkita qorincha.",
        "eng katta okean qaysi" to "Eng katta okean — Tinch okeani.",
        "eng uzun daryo qaysi" to "Eng uzun daryo — Nil daryosi.",
        "eng baland togʻ qaysi" to "Eng baland tog' — Everest (Jomolungma).",
        "yorugʻlik tezligi qancha" to "Yorug'lik tezligi soniyasiga taxminan 300 000 kilometr.",
        "fotosintez nima" to "Fotosintez — o'simliklarning quyosh energiyasidan foydalanib ozuqa ishlab chiqarish jarayoni.",
        "pi soni nechaga teng" to "Pi soni taxminan 3.14 ga teng.",
        "bir doirada nechta gradus bor" to "Bir to'liq doirada 360 gradus bor.",
        "uchburchak ichki burchaklari yigʻindisi nechaga teng" to "Uchburchak ichki burchaklari yig'indisi 180 gradusga teng.",
        "odam tanasining eng katta azosi nima" to "Odam tanasining eng katta a'zosi — teri.",
        "gravitatsiya nima" to "Gravitatsiya — jismlarni bir-biriga tortib turuvchi tabiiy kuch.",
        "dna nima" to "DNK — tirik organizmlarning genetik ma'lumotini saqlovchi molekula.",
        "qon qanday rangda" to "Qon qizil rangda, tarkibidagi temir elementi tufayli.",

        // --- Umumiy bilim va ta'riflar ---
        "internet nima" to "Internet — dunyo bo'ylab kompyuterlarni bir-biriga bog'lovchi global tarmoq.",
        "robot nima" to "Robot — inson buyrug'i bilan yoki avtomatik tarzda ishlaydigan mashina.",
        "sunʼiy intellekt nima" to "Sun'iy intellekt — kompyuterning inson kabi fikrlash va o'rganish qobiliyatini taqlid qilishi.",
        "demokratiya nima" to "Demokratiya — xalq o'z vakillarini saylash orqali davlatni boshqarish tizimi.",
        "iqtisodiyot nima" to "Iqtisodiyot — resurslarni ishlab chiqarish, taqsimlash va iste'mol qilish bilan shug'ullanuvchi fan.",
        "tarix nima" to "Tarix — o'tmishda yuz bergan voqealarni o'rganuvchi fan.",
        "geografiya nima" to "Geografiya — Yer yuzasi, uning tabiati va aholisini o'rganuvchi fan.",
        "matematika nima" to "Matematika — sonlar, shakllar va ularning munosabatlarini o'rganuvchi fan.",
        "fizika nima" to "Fizika — materiya, energiya va ularning o'zaro ta'sirini o'rganuvchi fan.",
        "kimyo nima" to "Kimyo — moddalarning tuzilishi, xossalari va o'zgarishlarini o'rganuvchi fan.",
        "biologiya nima" to "Biologiya — tirik organizmlarni o'rganuvchi fan.",

        // --- Kunlar, oylar, umumiy ---
        "haftaning birinchi kuni qaysi" to "O'zbekistonda hafta odatda dushanbadan boshlanadi.",
        "yilda nechta oy bor" to "Yilda 12 ta oy bor.",
        "bir yilda nechta kun bor" to "Oddiy yilda 365 kun, kabisa yilida 366 kun bor.",
        "bir soatda nechta daqiqa bor" to "Bir soatda 60 daqiqa bor.",
        "bir daqiqada nechta soniya bor" to "Bir daqiqada 60 soniya bor.",

        // --- Nikre haqida ---
        "sen kim tomonidan yaratilgansan" to "Meni Erkinbek yaratgan.",
        "senda qancha til bor" to "Hozircha o'zbek, rus, ingliz, turk va turkman tillarida sozlanishim mumkin.",
        "sen internetga ulanganmisan" to "Asosiy buyruqlarim internetsiz ishlaydi, ba'zi funksiyalarim (masalan brauzerda qidiruv) internet talab qiladi.",

        // --- Yana poytaxtlar ---
        "qatar poytaxti qayer" to "Doha",
        "kuvayt poytaxti qayer" to "Kuvayt shahri",
        "iordaniya poytaxti qayer" to "Amman",
        "livan poytaxti qayer" to "Bayrut",
        "suriya poytaxti qayer" to "Dimashq",
        "ozarbayjon poytaxti qayer" to "Boku",
        "armaniston poytaxti qayer" to "Yerevan",
        "gruziya poytaxti qayer" to "Tbilisi",
        "belarus poytaxti qayer" to "Minsk",
        "moldova poytaxti qayer" to "Kishinyov",
        "chexiya poytaxti qayer" to "Praga",
        "slovakiya poytaxti qayer" to "Bratislava",
        "vengriya poytaxti qayer" to "Budapesht",
        "ruminiya poytaxti qayer" to "Buxarest",
        "bolgariya poytaxti qayer" to "Sofiya",
        "xorvatiya poytaxti qayer" to "Zagreb",
        "serbiya poytaxti qayer" to "Belgrad",
        "albaniya poytaxti qayer" to "Tirana",
        "islandiya poytaxti qayer" to "Reykyavik",
        "irlandiya poytaxti qayer" to "Dublin",
        "singapur poytaxti qayer" to "Singapur shahri",
        "mogʻuliston poytaxti qayer" to "Ulan-Bator",
        "kuba poytaxti qayer" to "Gavana",
        "chili poytaxti qayer" to "Santyago",
        "peru poytaxti qayer" to "Lima",
        "kolumbiya poytaxti qayer" to "Bogota",
        "venesuela poytaxti qayer" to "Karakas",
        "efiopiya poytaxti qayer" to "Addis-Abeba",
        "tunis poytaxti qayer" to "Tunis shahri",
        "jazoir poytaxti qayer" to "Jazoir shahri",
        "liviya poytaxti qayer" to "Tripoli",
        "sudan poytaxti qayer" to "Xartum",
        "gana poytaxti qayer" to "Akkra",

        // --- Hayvonlar va tabiat ---
        "eng tez hayvon qaysi" to "Gepard — quruqlikdagi eng tez yuguruvchi hayvon.",
        "eng katta hayvon qaysi" to "Ko'k kit — Yerdagi eng katta hayvon.",
        "eng baland hayvon qaysi" to "Jirafa — Yerdagi eng baland hayvon.",
        "eng kuchli hayvon qaysi" to "Nisbiy kuchga ko'ra, chumoli o'z vazniga nisbatan eng kuchli hayvonlardan biri hisoblanadi.",
        "sherlar qayerda yashaydi" to "Sherlar asosan Afrikada va ozgina qismi Hindistonda yashaydi.",
        "pingvinlar qayerda yashaydi" to "Pingvinlar asosan Antarktida va Janubiy yarim sharda yashaydi.",

        // --- Odam tanasi ---
        "kattalarda nechta tish bor" to "Kattalarda odatda 32 ta tish bor.",
        "inson miyasi necha foiz suvdan iborat" to "Inson miyasi taxminan 75 foiz suvdan iborat.",
        "inson tanasida nechta mushak bor" to "Inson tanasida 600 dan ortiq mushak bor.",
        "nechta sezgi azosi bor" to "Insonda 5 ta asosiy sezgi a'zosi bor: ko'rish, eshitish, hid bilish, ta'm bilish va teri sezgisi.",

        // --- Yer va koinot ---
        "yer qanday shaklga ega" to "Yer qariyb shar shaklida (aniqrog'i, geoid shaklida).",
        "yer quyosh atrofini necha kunda aylanadi" to "Yer Quyosh atrofini taxminan 365 kunda aylanadi.",
        "oy yer atrofini necha kunda aylanadi" to "Oy Yer atrofini taxminan 27-29 kunda aylanadi.",
        "eng sovuq qita qaysi" to "Eng sovuq qit'a — Antarktida.",
        "eng issiq qita qaysi" to "Eng issiq qit'a — Afrika.",
        "nechta qita bor" to "Yerda 7 ta qit'a bor: Osiyo, Afrika, Shimoliy Amerika, Janubiy Amerika, Antarktida, Yevropa va Avstraliya.",
        "eng katta choʻl qaysi" to "Eng katta issiq cho'l — Sahroi Kabir (Sahara).",

        // --- Fan atamalari ---
        "atom nima" to "Atom — moddaning kimyoviy xossalarini saqlagan eng kichik bo'lagi.",
        "molekula nima" to "Molekula — ikki yoki undan ortiq atomning birikishidan hosil bo'lgan zarracha.",
        "elektr toki nima" to "Elektr toki — zaryadlangan zarrachalarning tartibli harakati.",
        "magnit nima" to "Magnit — temir kabi ba'zi metallarni o'ziga tortadigan jism.",
        "bakteriya nima" to "Bakteriya — bir hujayrali, ko'z bilan ko'rib bo'lmaydigan mikroorganizm.",
        "virus nima" to "Virus — faqat tirik hujayra ichida ko'payadigan juda kichik yuqumli zarracha.",

        // --- Tillar ---
        "rossiyaning davlat tili nima" to "Rossiyaning davlat tili — rus tili.",
        "turkiyaning davlat tili nima" to "Turkiyaning davlat tili — turk tili.",
        "xitoyning davlat tili nima" to "Xitoyning davlat tili — xitoy (mandarin) tili.",
        "aqshning davlat tili nima" to "AQShda rasmiy davlat tili qonunda belgilanmagan, lekin amalda ingliz tili ishlatiladi.",

        // --- Bayramlar va sanalar ---
        "navruz qachon nishonlanadi" to "Navro'z har yili 21-martda nishonlanadi.",
        "ozbekiston mustaqillik kuni qachon nishonlanadi" to "O'zbekiston Mustaqillik kuni 1-sentyabrda nishonlanadi.",
        "yangi yil qachon nishonlanadi" to "Yangi yil har yili 1-yanvarda nishonlanadi.",
        "xotira va qadrlash kuni qachon" to "Xotira va qadrlash kuni 9-mayda nishonlanadi.",

        // --- O'lchovlar ---
        "bir kilogrammda nechta gramm bor" to "Bir kilogrammda 1000 gramm bor.",
        "bir metrda nechta santimetr bor" to "Bir metrda 100 santimetr bor.",
        "bir kilometrda nechta metr bor" to "Bir kilometrda 1000 metr bor.",
        "bir litrda nechta millilitr bor" to "Bir litrda 1000 millilitr bor.",
        "bir soatda nechta soniya bor" to "Bir soatda 3600 soniya bor.",

        // --- Ranglar va umumiy ---
        "asosiy ranglar nechta" to "Asosiy ranglar 3 ta: qizil, sariq va ko'k.",
        "kamalakda nechta rang bor" to "Kamalakda 7 ta rang bor.",
        "svetofordagi ranglar nechta" to "Svetoforda 3 ta rang bor: qizil, sariq va yashil.",

        // --- Yana umumiy ta'riflar ---
        "ozbekiston nechanchi yilda bmtga azo bolgan" to "O'zbekiston 1992-yilda Birlashgan Millatlar Tashkilotiga a'zo bo'lgan.",
        "eng katta qita qaysi" to "Eng katta qit'a — Osiyo.",
        "eng kichik qita qaysi" to "Eng kichik qit'a — Avstraliya.",
        "eng koʻp aholi yashaydigan davlat qaysi" to "Aholi soni bo'yicha eng katta davlat — Hindiston.",
        "eng katta davlat hududi boyicha qaysi" to "Hudud bo'yicha eng katta davlat — Rossiya.",
        "futbolda bir jamoada nechta oʻyinchi boladi" to "Futbolda maydonda bir jamoadan 11 ta o'yinchi bo'ladi.",
        "shaxmatda nechta dona bor" to "Shaxmatda har bir o'yinchida 16 tadan, jami 32 ta dona bor.",
        "bir hafta nechta kundan iborat" to "Bir hafta 7 kundan iborat.",
        "yozning birinchi oyi qaysi" to "Yozning birinchi oyi — iyun.",
        "qishning birinchi oyi qaysi" to "Qishning birinchi oyi — dekabr.",
        "bahorning birinchi oyi qaysi" to "Bahorning birinchi oyi — mart.",
        "kuzning birinchi oyi qaysi" to "Kuzning birinchi oyi — sentyabr.",
        "insoniyat tarixidagi birinchi kompyuter qachon yaratilgan" to "Birinchi elektron kompyuterlar 1940-yillarda yaratilgan.",
        "birinchi odam oyga qachon qadam qoygan" to "Inson Oyga birinchi marta 1969-yilda qadam qo'ygan (Apollon-11 missiyasi).",

        // --- Kimyoviy elementlar ---
        "vodorod kimyoviy belgisi nima" to "Vodorodning kimyoviy belgisi — H.",
        "kislorod kimyoviy belgisi nima" to "Kislorodning kimyoviy belgisi — O.",
        "temir kimyoviy belgisi nima" to "Temirning kimyoviy belgisi — Fe.",
        "oltin kimyoviy belgisi nima" to "Oltinning kimyoviy belgisi — Au.",
        "kumush kimyoviy belgisi nima" to "Kumushning kimyoviy belgisi — Ag.",
        "mis kimyoviy belgisi nima" to "Misning kimyoviy belgisi — Cu.",
        "uglerod kimyoviy belgisi nima" to "Uglerodning kimyoviy belgisi — C.",
        "azot kimyoviy belgisi nima" to "Azotning kimyoviy belgisi — N.",
        "natriy kimyoviy belgisi nima" to "Natriyning kimyoviy belgisi — Na.",
        "eng yengil kimyoviy element nima" to "Eng yengil kimyoviy element — vodorod.",
        "havoda eng kop qanday gaz bor" to "Havoda eng ko'p azot gazi bor (taxminan 78 foiz).",
        "odam nafas olish uchun qanday gaz kerak" to "Odam nafas olish uchun kislorod gazi kerak.",

        // --- O'zbekiston geografiyasi ---
        "ozbekistondagi eng uzun daryo qaysi" to "O'zbekistondagi eng uzun daryo — Amudaryo.",
        "sirdaryo qayerdan boshlanadi" to "Sirdaryo Farg'ona vodiysidan boshlanadi.",
        "orol dengizi qayerda joylashgan" to "Orol dengizi O'zbekiston va Qozog'iston chegarasida joylashgan.",
        "ozbekistondagi eng baland togʻ qaysi" to "O'zbekistondagi eng baland cho'qqi — Adelung cho'qqisi (Hisor tizmasida).",
        "chimyon qayerda joylashgan" to "Chimyon — Toshkent viloyatida, tog' iqlim kurort hududi.",
        "ozbekiston necha davlat bilan chegaradosh" to "O'zbekiston 5 ta davlat bilan chegaradosh: Qozog'iston, Qirg'iziston, Tojikiston, Turkmaniston va Afg'oniston.",
        "registon maydoni qayerda joylashgan" to "Registon maydoni Samarqand shahrida joylashgan.",
        "xiva qaysi viloyatda joylashgan" to "Xiva shahri Xorazm viloyatida joylashgan.",

        // --- Ixtirolar va ixtirochilar ---
        "telefonni kim ixtiro qilgan" to "Telefonni Aleksandr Graham Bell ixtiro qilgan deb hisoblanadi.",
        "elektr lampochkasini kim ixtiro qilgan" to "Elektr lampochkasini takomillashtirgan va keng tarqatgan Tomas Edison hisoblanadi.",
        "internetni kim ixtiro qilgan" to "Internetning dastlabki asosi (ARPANET) 1960-yillarda AQSh olimlari tomonidan yaratilgan.",
        "samolyotni kim ixtiro qilgan" to "Birinchi boshqariluvchi samolyotni Rayt birodarlar yaratgan (1903-yil).",
        "kitob bosish dastgohini kim ixtiro qilgan" to "Kitob bosish dastgohini Iogann Gutenberg ixtiro qilgan.",

        // --- Sport ---
        "olimpiada oyinlari necha yilda bir marta otkaziladi" to "Yozgi va qishki Olimpiada o'yinlari har 4 yilda bir marta o'tkaziladi.",
        "futbolda bir oyin necha daqiqa davom etadi" to "Futbolda bir o'yin odatda 90 daqiqa (ikki taym, 45 daqiqadan) davom etadi.",
        "basketbolda bir jamoada nechta oʻyinchi boladi" to "Basketbolda maydonda bir jamoadan 5 ta o'yinchi bo'ladi.",
        "voleybolda bir jamoada nechta oʻyinchi boladi" to "Voleybolda maydonda bir jamoadan 6 ta o'yinchi bo'ladi.",
        "birinchi zamonaviy olimpiada qachon otkazilgan" to "Birinchi zamonaviy Olimpiada o'yinlari 1896-yilda Afinada o'tkazilgan.",

        // --- Texnologiya asoslari ---
        "kompyuter nima" to "Kompyuter — ma'lumotlarni qabul qiluvchi, qayta ishlovchi va natija chiqaruvchi elektron qurilma.",
        "protsessor nima" to "Protsessor — kompyuterning barcha hisob-kitoblarini bajaruvchi \"miya\"si.",
        "software nima" to "Dastur (software) — kompyuterga ma'lum vazifani bajarishni buyuruvchi kod.",
        "wifi nima" to "Wi-Fi — simsiz tarzda internetga ulanish texnologiyasi.",
        "bluetooth nima" to "Bluetooth — qurilmalar orasida qisqa masofada simsiz ma'lumot almashish texnologiyasi.",
        "qr kod nima" to "QR kod — kameradan skanerlash orqali ma'lumot (havola, matn) o'qiladigan kvadrat kod.",
        "sim karta nima" to "SIM karta — telefonni mobil tarmoqqa ulovchi kichik chip.",

        // --- Yana umumiy bilim ---
        "eng katta valyuta birligi qaysi" to "Bu savol vaqt o'tishi bilan o'zgaradi, chunki valyuta kursi doimiy almashinadi.",
        "dunyoda nechta davlat bor" to "Hozirda dunyoda taxminan 195 ta mustaqil davlat tan olingan.",
        "yer sharida nechta til bor" to "Dunyoda taxminan 7000 ga yaqin til mavjud deb hisoblanadi.",
        "eng kop gapiriladigan til qaysi" to "Ona tili sifatida eng ko'p gapiriladigan til — xitoy (mandarin) tili.",
        "dunyodagi eng baland bino qaysi" to "Hozirgi kunda dunyodagi eng baland bino — Burj Xalifa (Dubay, BAA).",
        "dunyodagi eng katta mamlakat qaysi shaharlardan iborat" to "Bu savol aniq emas, aniqroq so'rang — masalan, qaysi davlat haqida so'ramoqchisiz?",
        "piramidalar qayerda joylashgan" to "Mashhur qadimiy piramidalar Misrda, Qohira yaqinida joylashgan.",
        "xitoy devori necha kilometr" to "Xitoy devorining umumiy uzunligi taxminan 21 000 kilometrdan oshadi.",

        // --- Oddiy hayotiy bilim ---
        "koʻp suv ichish nima uchun foydali" to "Ko'p suv ichish tana faoliyati, teri sog'lig'i va umumiy salomatlik uchun foydali.",
        "yetarli uxlash nima uchun muhim" to "Yetarli uxlash miya va tana tiklanishi, xotira va diqqat uchun muhim.",
        "ertalabki mashq nima uchun foydali" to "Ertalabki mashq qon aylanishini yaxshilaydi va kun davomida energiya beradi.",
        "kitob oqish nima uchun foydali" to "Kitob o'qish bilimni oshiradi, so'z boyligini kengaytiradi va tafakkurni rivojlantiradi.",

        // --- Valyutalar ---
        "aqsh valyutasi nima" to "AQSh valyutasi — dollar.",
        "angliya valyutasi nima" to "Angliya valyutasi — funt sterling.",
        "yaponiya valyutasi nima" to "Yaponiya valyutasi — yen.",
        "xitoy valyutasi nima" to "Xitoy valyutasi — yuan.",
        "rossiya valyutasi nima" to "Rossiya valyutasi — rubl.",
        "yevropa ittifoqi valyutasi nima" to "Ko'pchilik Yevropa Ittifoqi davlatlarining valyutasi — yevro.",
        "hindiston valyutasi nima" to "Hindiston valyutasi — rupiya.",
        "turkiya valyutasi nima" to "Turkiya valyutasi — lira.",
        "qozogiston valyutasi nima" to "Qozog'iston valyutasi — tenge.",
        "koreya valyutasi nima" to "Janubiy Koreya valyutasi — von.",

        // --- Mashhur yozuvchilar ---
        "shekspir kim bolgan" to "Shekspir — mashhur ingliz dramaturgi va shoiri, \"Hamlet\", \"Romeo va Julyetta\" kabi asarlar muallifi.",
        "pushkin kim bolgan" to "Pushkin — mashhur rus shoiri, rus adabiyotining asoschilaridan biri.",
        "tolstoy kim bolgan" to "Lev Tolstoy — mashhur rus yozuvchisi, \"Urush va tinchlik\" romani muallifi.",
        "abdulla qodiriy kim bolgan" to "Abdulla Qodiriy — o'zbek yozuvchisi, \"O'tkan kunlar\" romani muallifi.",
        "choʻlpon kim bolgan" to "Cho'lpon — o'zbek shoiri va yozuvchisi, jadid adabiyoti vakili.",

        // --- Raqamlar boshqa tillarda (1-10) ---
        "ingliz tilida bir qanday aytiladi" to "Ingliz tilida \"bir\" — one.",
        "ingliz tilida ikki qanday aytiladi" to "Ingliz tilida \"ikki\" — two.",
        "ingliz tilida uch qanday aytiladi" to "Ingliz tilida \"uch\" — three.",
        "ingliz tilida besh qanday aytiladi" to "Ingliz tilida \"besh\" — five.",
        "ingliz tilida on qanday aytiladi" to "Ingliz tilida \"o'n\" — ten.",
        "rus tilida bir qanday aytiladi" to "Rus tilida \"bir\" — odin.",
        "rus tilida ikki qanday aytiladi" to "Rus tilida \"ikki\" — dva.",
        "rus tilida rahmat qanday aytiladi" to "Rus tilida \"rahmat\" — spasibo.",
        "ingliz tilida salom qanday aytiladi" to "Ingliz tilida \"salom\" — hello.",
        "ingliz tilida rahmat qanday aytiladi" to "Ingliz tilida \"rahmat\" — thank you.",

        // --- Grammatika ---
        "ot nima" to "Ot — predmet yoki narsa nomini bildiruvchi so'z turkumi (masalan: kitob, uy).",
        "feil nima" to "Fe'l — harakat yoki holatni bildiruvchi so'z turkumi (masalan: yozmoq, o'qimoq).",
        "sifat nima" to "Sifat — predmetning belgisini bildiruvchi so'z turkumi (masalan: katta, chiroyli).",
        "son nima" to "Son — miqdor yoki tartibni bildiruvchi so'z turkumi (masalan: besh, beshinchi).",
        "olmosh nima" to "Olmosh — ot, sifat yoki son o'rnida ishlatiladigan so'z (masalan: men, sen, u).",

        // --- Oziq-ovqat ---
        "non nima uchun foydali" to "Non — uglevodlarga boy asosiy oziq-ovqat mahsulotlaridan biri.",
        "choy nima uchun ichiladi" to "Choy tetiklantiruvchi va tinchlantiruvchi ichimlik sifatida ichiladi, madaniyatimizda mehmondo'stlik ramzi.",
        "asalning foydasi nima" to "Asal tabiiy shirinlik, vitaminlarga boy va immunitetni mustahkamlashga yordam beradi.",
        "sabzavotlar nima uchun foydali" to "Sabzavotlar vitamin va tolaga boy, sog'lom ovqatlanish uchun muhim.",
        "meva yeyish nima uchun foydali" to "Mevalar vitamin, mineral va tabiiy shakar manbai, sog'liq uchun foydali.",

        // --- Davlatlar va qit'alar ---
        "ozbekiston qaysi qitada joylashgan" to "O'zbekiston Osiyo qit'asida, aniqrog'i Markaziy Osiyoda joylashgan.",
        "misr qaysi qitada joylashgan" to "Misr Afrika qit'asida joylashgan.",
        "braziliya qaysi qitada joylashgan" to "Braziliya Janubiy Amerika qit'asida joylashgan.",
        "avstraliya qaysi qitada joylashgan" to "Avstraliya o'zi alohida qit'a hisoblanadi.",
        "yaponiya qaysi qitada joylashgan" to "Yaponiya Osiyo qit'asida joylashgan.",

        // --- Yana fan va kundalik hayot ---
        "muzlatgich nima uchun kerak" to "Muzlatgich ovqatlarni sovuqda saqlab, uzoqroq buzilishdan asraydi.",
        "elektr energiyasi qayerdan olinadi" to "Elektr energiyasi elektr stansiyalarida (suv, quyosh, shamol, gaz orqali) ishlab chiqariladi.",
        "quyosh energiyasi qanday ishlaydi" to "Quyosh panellari quyosh nurini elektr energiyasiga aylantiradi.",
        "yomgʻir qanday hosil boladi" to "Yomg'ir — bug'lanish va kondensatsiya natijasida bulutlardan tushadigan suv tomchilari.",
        "qor qanday hosil boladi" to "Qor — havo haroratining past bo'lgan sharoitida suv bug'ining muzlashidan hosil bo'ladi.",
        "chaqmoq nima" to "Chaqmoq — bulutlar orasida yoki bulut va Yer orasida yuzaga keladigan elektr razryadi.",

        // --- O'zbek maqollari va ularning ma'nosi ---
        "mehnat qilsang rohat korasan maqoli nima manoni anglatadi" to "Bu maqol: ko'p va fidokorona ishlagan kishi oxir-oqibat yaxshi natijaga erishadi, degan ma'noni bildiradi.",
        "bilim baxt keltiradi maqoli nima manoni anglatadi" to "Bu maqol: ilm-ma'rifat insonga baxt va farovonlik olib keladi, degan ma'noni bildiradi.",
        "ona yurt oltin beshik maqoli nima manoni anglatadi" to "Bu maqol: tug'ilib o'sgan yurt inson uchun eng qadrli va aziz joy ekanini bildiradi.",
        "do‘stga qiyin kunda kerak maqoli nima manoni anglatadi" to "Bu maqol: haqiqiy do'st og'ir vaziyatda yordam beradigan kishi ekanini bildiradi.",
        "bir kun ilm ikkinchi kun hilm maqoli nima manoni anglatadi" to "Bu maqol: avval bilim olish, keyin esa sabr-matonat bilan ish tutish kerakligini bildiradi.",

        // --- Dunyoning yettita mo''jizasi (qadimgi) ---
        "qadimgi dunyoning yettita mojizasidan biri nima" to "Qadimgi dunyo mo''jizalaridan biri — Misr piramidalari.",
        "zamonaviy dunyoning yettita mojizasidan biri nima" to "Zamonaviy dunyo mo''jizalaridan biri — Xitoy devori.",
        "tomariston mojizalaridan biri qaysi" to "Machu-Pikchu, Petra, Kolizey kabi joylar zamonaviy dunyo mo''jizalari ro'yxatiga kiradi.",

        // --- Kosmos va fazoga oid ---
        "birinchi kosmonavt kim bolgan" to "Birinchi kosmonavt — Yuriy Gagarin, 1961-yilda kosmosga uchgan.",
        "birinchi ayol kosmonavt kim bolgan" to "Birinchi ayol kosmonavt — Valentina Tereshkova.",
        "sunʼiy yoldosh nima" to "Sun'iy yo'ldosh — Yer atrofida aylanib, aloqa yoki kuzatuv uchun ishlatiladigan qurilma.",
        "galaktika nima" to "Galaktika — yulduzlar, gaz va chang-to'zondan tashkil topgan ulkan kosmik tizim.",
        "bizning galaktikamiz nima deb ataladi" to "Bizning galaktikamiz — Somon Yo'li (Oq yo'l) deb ataladi.",
        "quyosh nima" to "Quyosh — Quyosh tizimi markazidagi yulduz, Yerga yorug'lik va issiqlik beradi.",

        // --- Hayvonot olami (davomi) ---
        "tuya necha kun suvsiz yura oladi" to "Tuyalar bir necha hafta suvsiz yura olishi mumkin, chunki o'rkachida yog' zaxirasi bor.",
        "ari asalni qanday ishlab chiqaradi" to "Arilar gullardan nektar yig'ib, uni asalarichilik uyasida qayta ishlab asalga aylantiradi.",
        "bola it kuchukcha deyiladimi" to "Ha, itning bolasi kuchukcha deb ataladi.",
        "bola mushuk nima deyiladi" to "Mushukning bolasi mushukcha deb ataladi.",
        "qush qanday koʻpayadi" to "Ko'pchilik qushlar tuxum qo'yib, keyin bolalarini chiqaradi.",
        "baliqlar qanday nafas oladi" to "Baliqlar jabralar orqali suvdagi kislorodni o'zlashtirib nafas oladi.",

        // --- San'at va musiqa ---
        "rassom nima ish qiladi" to "Rassom — rasm chizish, tasviriy san'at bilan shug'ullanadigan ijodkor.",
        "bastakor nima ish qiladi" to "Bastakor — musiqa asarlarini yozadigan ijodkor.",
        "gitara necha torli boladi" to "Klassik gitara odatda 6 torli bo'ladi.",
        "orkestr nima" to "Orkestr — turli musiqa asboblarida birgalikda ijro etuvchi musiqachilar jamoasi.",

        // --- Son va matematik tushunchalar ---
        "million nima" to "Million — 1 000 000 (bir mingdan ming marta ko'p) sonini bildiradi.",
        "milliard nima" to "Milliard — 1 000 000 000 (bir milliarddan) sonini bildiradi.",
        "foiz nima" to "Foiz — yuzdan bir ulushni bildiruvchi o'lchov birligi (%).",
        "juft son nima" to "Juft son — 2 ga qoldiqsiz bo'linadigan son (masalan: 2, 4, 6).",
        "toq son nima" to "Toq son — 2 ga qoldiqsiz bo'linmaydigan son (masalan: 1, 3, 5).",
        "tub son nima" to "Tub son — faqat 1 ga va o'ziga bo'linadigan son (masalan: 2, 3, 5, 7).",

        // --- Davlatlar va o'ziga xosliklar ---
        "qaysi davlatda qor kam yogadi" to "Issiq iqlimli davlatlarda, masalan Ekvador yoki Birlashgan Arab Amirliklarida qor deyarli yog'maydi.",
        "qaysi davlat eng koʻp orol davlati" to "Indoneziya dunyodagi eng ko'p orolga ega davlatlardan biri (17 mingdan ortiq orol).",
        "qaysi davlat eng koʻp till rasmiy" to "Janubiy Afrikada 11 ta rasmiy til mavjud, bu dunyodagi eng ko'p rasmiy tilli davlatlardan biri.",

        // --- Diniy va madaniy bayramlar (neytral) ---
        "ramazon hayiti nima" to "Ramazon hayiti — musulmonlar bir oylik ro'za tutgandan keyin nishonlaydigan diniy bayram.",
        "qurbon hayiti nima" to "Qurbon hayiti — musulmonlar tomonidan nishonlanadigan diniy bayramlardan biri.",
        "mustaqillik kuni nima" to "Mustaqillik kuni — davlatning boshqa davlatdan mustaqil bo'lgan kunini nishonlash bayrami.",

        // --- Kundalik hayot va texnologiya ---
        "povar plita qanday ishlaydi" to "Elektr plitalar elektr energiyasini issiqlikka aylantirib ovqat pishiradi.",
        "muzlatgichda ovqat qancha saqlanadi" to "Bu ovqat turiga bog'liq — odatda bir necha kundan bir necha haftagacha saqlanishi mumkin.",
        "zaryadlovchi qanday ishlaydi" to "Zaryadlovchi elektr tarmog'idan kelayotgan tokni qurilma batareyasiga mos shaklga o'zgartirib beradi.",
        "batareya qanday ishlaydi" to "Batareya kimyoviy energiyani elektr energiyasiga aylantirib, qurilmaga quvvat beradi.",

        // --- Umumiy savol-javoblar (qo'shimcha) ---
        "eng qadimiy sivilizatsiya qaysi" to "Dunyodagi eng qadimiy sivilizatsiyalardan biri — Mesopotamiya (hozirgi Iroq hududi) sivilizatsiyasi.",
        "yozuv qachon paydo bolgan" to "Dastlabki yozuv tizimlari taxminan 5000 yil avval Mesopotamiyada paydo bo'lgan.",
        "pul qachon paydo bolgan" to "Birinchi tangalar taxminan 2600 yil avval Lidiya (hozirgi Turkiya hududi)da paydo bo'lgan.",
        "soat qachon ixtiro qilingan" to "Mexanik soatlar o'rta asrlarda, taxminan 13-14-asrlarda paydo bo'lgan.",

        // --- Jism holatlari va kundalik fizika ---
        "moddaning necha holati bor" to "Moddaning asosiy uchta holati bor: qattiq, suyuq va gaz.",
        "muz nima" to "Muz — suvning qattiq holati, 0 daraja selsiyda hosil bo'ladi.",
        "bugʻ nima" to "Bug' — suvning gaz holati, qizdirilganda hosil bo'ladi.",
        "erish nima" to "Erish — qattiq moddaning qizdirilib suyuq holatga o'tishi.",
        "muzlash nima" to "Muzlash — suyuq moddaning sovutilib qattiq holatga o'tishi.",
        "bugʻlanish nima" to "Bug'lanish — suyuqlikning gaz holatiga o'tishi.",

        // --- Davlatlar bayroqlari ---
        "rossiya bayrogʻida qanday ranglar bor" to "Rossiya bayrog'ida oq, ko'k va qizil ranglar bor.",
        "aqsh bayrogʻida nechta yulduz bor" to "AQSh bayrog'ida 50 ta yulduz bor, har bir shtat uchun bittadan.",
        "turkiya bayrogʻida nima tasvirlangan" to "Turkiya bayrog'ida qizil fonda oq yarim oy va yulduz tasvirlangan.",
        "yaponiya bayrogʻida nima tasvirlangan" to "Yaponiya bayrog'ida oq fonda qizil doira (quyosh) tasvirlangan.",

        // --- Uzbek milliy taomlari ---
        "osh nima" to "Osh (palov) — guruch, go'sht va sabzidan tayyorlanadigan o'zbek milliy taomi.",
        "somsa nima" to "Somsa — xamir ichiga go'sht yoki sabzavot solib, tandirda yoki pechda pishiriladigan taom.",
        "lagʻmon nima" to "Lag'mon — cho'ziluvchi xamir va go'sht-sabzavotli sousdan iborat milliy taom.",
        "manti nima" to "Manti — xamir ichiga go'sht solib bug'da pishiriladigan taom.",
        "nonushta uchun nima yeyish foydali" to "Nonushtada tuxum, sut mahsulotlari, mevalar va non kabi to'yimli ovqatlar foydali.",

        // --- Yana fan: biologiya ---
        "hujayra nima" to "Hujayra — barcha tirik organizmlarning eng kichik tuzilish va vazifa birligi.",
        "oʻsimliklar qanday ozuqlanadi" to "O'simliklar fotosintez jarayoni orqali quyosh energiyasidan ozuqa hosil qiladi.",
        "inson qancha yil yashaydi organicha" to "Inson o'rtacha umr ko'rish davomiyligi davlatga qarab 65-85 yil atrofida bo'ladi.",
        "nafas olish jarayoni qanday boladi" to "Nafas olishda kislorod o'pkaga kirib qonga o'tadi, karbonat angidrid esa chiqarib yuboriladi.",
        "ovqat hazm qilish qanday boradi" to "Ovqat og'izdan boshlab oshqozon va ichaklarda qayta ishlanib, tanaga foydali moddalar singadi.",

        // --- Geografik rekordlar (davomi) ---
        "eng chuqur kol qaysi" to "Dunyodagi eng chuqur ko'l — Baykal ko'li (Rossiya).",
        "eng katta kol qaysi" to "Dunyodagi eng katta ko'l — Kaspiy dengizi (aslida ko'l hisoblanadi).",
        "eng issiq joy qayerda qayd etilgan" to "Yerdagi eng yuqori harorat Liviyada va AQShning Death Valley hududida qayd etilgan.",
        "eng sovuq joy qayerda qayd etilgan" to "Yerdagi eng past harorat Antarktidada qayd etilgan.",

        // --- Zamonaviy texnologiya va internet ---
        "email nima" to "Email (elektron pochta) — internet orqali xabar yuborish va qabul qilish usuli.",
        "brauzer nima" to "Brauzer — internet sahifalarini ko'rish uchun ishlatiladigan dastur.",
        "server nima" to "Server — boshqa kompyuterlarga xizmat (ma'lumot, dastur) taqdim etuvchi kuchli kompyuter.",
        "dastur kodlash nima" to "Dasturlash (kodlash) — kompyuterga vazifalarni bajarishni buyuruvchi kod yozish jarayoni.",
        "sunʼiy yoldosh aloqasi qanday ishlaydi" to "Sun'iy yo'ldoshlar orbitada aylanib, signalni qabul qilib qayta uzatadi, shu orqali uzoq masofaga aloqa ta'minlanadi.",

        // --- Yana umumiy va qiziqarli faktlar ---
        "eng kop tilga tarjima qilingan kitob qaysi" to "Dunyoda eng ko'p tilga tarjima qilingan kitob — Bibliya hisoblanadi.",
        "shaxmat qachon oʻylab topilgan" to "Shaxmat o'yini qadimgi Hindistonda, taxminan 1500 yil avval o'ylab topilgan deb hisoblanadi.",
        "velosiped qachon ixtiro qilingan" to "Zamonaviy velosipedning dastlabki shakllari 19-asr boshlarida paydo bo'lgan.",
        "avtomobil qachon ixtiro qilingan" to "Birinchi benzinli avtomobil 1886-yilda Karl Benz tomonidan yaratilgan.",
        "poyezd qachon ixtiro qilingan" to "Birinchi bug' lokomotivi 19-asr boshlarida (1804-yil atrofida) yaratilgan."
    )

    // ---------- O'RGATILADIGAN XOTIRA (oddiy savol-javob xotirasi) ----------

    private const val LEARN_PREF = "nikre_learned"
    private const val LEARN_KEY = "qa_map"

    private fun loadLearned(context: Context): MutableMap<String, String> {
        return try {
            val raw = context.getSharedPreferences(LEARN_PREF, Context.MODE_PRIVATE).getString(LEARN_KEY, "{}")
            val json = org.json.JSONObject(raw ?: "{}")
            val map = mutableMapOf<String, String>()
            json.keys().forEach { k -> map[k] = json.getString(k) }
            map
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun saveLearned(context: Context, map: Map<String, String>) {
        try {
            val json = org.json.JSONObject()
            map.forEach { (k, v) -> json.put(k, v) }
            context.getSharedPreferences(LEARN_PREF, Context.MODE_PRIVATE)
                .edit().putString(LEARN_KEY, json.toString()).apply()
        } catch (e: Exception) {
            // saqlashda xato bo'lsa, e'tiborsiz qoldiramiz
        }
    }

    /**
     * "<savol> javobi <javob>" shaklidagi gapdan savol-javobni ajratib, xotiraga yozadi.
     */
    private fun teachAnswer(context: Context, text: String): String {
        val idx = text.indexOf(" javobi ")
        if (idx < 0) return "Tushunmadim, \"savol javobi javob\" shaklida ayting."
        val question = text.substring(0, idx).trim()
        val answer = text.substring(idx + " javobi ".length).trim()
        if (question.isBlank() || answer.isBlank()) {
            return "Savol yoki javobni tushunmadim, qaytadan ayting."
        }
        val map = loadLearned(context)
        map[question] = answer
        saveLearned(context, map)
        return "Yodlab oldim: \"$question\" uchun javob — \"$answer\"."
    }

    /**
     * Xotiradan savolga mos javobni qidiradi (avval aniq, keyin "fuzzy" moslik bilan).
     */
    private fun findLearnedAnswer(context: Context, text: String): String? {
        val map = loadLearned(context)
        if (map.isEmpty()) return null
        map[text]?.let { return it }
        for ((question, answer) in map) {
            if (fuzzyAny(text, question)) return answer
        }
        return null
    }

    private fun forgetAnswer(context: Context, question: String): String {
        val map = loadLearned(context)
        val key = map.keys.firstOrNull { it == question.trim() || fuzzyAny(question.trim(), it) }
        return if (key != null) {
            map.remove(key)
            saveLearned(context, map)
            "\"$key\" haqidagi bilimni unutdim."
        } else {
            "Bu haqda hech narsa bilmaganman."
        }
    }

    private fun learnedCount(context: Context): String {
        val count = loadLearned(context).size
        return if (count == 0) "Hali hech narsa o'rganmaganman. Menga biror narsa o'rgating!"
        else "Men hozircha $count ta narsani o'rgandim."
    }

    // ---------- YORDAMCHI FUNKSIYALAR ----------

    private fun normalize(input: String): String =
        input.lowercase(Locale.getDefault()).trim()

    /** Soz uzunligiga qarab necha harf xatosiga chidash mumkinligini hisoblaydi. */
    private fun toleranceFor(word: String): Int = when {
        word.length <= 3 -> 0
        word.length <= 6 -> 1
        word.length <= 10 -> 2
        else -> 3
    }

    private fun fuzzyAny(text: String, vararg phrases: String): Boolean =
        phrases.any { phrase ->
            if (text.contains(phrase)) return@any true
            // Fuzzy: har bir so'zni alohida solishtiramiz (harf xatolariga chidamli,
            // uzun so'zlar uchun ko'proq xatoga ruxsat beriladi)
            val phraseWords = phrase.split(" ")
            val textWords = text.split(" ")
            phraseWords.all { pw ->
                textWords.any { tw -> levenshtein(tw, pw) <= toleranceFor(pw) }
            }
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
