## DuelSira v1.2.0

- **Kuvvet I seçeneği:** `/duel <oyuncu>` menüsünde Kuvvet I aç/kapat düğmesi var. Açıksa duel boyunca iki oyuncuya da Kuvvet I verilir; süt, totem veya `/effect clear` silerse otomatik geri gelir. Duel bitince kaldırılır.
- **Duel sırası:** Tüm sıra duelloları varsayılan olarak Kuvvet I ile atılır. Bunu sadece admin `/duelsira kuvvet <aç|kapat>` ile değiştirebilir (`queue.strength`).
- **Kaldırıldı:** Duel menüsündeki "eşyalar düşer/korunur" düğmesi. Kendi envanteriyle duelda bu artık `settings.items-drop` ayarından gelir; kitli duelde eşyalar her zaman korunur.
- **Kaldırıldı:** Duel kuralları sistemi (merdiven, slab, buton, ağ, elytra), kurallar menüsü ve `defaults` config bölümü.
- **Kaldırıldı:** Sıra eşleşmesindeki [ENVANTERİ GÖR] butonu ve `/duelsira envanter` komutu. Sohbette artık sadece [EVET] [HAYIR] var.
- Duel geçmişinde Kuvvet I bilgisi gösteriliyor.
- Yeni config ayarları: `settings.items-drop`, `settings.strength-default`, `queue.strength`.

## DuelSira v1.1.0: Akıllı arena sıfırlama

- **Değişiklik yoksa schem yapıştırılmaz.** Duel sonunda arena snapshot ile schem'e karşı arka planda karşılaştırılır. Hiçbir blok değişmediyse sunucu hiç yorulmaz.
- **Bölüm bazlı yapıştırma.** Sadece değişen 16×16×16 bölümler geri yüklenir. Değişiklik oranı `full-threshold` değerini geçerse tek seferde tam yapıştırma yapılır.
- **NBT takibi.** Sandık, tabela, fırın, huni, dağıtıcı ve iksir standı değişiklikleri olaylarla işaretlenir ve geri yüklenir.
- **Tekrar kontrol.** Sıfırlama sırasında akan sıvı, düşen kum veya patlama olursa arena tekrar kontrol edilir.
- **Lag koruması.** Kontrol ve yapıştırma işlemleri tick başına sınırlıdır (`max-operations-per-tick`).
- **Güvenlik.** Her N sıfırlamada bir tam yapıştırma yapılır (`full-reset-every`). Yeni `/duel arena <isim> sifirla` komutu.
- **Diğer optimizasyonlar.** Konumdan arena bulma artık chunk indeksiyle O(1). Komut izin listesi önbellekte tutuluyor. Schem karşılaştırma tablosu blok başına 2 bayt.
- Yeni config bölümü: `arena-reset` (smart, full-threshold, full-reset-every, max-operations-per-tick).

## DuelSira v1.0.1

- Duel sırasına **sadece NPC'ye sağ tıklayarak** girilir; komutla girme yolu yoktur (testle doğrulandı).
- `/duelsira kaldır <npc>`: tab'da eklenmiş tüm duel sırası NPC'leri listelenir (`kaldir` yazımı da çalışır). NPC verilmezse en yakın NPC kaldırılır.
- Tab tamamlamada Türkçe yazımlar: `hayır`, `ayrıl`, `kaldır`.

## DuelSira v1.0.0

Paper/Spigot **1.20.1+** · Java 17 · Gereken pluginler: WorldEdit 7.2.15+ (veya FAWE) ve WorldGuard 7.0.9+

### Yeni
- **Duel Sırası:** `/duelsira ekle <mob>` komutuyla hareketsiz bir NPC konur. NPC'ye sağ tıklayan oyuncu sıraya girer. Sırada kalmak için WorldGuard `pvp deny` bölgesinde olmak gerekir.
- Eşleşen oyunculara sohbette **[EVET] [HAYIR] [ENVANTERİ GÖR]** butonları gelir. İki taraf da kabul ederse rastgele boş bir arenada kuralsız duel başlar.
- Reddeden oyuncu sıradan çıkar, karşı taraf sıranın başına alınır. Reddeden, aynı kişiyle ancak araya başka bir eşleşme girdikten sonra tekrar eşleşebilir.
- 15 saniye içinde yanıt vermeyen taraf sıradan çıkar.
- **Arena schem:** Arena oluşturulurken `.schem` olarak kaydedilir ve her duel sonunda bu ilk haline döner. Sunucu çökerse arena açılışta onarılır.
- Arenaların hepsi doluysa veya hiç arena yoksa oyuncular uyarılır.

### Kaldırılan
- Duel sandığı.

### Düzeltilen hatalar (eski Duel 2.0.0)
- Detay menüsünde 1. oyuncunun kafasına tıklamak çalışmıyordu (slot 11 / 10 uyuşmazlığı).
- Kitli duelde hareket edince `NullPointerException` oluşuyordu (kural ayarı yoktu).
- Geçmişte kaybedenin envanteri boş kaydediliyordu.
- Sınır kontrolü iki kez çalışıyordu, sandık güncellemesi üç listener'da tekrar ediyordu.
- `request-timeout` ve diğer config ayarları, mesajlar kullanılmıyordu. Hepsi artık config'den okunuyor.
- Menüler başlık yerine InventoryHolder ile tanınıyor (sahte başlıkla açılamaz). Shift, sayı tuşu, çift tık ve sürükleme ile eşya alınamıyor.
- Ölen oyuncuya `hub` komutu ölüyken çalıştırılıyordu. Artık respawn'dan sonra çalışıyor.
- Kitli duelde oyundan çıkan veya sunucu çökünce envanterini kaybeden oyuncu sorunu giderildi. Envanter yedeği diske yazılıyor ve girişte geri yükleniyor.
- Duel sırasında `/fly`, ender pearl ya da chorus fruit ile arenadan kaçılabiliyordu. Arena dışına blok konulabiliyordu.
- Duel dışındaki oyuncuların duele müdahalesi (vurma) engellendi.
- Geri sayımda oyuncular hareket edemez ve hasar almaz.

### Optimizasyon
- Tüm veritabanı işlemleri tek bir arka plan thread'inde çalışır, ana thread hiç beklemez.
- SQLite sunucu jar'ında zaten olduğu için shade edilmedi (jar ~130 KB).
- Hareket dinleyicileri önce O(1) filtre uygular. WorldGuard sorgusu yalnızca sıradaki oyuncu blok değiştirdiğinde yapılır.
- Elytra kontrolü her harekette değil, `EntityToggleGlideEvent` ile yapılır.
- Arena şematikleri bellekte önbelleklenir ve komşu blok güncellemeleri kapalı yapıştırılır.

### Test
GitHub Actions her push'ta gerçek bir Paper 1.20.1 + WorldEdit 7.2.15 + WorldGuard 7.0.9 sunucusu açar ve üç botla uçtan uca testleri çalıştırır (`test/e2e.js`). Testler sıra, eşleşme, red, red engeli, zaman aşımı, envanter görüntüleme, PvP bölgesi, normal duel, kitli duel, arena sıfırlama ve NPC korumasını kapsar.
