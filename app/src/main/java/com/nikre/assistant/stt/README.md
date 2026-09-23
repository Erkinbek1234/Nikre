# stt/ (Speech-to-Text)

Bu yerga keyingi bosqichda quyidagilar qo'shiladi:
- `VoskRecognizer.kt` - offline ovozni matnga o'girish (internet yo'q bo'lganda)
- `OnlineSttClient.kt` - online, aniqroq tanib olish (internet bor bo'lganda, ixtiyoriy)

Vosk model fayli (~50MB, o'zbek/rus/ingliz tili) `app/src/main/assets/vosk-model/` papkasiga joylashtiriladi.
