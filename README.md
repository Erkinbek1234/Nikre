# Nikre — Android intellektual yordamchi (Siri o'xshashi)

Offline va online rejimda ishlaydigan ovozli yordamchi.

## Loyihani ochish

1. Android Studio'ni oching (eng so'ngi versiya tavsiya etiladi).
2. **File > Open** orqali shu `nikre/` papkasini tanlang.
3. Gradle sinxronizatsiyasi avtomatik boshlanadi (birinchi marta internet kerak,
   kutubxonalar yuklab olinadi).
4. `Run` tugmasini bosib, telefon yoki emulyatorda ishga tushiring.

## Hozirgi holat (1-bosqich: skelet)

✅ Loyiha tuzilmasi
✅ Asosiy ekran (MainActivity) — ruxsatlarni so'raydi
✅ Fon xizmati (NikreService) — bildirishnoma bilan fonda ishlaydi
✅ Kerakli kutubxonalar `build.gradle`ga qo'shilgan (Vosk, Porcupine, OkHttp)

⬜ Wake word ("Hey Nikre") — hali ulanmagan
⬜ Offline STT (Vosk) — hali ulanmagan
⬜ Online AI (Claude API) — hali ulanmagan
⬜ Offline komandalar (qo'ng'iroq, SMS, signal) — hali ulanmagan
⬜ TTS (javob berish ovozi) — hali ulanmagan

## Papka tuzilmasi

```
app/src/main/java/com/nikre/assistant/
├── MainActivity.kt          # Asosiy ekran
├── service/
│   └── NikreService.kt      # Fonda ishlaydigan asosiy xizmat
├── stt/                     # Ovozni matnga o'girish (keyingi bosqich)
├── wakeword/                # "Hey Nikre" aniqlash (keyingi bosqich)
├── ai/                      # Online AI (Claude API) (keyingi bosqich)
└── commands/                # Offline buyruqlar (keyingi bosqich)
```

## Keyingi bosqichlar (tavsiya etilgan tartib)

1. **Wake word** — Picovoice Console'da (console.picovoice.ai, bepul) "Hey Nikre"
   so'zini train qilib, `.ppn` faylini oling. Keyin `wakeword/PorcupineListener.kt`
   yoziladi.
2. **Offline STT** — Vosk uchun o'zbek/rus/ingliz tili modelini
   (alphacephei.com/vosk/models) yuklab, `assets/` ga qo'yamiz.
3. **Offline komandalar** — soat, signal, qo'ng'iroq, SMS kabi oddiy buyruqlar.
4. **Online AI** — internet bor bo'lganda Claude API'ga ulanib, murakkab
   savollarga javob berish (API kalitini xavfsiz saqlash bilan).
5. **TTS** — Android'ning o'z TextToSpeech'i orqali javob aytish.

Har bir bosqichni alohida so'rab, kod bilan davom ettirishingiz mumkin.
