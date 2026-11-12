# DuelSira

Jestron'un Duel plugininin yeni sürümü. Duel sandığı kaldırıldı, yerine **Duel Sırası** eklendi.
Paper/Spigot **1.20.1+** · Java 17 · Gereken pluginler: **WorldEdit** (veya FAWE), **WorldGuard**.

## Özellikler

### Duel Sırası (yeni)
- `/duelsira ekle <mob>` (alias `/duelsıra`): tab'da Minecraft'taki tüm moblar listelenir. Mob, durduğun bloğun **tam ortasına** konur ve **baktığın yöne** döner. AI'si kapalıdır, sağa sola dönmez, hasar almaz, yanmaz, dönüşmez. Ticaret, binme ve kayış kullanılamaz. Biri silerse otomatik geri gelir.
- Sıraya **sadece NPC'ye sağ tıklayarak** girilir (komutla girilemez). Tekrar sağ tık atarsan sıradan çıkarsın.
- Sırada kalmak için WorldGuard **pvp=deny** olan bir bölgede olmalısın. PvP açık bölgeye girersen sıradan atılırsın.
- Eşleşince oyuncular hemen ışınlanmaz. Sohbette şu soru çıkar: *"X oyuncusuyla eşleştin! Kabul ediyor musun?"* **[EVET] [HAYIR] [ENVANTERİ GÖR]**
  - **ENVANTERİ GÖR:** rakibin envanteri salt okunur bir menüde açılır. Menüden hiçbir eşya alınamaz.
  - İki taraf da **EVET** derse rastgele boş bir arenada **kuralsız** duel başlar.
  - **HAYIR** diyen sıradan çıkar. Karşı taraf sıranın başına geri alınır ve yeni rakip bekler.
  - Reddeden oyuncu, reddettiği kişiyle **en az bir kez başka biriyle eşleşmeden** tekrar eşleşmez.
  - **15 saniye** içinde yanıt vermeyen taraf veya taraflar sıradan çıkarılır. Yanıt veren taraf sırada kalır.
- Arenaların hepsi doluysa uyarı verir: *"Tüm arenalar dolu, boş arena bekleniyor"*. Hiç arena yoksa uyarır: *"Boş arena yok"*.

### Arena sıfırlama (schem, akıllı)
- `/duel olustur <isim>` komutu WorldEdit seçimini `plugins/DuelSira/arenas/<isim>.schem` olarak kaydeder.
- **Her duel sonunda** arena bu ilk haline döner: kırılan veya konan bloklar düzelir, sandık içerikleri geri gelir. Yere düşen eşyalar, oklar ve tekneler silinir.
- **Akıllı sıfırlama:** Arena önce schem ile karşılaştırılır. Ana thread'de sadece chunk snapshot alınır, karşılaştırma arka planda yapılır.
  - **Hiçbir blok değişmediyse hiçbir şey yapıştırılmaz.**
  - Değişiklik varsa sadece değişen 16×16×16 bölümler yapıştırılır. Çok fazla bölüm değiştiyse tek seferde tam yapıştırma yapılır.
  - Sandık, tabela, fırın, huni gibi NBT değişiklikleri olaylarla takip edilir.
  - Sıfırlama sırasında akan sıvı veya düşen blok olursa arena tekrar kontrol edilir.
  - Aynı anda biten duellolar tick'lere yayılır, böylece lag yapmaz.
  - Güvenlik için her 50 sıfırlamada bir tam yapıştırma yapılır. Bütün ayarlar `config.yml` içindeki `arena-reset` bölümünde.
  - WorldEdit ile arenayı elle düzenlediysen `/duel arena <isim> sifirla` komutu arenayı zorla tam sıfırlar.
- Sunucu duel ortasında çökerse, açılışta "kirli" kalan arenalar otomatik sıfırlanır.

### Normal duel
- `/duel <oyuncu>` komutu GUI açar. GUI'de kit, arena, "eşyalar düşer/korunur" ve kurallar (merdiven, slab, buton, ağ, elytra) seçilir.
- Duel geçmişi `/duel gui` ile açılır. Kayıtlarda tarih, kazanan, arena ve süre bulunur. Oyuncuların duel sonundaki envanteri de salt okunur olarak görülebilir.

