# commands/ (Offline komandalar)

Internet bo'lmaganda ishlaydigan oddiy buyruqlar shu yerda bo'ladi:
- "soat nechi" -> vaqtni aytish
- "signal qo'y" -> AlarmManager
- "qo'ng'iroq qil [ism]" -> CALL_PHONE intent
- "SMS yubor [ism]ga [matn]" -> SEND_SMS
- "musiqa qo'y / to'xtat" -> media session boshqaruvi
- "yorug'lik/tovushni oshir/kamaytir" -> tizim sozlamalari

`OfflineCommandHandler.kt` - matnni (Vosk'dan kelgan) tekshirib, mos komandani ishga tushiradi.
Internet bo'lmasa shu yerga tushadi; internet bo'lsa `ai/ClaudeApiClient.kt` ga yuboriladi.