## Komutlar

| Komut | Açıklama | Yetki |
|---|---|---|
| `/duel <oyuncu>` | Duel gönder (GUI) | duel.use |
| `/duel kabul <oyuncu>` / `/duel reddet` | İstek yanıtla | duel.use |
| `/duel toggle` | İstekleri aç/kapat | duel.toggle |
| `/duel olustur <isim>` | Seçimden arena + schem | duel.admin |
| `/duel sil <isim>` | Arena sil | duel.admin |
| `/duel arena <isim> setspawn1/setspawn2` | Spawn ayarla (arena içinde) | duel.admin |
| `/duel arena <isim> sifirla` | Arenayı zorla tam sıfırla | duel.admin |
| `/duel arenalar` | Arena durumları | duel.admin |
| `/duel kit olustur/sil <isim>` | Kit | duel.admin |
| `/duel gui` | Duel geçmişi | duel.admin |
| `/duel setlobby` | Duel sonrası dönüş noktası | duel.admin |
| `/duel reload` | Config yenile | duel.admin |
| `/duelsira ekle <mob>` | Sıra NPC'si koy | duelsira.admin |
| `/duelsira kaldır [npc]` | NPC kaldır (tab: eklenen tüm NPC'ler; boşsa en yakın) | duelsira.admin |
| `/duelsira liste` | NPC listesi | duelsira.admin |
| `/duelsira evet` / `hayır` / `envanter` / `ayrıl` | Eşleşmeyi yanıtla / sıradan çık | duelsira.use |

`duel.admin.bypass`: duel sırasında komut engelini atlar.

## Kurulum
1. `DuelSira-1.1.0.jar` dosyasını `plugins/` klasörüne at. WorldEdit ve WorldGuard da kurulu olmalı.
2. Sunucuyu başlat.
3. `//wand` ile arena alanını seç, `/duel olustur arena1` yaz, sonra `setspawn1` ve `setspawn2` ile spawnları ayarla.
4. Lobide WorldGuard ile `pvp deny` olan bir bölge belirle. Örnek: `/rg flag spawn pvp deny`
5. O bölgede `/duelsira ekle villager` yaz.
6. İstersen `/duel setlobby` ile dönüş noktasını ayarla. `config.yml` içindeki `hub-command` duel sonunda çalıştırılacak komuttur.

> Not: `difficulty=peaceful` ise düşman moblar (zombie vb.) oyun tarafından silinir. NPC olarak villager gibi barışçıl bir mob kullan.
> Not: Plugin adında `ı` olamaz (Bukkit kuralı), bu yüzden plugin adı **DuelSira**. Komut hem `/duelsira` hem `/duelsıra` olarak çalışır.

## Kaynak koddan derleme (.zip'ten .jar)

Gerekenler: **JDK 17** ve **Maven 3.8+**.

1. JDK 17 kur ([Adoptium Temurin 17](https://adoptium.net/temurin/releases/?version=17)). Kurulumda "Set JAVA_HOME" seçeneğini işaretle.
2. Maven kur ([maven.apache.org/download.cgi](https://maven.apache.org/download.cgi)). Zip'i aç ve `bin` klasörünü PATH'e ekle.
3. Kontrol et: `java -version` (17 görünmeli), `mvn -v`.
4. `DuelSira-v1.1.0-source.zip` dosyasını aç ve klasöre gir (içinde `pom.xml` olan klasör).
5. Derle:
   ```
   mvn clean package
   ```
6. Jar dosyası şuraya çıkar: `target/DuelSira-1.1.0.jar`

IntelliJ IDEA ile: *File → Open → pom.xml → Open as Project*. Sağdaki Maven panelinde *Lifecycle → package* adımına çift tıkla.

GitHub'a push edildiğinde Actions otomatik derler ve gerçek bir Paper 1.20.1 sunucusunda bot testlerini çalıştırır (`test/e2e.js`). `v*` etiketi atılırsa Release oluşturulur.
