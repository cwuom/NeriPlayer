[English](./README_EN.md) | [中文](./README.md) | [Türkçe](./README_TR.md)

<h1 align="center">NeriPlayer</h1>

<div align="center">

<h3>✨ Çok kaynaklı akış, yerel kütüphaneler, kendi barındırdığınız sunucular (önizleme) ve kelime senkronlu şarkı sözlerini bir araya getiren yerel bir Android ses oynatıcısı 🎵</h3>

<p><strong>🖥️ PC sürümünü mü arıyorsunuz? <a href="https://github.com/cwuom/NeriPlayer-Desktop">NeriPlayer Desktop deposunu ziyaret edin →</a></strong></p>

<p>
  <a href="https://github.com/cwuom/NeriPlayer/releases">
    <img alt="Downloads" src="https://img.shields.io/github/downloads/cwuom/NeriPlayer/total?style=social" />
  </a>
  <a href="https://github.com/cwuom/NeriPlayer/releases/latest">
    <img alt="Stable release" src="https://img.shields.io/github/v/release/cwuom/NeriPlayer?label=Release" />
  </a>
  <img alt="Android 9+" src="https://img.shields.io/badge/Android-9%2B-3DDC84?logo=android&logoColor=white" />
  <a href="https://t.me/ouom_pub">
    <img alt="Telegram" src="https://img.shields.io/badge/Telegram-@ouom__pub-blue" />
  </a>
  <a href="https://t.me/neriplayer_ci">
    <img alt="CI Builds" src="https://img.shields.io/badge/CI_Builds-@neriplayer__ci-orange" />
  </a>
</p>

<p>
  <img src="icon/readme-banner.svg" width="100%" alt="NeriPlayer — Modern Müzik Oynatıcısı" />
</p>

<p>
Proje adı ve simgesi, "星空鉄道とシロの旅" eserindeki "Kazamata Neri" karakterinden
esinlenmiştir.
</p>

<p>
NeriPlayer, çok kaynaklı keşif, çevrimiçi oynatma, yerel kontrol ve
kullanıcıya ait verilere odaklanan, Android 9 (API 28) ve üzeri için yerel bir Android uygulamasıdır.
</p>

🛠️ <strong>Aktif geliştirme aşamasında</strong>

<a href="https://trendshift.io/repositories/23906" target="_blank"><img src="https://trendshift.io/api/badge/repositories/23906" alt="cwuom%2FNeriPlayer | Trendshift" style="width: 250px; height: 55px;" width="250" height="55"/></a>

</div>

> [!WARNING]
> **Kendi barındırdığınız müzik sunucusu desteği önizleme aşamasındadır ve henüz yeterince test edilmemiştir.**

---

> [!WARNING]
> Bu proje yalnızca öğrenme ve araştırma amaçlıdır. Yasa dışı amaçlarla kullanmayın. İçeriklere yalnızca hakkınız, yetkiniz veya platform izniniz olduğu durumlarda erişin, oynatın veya kaydedin.
>
> Proje hiçbir medya içeriği, anahtar veya ödeme duvarı / DRM / bölge aşma mekanizması sunmaz; ayrıca herkese açık bir bulut kütüphanesi, medya proxy'si veya yeniden dağıtım hizmeti sağlamaz.
>
> Seçilen bölgede herkese açık YouTube Music içeriklerini misafir olarak gezebilir, arayabilir ve oynatabilirsiniz. Kişisel kütüphaneler hesap yetkilendirmesi gerektirir. Yalnızca üyelere özel ve kısıtlı içerikler sağlayıcının kurallarına tabidir.
>
> Bu proje ve bakımcısı hiçbir sponsorluk, bağış veya ticari fon kabul etmez.

---

## Buradan Başlayın

| Hızlı Bağlantılar | Açıklama |
| :--- | :--- |
| [Başlarken](#başlarken) | APK'ları indirin, CI derlemelerini alın veya kaynaktan derleyin |
| [Temel Özellikler](#temel-özellikler) | Oynatma, ses efektleri, şarkı sözleri ve yerel yönetim |
| [Platform Durumu](#platform-durumu) | Her platformun desteklediği özellikler |
| [Müzik sunucusuna bağlanma](#müzik-sunucusuna-bağlanma) | Kendi barındırdığınız Navidrome / OpenSubsonic kütüphanesini bağlayın (Önizleme) |
| [Birlikte Dinle Dağıtımı](#birlikte-dinle-dağıtımı) | Cloudflare Workers üzerinde gerçek zamanlı bir dinleme odası barındırın |
| [docs/implementation_EN.md](./docs/implementation_EN.md) | Mimari, modül sınırları, başlangıç akışı ve testler |
| [CONTRIBUTING_EN.md](./CONTRIBUTING_EN.md) | Geliştirme kurulumu, pull request iş akışı ve yönergeler |

**Öne Çıkanlar**

| Boyut | Öne Çıkanlar |
| :--- | :--- |
| **Çok Kaynaklı ve Kendi Sunucunuz** | Tek bir paylaşılan kuyrukta NetEase Cloud Music, Bilibili, YouTube Music, yerel sesler ve Navidrome / OpenSubsonic (Önizleme) |
| **Şarkı Sözleri** | Satır ve kelime senkronlu LRC / YRC / TTML, Bilibili CC altyazıları, çeviri, transliterasyon ve çok kaynaklı eşleştirme; yüzen pencere, durum çubuğu, Bluetooth / araç ve sistem çıktıları |
| **Ses ve Görseller** | Media3 motoru, kendi DSP'si, 10 bantlı ekolayzer, AutoEQ, 32-bit çıktı, USB özel modu, DLNA / UPnP yayını; Material You ve Clean temaları, akışkan arka plan, widget'lar ve başlatıcı kısayolları |
| **Önce Yerel ve Kullanıcıya Ait Senkronizasyon** | Veriler varsayılan olarak cihazda kalır; isteğe bağlı olarak kendi GitHub deponuza veya WebDAV uzak sunucunuza meta veri senkronizasyonu |
| **Yerleşik Kurtarma** | Çevrimdışı mod, güvenli mod, JVM / Native çökme günlükleri, ANR izleri ve hata ayıklama probları |

## Hakkında

NeriPlayer, **Jetpack Compose + Media3** ile geliştirilmiş yerel bir Android ses oynatıcısıdır. **NetEase Cloud Music**, **Bilibili**, **YouTube Music**, **kendi barındırdığınız müzik sunucuları** ve **yerel sesleri** tek bir oynatma ve kütüphane deneyiminde birleştirir. Yerel oynatma ve herkese açık YouTube Music içerikleri misafirler için kullanılabilir. Kişisel kütüphaneler ilgili platformda oturum açmayı gerektirir.

- **Kendi barındırdığınız müzik (Önizleme)**: Mevcut oynatıcı, albüm kapağı, şarkı sözleri, yerel favoriler, çalma listeleri ve orijinal dosya çevrimdışı indirmeleriyle albümlere, şarkılara ve sanatçılara göz atmak ve arama yapmak için Kütüphane → Müzik sunucuları altında Navidrome / OpenSubsonic'i bağlayın (bkz. [Müzik sunucusuna bağlanma](#müzik-sunucusuna-bağlanma)).
- **Önce yerel**: Önbellek, indirmeler, çalma listeleri, geçmiş, ayarlar ve kimlik doğrulama verileri cihazda saklanır.
- **İsteğe bağlı senkronizasyon**: Çalma listeleri, favoriler, son çalınanlar ve oynatma istatistikleri kendi GitHub deponuza (uygulama içinden oluşturulduğunda varsayılan olarak gizlidir) veya bir WebDAV uzak sunucusuna senkronize edilebilir.
- **Kullanıcıya ait veriler**: Senkronize edilen veriler yalnızca sizin kontrol ettiğiniz depolamaya gider, asla bakımcı tarafından işletilen bir sunucudan geçmez.
- **Hesap güvenliği**: Oynatma geçmişi ve istatistikler varsayılan olarak üçüncü taraf platformlara gönderilmez. Platform sıralamalarını veya izleme geçmişini istiyorsanız, Ayarlar → Üçüncü Taraf Oturum Açma bölümünden NetEase / Bilibili dinleme geçmişi senkronizasyonunu ayrı olarak açın.

> 💡 **Üç bağımsız özellik**
>
> - **Müzik sunucuları**: Kendi Navidrome / OpenSubsonic kütüphanenizi bağlayın.
> - **Birlikte Dinle**: Oynatma durumunu cihazlar arasında gerçek zamanlı olarak senkronize edin.
> - **GitHub / WebDAV senkronizasyonu**: Ses dosyaları veya müzik sunucusu profilleri ve kimlik bilgileri olmadan çalma listelerini, istatistikleri ve diğer meta verileri senkronize edin.

Mimari, başlangıç akışı ve testler [docs/implementation_EN.md](./docs/implementation_EN.md) ve [CONTRIBUTING_EN.md](./CONTRIBUTING_EN.md) dosyalarında ele alınmıştır.

### Müzik sunucusuna bağlanma

> [!WARNING]
> Sınırlı testlere sahip önizleme sürümüdür. Uyumluluk ve kararlılık sunucuya, kütüphaneye ve ağa göre değişiklik gösterir. Sunucuya geri yazma, Birlikte Dinle ve profil taşıma henüz bağlanmamıştır.

1. Ayarlar → Üçüncü taraf platform oturum açma → Müzik sunucuları altında bir ad, adres, kullanıcı adı ve parola girin, ardından bağlantı kontrolü geçtikten sonra kaydedin.
2. `https://music.example.com:4533/navidrome/` gibi sunucunun temel adresini kullanın. Bağlantı noktaları ve ters proxy alt yolları sorunsuz çalışır; `/app/` ve `/rest/` kısımlarını eklemeyin.
3. Kütüphane → Müzik sunucuları'nı açın, bir profil seçin ve albümlere, şarkılara ve sanatçılara göz atın veya arama yapın. Albümlerini görmek için bir sanatçıyı açın.
4. Kategorileri değiştirmek arama sorgunuzu korur; bir albümün içinde arama yapmak yalnızca o albümü filtreler.
5. Bir şarkıyı çalın veya favorilere, kuyruğa ya da yerel bir çalma listesine ekleyin.
6. Şarkı sözleri önce sunucunun yapılandırılmış şarkı sözlerinden, ardından standart şarkı sözü uç noktasından gelir.
7. Bir şarkıya uzun basın veya birkaç şarkı seçin, ardından Yerele indir'i seçin. Sunucu izin veriyorsa dosyalar orijinal biçimini korur; indirme yöneticisinde ilerleme ve yeniden denemeler yer alır.
8. Tamamlanan indirmeler Yerel müzik → İndirilenler altında görünür ve çevrimdışı olarak veya profili kaldırdıktan sonra bile çalınabilir kalır.

**Nasıl çalışır**

- **Dizin önbelleği**: Bir profili açmak tam albüm, şarkı ve sanatçı dizinlerini yükler ve önbelleğe alır. Aramalar yerel dizinleri kullanır ve önbellek 24 saat boyunca yeniden kullanılır.
- **Manuel yenileme**: Kütüphane değişikliklerinden sonra yenileyin; başarısız bir yenileme mevcut dizinleri korur.
- **Medya yükleme**: Önbellek yalnızca meta verileri tutar. Kapak görselleri isteğe bağlı olarak yüklenir ve sesler oynatma veya indirme sırasında getirilir.
- **Sanatçı görselleri**: Önce sunucu görselleri, aksi takdirde sanatçının kapağı olan son şarkısının kapağı kullanılır.
- **Kimlik bilgileri**: Profiller ve parolalar şifrelenmiş yerel depolamada tutulur ve senkronizasyonla taşınmaz.
- **Düzenleme**: Yalnızca ad ve parola değiştirilebilir. Yeni bir adres veya kullanıcı adı yeni bir profil gerektirir ve mevcut çalma listesi girişleri taşınmaz.
- **Kapak görseli senkronizasyonu**: Kapaklar kimlik doğrulama parametreleri olmadan uç nokta URL'leri olarak senkronize edilir; özel sunucular yine de oturum açma gerektirir.
- **Birden fazla cihaz**: Aynı adres ve kullanıcı adına sahip başka bir Android cihaz bu kapakları yükleyebilir. Masaüstü istemcileri şimdilik yalnızca URL'leri saklar.

Protokol ve istemci davranışı için [API sözleşmesine](docs/music-server-api.md) (Çince) bakın.

---

## Temel Özellikler

### 🎧 Oynatma ve ses kalitesi

- **Çok kaynaklı ve yerel oynatma**: NetEase Cloud Music, Bilibili, YouTube Music ve yerel sesler tek bir kuyruğu paylaşır.
- **Oynatma yedeği (fallback)**: NetEase önce kaliteyi düşürür; Bilibili yedeği açıkken adaylar başlık, sanatçı ve süreye göre eşleştirilir ve tekrarlanan hatalar atlanır.
- **YouTube Music uyumluluğu**: Geçerli kimlik çerezleri korunur ve döndürülür; önbelleğe alınmış bir `player.js` veya oynatma adayı başarısız olursa, oynatma EJS / HLS'ye geri döner.
- **Ses efektleri**: Yerleşik ön ayarlara sahip kendi DSP'si, 10 bantlı ekolayzer, AutoEQ içe aktarma, ses yüksekliği dengeleme ve 32-bit yüksek çözünürlüklü çıktı. Kapatıldığında tümü atlanır.
- **USB özel çıktısı**: UAC1.0 ve UAC2.0 Type I PCM DAC'lere bit-kusursuz (bit-perfect) çıktı; USB takılma olaylarına yanıt verme kapatılabilir.
- **DLNA / UPnP yayını**: Şu An Çalınıyor çıktı sayfasından yerel ağ (LAN) oynatıcılarını tarayın veya manuel olarak ekleyin; kayıpsız FLAC doğrudan aktarımı, WAV uyumluluk yedeği ve uyarlanabilir doğrudan akış desteğiyle.
- **Arka planda oynatma**: Uygulama değiştirdiğinizde veya NeriPlayer'ı Son Kullanılanlar'dan kaydırıp kapattığınızda müzik ön plan medya servisi üzerinden çalmaya devam eder.
- **Yüzen pencere ile canlı tutma**: Sistemin arka plan oynatmayı dondurmasını zorlaştıran isteğe bağlı görünmez 1 piksellik pencere.
- **Oynatma davranışı**: Yumuşak giriş/çıkış (fade in/out), çapraz geçiş (crossfade), Bluetooth bağlantısı kesildiğinde duraklatma, diğer uygulamalarla ses karıştırma ve ses odağı politikası. Açılışta kalınan konum ve oynatma modu geri yüklenir.
- **Bilibili atlama**: SponsorBlock otomatik atlama ve özel aralıklar; varsayılan olarak kapalıdır ve Birlikte Dinle sırasında etkin değildir.

### 🎤 Şarkı Sözleri

- **Satır ve kelime düzeyi**: Vurgulama, çeviri, transliterasyon, zaman kaydırma (offset), dokunarak atlama ve tam ekran şarkı sözleri ile LRC / YRC / TTML desteği.
- **CC altyazı sözleri**: Bilibili şarkıları, parça seçimi ve otomatik oluşturulan yedek ile videonun CC altyazılarını kullanabilir.
- **Kaynaklar ve düzenleme**: Otomatik kaynak tercihi (NetEase Cloud Music / Kugou / QQ Music / LRCLIB / AMLL), ayrıca manuel eşleştirme ve düzenleme.
- **Harici çıktı**: Yüzen şarkı sözleri, durum çubuğu sözleri, SuperLyric, Lyricon, Bluetooth sözleri ve araç ekranı.
- **ColorOS kilit ekranı sözleri**: Deneyseldir; Root veya Shizuku gerektirir ve ROM desteğine bağlıdır.

### 🗂️ Kütüphane ve yerel yönetim

- **NetEase çalma listelerine ekleme**: Bir NetEase Cloud Music kaynağından çalarken sağ üstteki üç nokta menüsünü açın, “Add to NetEase playlist” (NetEase çalma listesine ekle) seçeneğini seçin ve çalma listelerinizden birini belirleyin.
  - Oturum açma ve ağ bağlantısı gerektirir.
  - Oynatma Bilibili veya yerel sese düştüğünde bu seçenek gizlenir.
- **Kategoriye göre gezinme**: Yerel, favoriler, NetEase Cloud Music, YouTube Music ve Bilibili girişleri. YouTube Music tamamen devre dışı bırakılabilir ve Bilibili müzikleri yalnızca ana sayfadan gizlenebilir.
- **Ana sayfa keşfi**: Yerel ana sayfa listeleri, günlük seçimleri ve radar çalma listelerini gösterir. Uluslararası ana sayfada hangi tür YouTube Music önerilerinin görüneceğini seçmek için **Ana sayfa ve başlangıç → Önerileriniz** bölümünü açın ve parçaları, çalma listelerini, albümleri veya sanatçıları açıp kapatın. Her ana sayfa kendi düzenini korur.
- **Arama**: NetEase Cloud Music, Bilibili ve YouTube Music'te sayfalama özellikli platform bazlı arama.
- **Bağlantı tanıma**: NetEase Cloud Music, Bilibili ve YouTube Music içeriklerini çözümlemek için başlık veya kısa bağlantı içeren bir paylaşım metni yapıştırın.
- **İçe aktarma ve toplu kapak görseli**: Paylaşım içe aktarma ve klasör tarama, ayrıca yerel şarkılar için tek dokunuşla toplu şarkı sözü ve kapak görseli eşleştirme ve gömme.
- **Yerel sanatçılar ve çalma listeleri**: Sıralama, blok ekleme ve silme işlemleri için geri alma özelliğiyle görünen sanatçıya göre gruplandırılır.
- **Şarkı ansiklopedisi**: Türler, dil, BPM, katkıda bulunanlar, yorum önizlemeleri, benzer şarkılar ve ilgili çalma listeleri.

### ⬇️ İndirmeler, önbellek ve depolama

- **Uygulama içi indirmeler**: Devam ettirme ve başlangıç kurtarma özellikli çok platformlu ses indirme, varsayılan olarak 6 eşzamanlı indirme (1–8 arası ayarlanabilir).
- **Özel dizin**: SAF dizini ve dosya adı şablonları; değiştirdiğinizde mevcut indirmeler taşınır.
- **Önbellek sınırı**: Ses önbelleği varsayılan olarak 1 GB ile sınırlandırılmıştır.
- **Son çalınanları koru**: 0–1000 parça kaydırıcısı son çalınan şarkıları korur; geçerli parça her zaman korunur.
- **Yerel içe aktarma önbelleği**: Ayarlar → Depolama ve Önbellek → Önbelleği Temizle, artık referansı kalmayan çıkarılmış kapakları ve geçici tarama dizinlerini kaldırır.
- **Kapak saklama**: Geçmiş, istatistikler veya diğer kayıtlar tarafından hala kullanılan kapaklar saklanır; geçerli oturumda kullanılan kapaklar bir sonraki soğuk başlangıçtan sonra yeniden değerlendirilir.

### 🔄 Senkronizasyon, istatistikler ve yedekleme

- **GitHub / WebDAV senkronizasyonu**: Çalma listeleri, favoriler, son çalınanlar, silme kayıtları ve oynatma istatistikleri kendi sahip olduğunuz uzak sunucularda saklanır.
- **Oynatma istatistikleri**: Parça, sanatçı ve albüm sıralamalarını, dinleme günlerini ve hafta içi gün ve saate göre dinlemeleri keşfedin. Güne, haftaya, aya, yıla veya özel bir tarih aralığına göre göz atın. Senkronizasyon etkinken kaydedilen saatlik toplamlar cihazlar arasında kullanılabilir. Her dinleme aralığının kesin başlangıç ve bitiş zamanları yalnızca onu kaydeden cihazda kalır.
- **Trafik istatistikleri**: Wi-Fi, mobil ve dolaşım olarak ayrılır.
- **Yedekleme ve geri yükleme**: Çalma listesi JSON içe ve dışa aktarma.
- **Yapılandırma dışa aktarma**: Varsayılan olarak oturum açma durumunu hariç tutar; isteğe bağlı olarak kimlik doğrulama dahil edilerek şifrelenebilir.

### 🎨 Arayüz ve kişiselleştirme

- **İki arayüz stili**: Material You ve sıvı cam (liquid glass) efektini destekleyen önizleme **Clean** stili.
- **Tema ve görünüm**: Dinamik renk, özel arka plan, şarkı sözü yazı tipi boyutu ve arayüz ölçeklendirmesi (telefonlarda 0.6–1.2x, araç modunda 0.6–1.5x).
- **Akışkan arka plan**: Oynatıcı sayfasında sese tepki veren, kapak renkli bir `RuntimeShader` arka planı (Android 13+).
- **Masaüstü ve araç**: Üç widget boyutu, başlatıcı kısayolları ve Bluetooth araç meta verileri.
- **Büyük ekranlar**: Tablet yatay modunda tam bir şarkı sözü sütunu sabitlenir; telefon yatay modunda kapak ve şarkı sözleri arasında geçiş yapılır.

### 👥 Birlikte Dinle

- **Birleşik giriş**: Şu An Çalınıyor → Daha Fazla → Birlikte Dinle altındaki kaydırılabilir sekmeleri kullanarak Bulut ve Yerel Ağ (LAN) arasında geçiş yapın. Tablet mini oynatıcısı ve Ayarlar aynı gruplamayı kullanır.
- **Odalar**: Gerçek zamanlı WebSocket senkronizasyonuyla bir oda oluşturun veya katılın.
- **Katılma**: İlk katılımda davet anahtarı gerekir; yeniden bağlanmalarda üye anahtarı kullanılır.
- **Senkronize durum**: Sunucu / dinleyici rolleri, tekrarlama ve karıştırma modları, saat kayması düzeltmesi ve sunucu çevrimdışı kurtarma.
- **Oynatma adayları**: Sunucu en fazla 3 aday paylaşabilir; dinleyiciler bunları önce kendi kalite politikalarıyla çözümler.

- **Yerel Ağ (LAN) Birlikte Dinle**, aynı Wi-Fi ağındaki cihazların odaları keşfetmesine veya adresle katılmasına olanak tanır. Odaları oynatıcıdaki hoparlör düğmesinden, Şu An Çalınıyor → Daha Fazla bölümünden veya Ayarlar → Birlikte Dinle'den yönetin. Sunucu sesi, kapak görselini ve şarkı sözlerini paylaşabilir. Üyeler varsayılan olarak oynatabilir ve duraklatabilir. İlerleme izni hem parça atlamayı hem de sarmayı kontrol eder ve varsayılan olarak etkindir. Üyeler oda ses düzeyini görebilir ve kendi cihazlarının ses düzeyini ve zamanlama kaymasını ayarlayabilirler. Sunucu ses kontrolü izni verirse, üyeler tüm cihazları birlikte ayarlamak için oda ses düzeyi kaydırıcısını sürükleyebilir veya yalnızca sunucu cihazın ses düzeyini değiştirebilirler. İzin ayarları sunucu cihazda kaydedilir. LAN odaları bulut odalarından ayrı çalışır ve şu anda USB özel çıktısını desteklemez. Bkz. [modül dokümantasyonu](modules/ltwlan/README.md).

### 🛟 Kararlılık ve tanılama

- **Çevrimdışı mod**: Ağ durumunu algılar ve çevrimdışıyken yalnızca yerel dosyaları ve önbelleği kullanır.
- **Güvenli mod**: Bir JVM / Native çökmesi veya sistem ANR'sinden sonra günlükleri dışa aktarmak için güvenli moda girin.
- **Geliştirici modu**: Hata ayıklama problarını ve günlük görüntüleyiciyi etkinleştirmek için sürüm numarasına 7 kez dokunun.

---

## Başlarken

### a. Kararlı sürümü indirin (önerilen)

Cihaz mimarinize uygun APK'yı [en son kararlı sürümden](https://github.com/cwuom/NeriPlayer/releases/latest) indirin.

| APK Mimarisi (ABI) | Hedef Cihazlar |
| :--- | :--- |
| `arm64-v8a` | Çoğu modern telefon ve tablet (önerilen) |
| `armeabi-v7a` | Eski 32-bit ARM cihazlar |
| `x86` / `x86_64` | Masaüstü emülatörleri, Intel cihazlar veya Chromebook'lar |

> [!IMPORTANT]
> Bir grup özellik tamamlanıp testlerden geçtikten sonra kararlı sürümleri manuel olarak yayınlıyoruz. Yine de bazı sorunlar gözden kaçabilir.

### b. Nightly ön sürümünü indirin

[GitHub Releases](https://github.com/cwuom/NeriPlayer/releases) üzerinde **Nightly** olarak işaretlenmiş bir ön sürüm seçin. Her Nightly, yukarıda listelenen dört mimarinin her biri için imzalı bir APK içerir. CI, varsayılan daldaki en son commit'i her gün 22:00'de (UTC+8) kontrol eder ve commit başına en fazla bir Nightly yayınlar. Yalnızca en son Nightly saklanır.

Bir Nightly ön sürümünde sorunla karşılaşırsanız, [her zaman bildirebilirsiniz](https://github.com/cwuom/NeriPlayer/issues/new?template=bug-report.yml). İnceleyebilmemiz için derleme sürümünü ve sorunu yeniden oluşturma adımlarını ekleyin.

### c. CI derlemesi indirin

1. [Android CI](https://github.com/cwuom/NeriPlayer/actions/workflows/android_ci.yml) sayfasına gidin, en son başarılı derlemeden Artifacts dosyasını indirin ve ayıklayın.
2. Veya [NeriPlayer CI Builds](https://t.me/neriplayer_ci) kanalını ziyaret edin.

> master dalındaki CI varsayılan olarak bir `arm64-v8a` APK yükler.

### d. Yerel derleme

1. Depoyu klonlayın ve alt modülleri başlatın:
   ```bash
   git clone --recursive https://github.com/cwuom/NeriPlayer.git
   cd NeriPlayer
   ```
2. JDK 17'yi yükleyin ve projeyi en son kararlı Android Studio ile açın.
3. `:playback:runtime` JVM testlerini veya tam modül kapısını çalıştırmak için Gradle'ın bulabileceği bir JDK 21 de gerekir (üretim bayt kodu yine 17'yi hedefler).
4. Debug APK'yı derleyin ve yükleyin:
   ```bash
   ./gradlew :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
5. İlk açılışta sorumluluk reddini okuyun ve başlangıç adımlarını tamamlayın. Bildirim ve yerel müzik izinleri yalnızca siz izin verdikten sonra istenir.
6. Hata ayıklama araçları için Ayarlar'da **sürüm numarasına** 7 kez dokunun.

**Debug derlemeleri hakkında**

- Yalnızca test amaçlıdır; performans ve boyut Release derlemelerinden farklıdır.
- `moe.ouom.neriplayer.debug` paket adını, turuncu bir başlatıcı simgesini ve `DEBUG` ad sonekini kullanırlar ve Release derlemeleriyle yan yana bulunabilirler.
- Debug'dan paylaşılan davetler `neriplayer-debug://` kullanır; normal davet bağlantıları da katılmak için yapıştırılabilir.
- Başlangıç adımlarından sonra bir Debug derlemesi uyarısı görünür. Onaylandıktan sonra gizli kalır; uygulama verilerini temizlemek veya yeniden yüklemek uyarıyı tekrar gösterir.

Android Studio'dan çalıştırmak için `app` çalıştırma yapılandırmasını ve Build Variants içinde `:app` için `debug` varyantını seçin.
Uygulama kimliği yapılandırmasını değiştirdikten veya dal değiştirdikten sonra **Sync Project with Gradle Files** komutunu çalıştırın. Aksi takdirde IDE önbelleğe alınmış başlatma paketini koruyabilir ve yüklü Release derlemesini başlatabilir.

Release derlemesi ve imzalama ayrıntıları için [CONTRIBUTING_EN.md](./CONTRIBUTING_EN.md#release-build) belgesine bakın.

---

## Platform Durumu

### NetEase Cloud Music

- Oturum açma, arama, küratörlü çalma listeleri, albümler, bulut depolama, oynatma, indirmeler, şarkı sözleri ve meta veri tamamlama.
- İzin eksik olduğunda oynatma yedeği (fallback); yerel favoriler "My Favorites" ile senkronize edilebilir.
- Sanatçı sayfaları, şarkı ansiklopedisi ve sanatçı takibi.
- Yorumlar popüler / en yeni / önerilen sıralamayı, iç içe yanıtları ve kopyalamayı destekler; oturum açmış kullanıcılar beğenebilir, gönderi paylaşabilir ve yanıtlayabilir.
- Platform kurallarına ve hesap izinlerine tabidir.

### Bilibili

- Web ve QR ile oturum açma, video arama, oluşturulan ve abone olunan favoriler ve koleksiyonlar.
- Çok parçalı videolar ses olarak oynatılır ve indirilir.
- Ana sayfa Popüler ve Orijinal müzik listelerini ve popüler müzik videolarını gösterir.
- Bağlantı tanıma, seçilen parçayı korur ve `season_id` üzerinden koleksiyon bağlamını geri yükler.
- Yorumlar popüler / en yeni sıralamayı ve iç içe yanıtları destekler; oturum açmış kullanıcılar etkileşime girebilir.
- Ses odaklıdır; tam video akışı yoktur.

### YouTube Music

- Ana sayfada YouTube Music önerilerini göstermek için Ayarlar → Dil ve içerik bölümünden uluslararası modu etkinleştirin. Keşfet; şarkıları, videoları, sanatçıları, albümleri, öne çıkan çalma listelerini ve topluluk çalma listelerini destekler.
- Uygulama dilini istediğiniz zaman değiştirebilirsiniz. Uluslararası mod açıkken aynı sayfada içerik dilinizi ve bölgenizi seçin. İçerik ve keşif altında çalma listelerinin nasıl önerileceğini ve müstehcen içeriklerin veya müzik videolarının gizlenip gizlenmeyeceğini seçin. Uluslararası ana ekrandaki dünya simgesi bu sayfayı açar. Sistem bölgesi Çin anakarasına ayarlı cihazlar, içerik dili için varsayılan olarak Basitleştirilmiş Çince ve içerik bölgesi için Japonya'yı kullanır.
- Uygun oturum açılmış oturumlar otomatik olarak yenilenir. Hesap ayarları yenileme durumunu gösterir. Ağ ayarları HTTP ve SOCKS5 proxy'leri ile DNS over HTTPS (DoH) destekler.
- İçerik erişimi platform kurallarına ve hesap izinlerine tabidir.

### Kendi Barındırdığınız Müzik Sunucusu (Navidrome / OpenSubsonic)

- Albümlere, şarkılara ve sanatçılara göz atın ve arama yapın; bir sanatçının albümlerini görüntüleyin.
- Mevcut oynatıcıyı, kapak görsellerini ve şarkı sözlerini kullanır.
- Yerel favorileri, çalma listesi yönetimini ve orijinal dosya çevrimdışı indirmelerini destekler.
- Sınırlı testlere sahip önizleme aşamasındadır; sunucuya geri yazma, Birlikte Dinle ve profil taşıma henüz bağlanmamıştır.

### QQ Music

- Şu An Çalınıyor meta verisi ve şarkı sözü tamamlama.
- Oturum açma, oynatma veya kütüphane sayfaları yoktur.

### Yerel Sesler

- Paylaş / aç ile içe aktarma, cihaz ve klasör tarama ve oynatma.
- Sanatçı gruplama, paylaşım ve çalma listesi yönetimi.

Çevrimiçiyken, hangi bölümün çalınacağını seçmek için Favorilerim'deki veya yerel bir çalma listesindeki masaüstünden senkronize edilmiş çok parçalı bir Bilibili videosuna dokunun.

---

## Uygulama Notları

Derleme sürümleri, modül sınırları ve temel uygulama yolları ayrı olarak belgelenmiştir:

- [docs/implementation_EN.md](./docs/implementation_EN.md): derleme sürümleri, 15 kendi kütüphanesi, giriş noktaları ve gezinme, oynatma ve önbelleğe alma, arama ve veri kaynakları, yerel veriler ve güvenlik, indirmeler ve yedekleme.
- [CONTRIBUTING_EN.md](./CONTRIBUTING_EN.md#project-layout): modül sınırları, genişletme yolları, testler ve gönderim kuralları.

---

## Birlikte Dinle Dağıtımı

NeriPlayer, Birlikte Dinle özelliğini içerir. Kendi sunucunuzu dağıtabilir veya başka biri tarafından dağıtılan bir sunucuyu kullanabilirsiniz.

- Depo içi sunucu kaynağı: `np-submodule/NeriPlayer-LTW`
- Herkese açık dağıtım şablonu: [TheSmallHanCat/NeriPlayer-LTW](https://github.com/TheSmallHanCat/NeriPlayer-LTW)

Sunucu **Cloudflare Workers** ve **Durable Objects** üzerinde çalışır ve WebSocket üzerinden gerçek zamanlı olarak senkronize olur.

### Cloudflare Workers'a tek tıkla dağıtım

[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/TheSmallHanCat/NeriPlayer-LTW)

Uygulamanın Ayarlar bölümünde sunucu adresini belirleyebilir, kullanılabilirliği test edebilir ve yerel Birlikte Dinle kimliğini sıfırlayabilirsiniz.

- Oda kimlikleri 6 okunabilir karakterden oluşur.
- Takma adlar 1–24 karakterdir: Çince, Latin harfleri ve rakamlar.
- İlk katılımda davet bağlantısındaki anahtar kullanılmalıdır.
- Bulut odaları çevrimiçi parçaları destekler. LAN odaları yerel sesleri de destekler.

Tam protokol, olay modeli ve dağıtım ayrıntıları için [np-submodule/NeriPlayer-LTW/README.md](./np-submodule/NeriPlayer-LTW/README.md) dosyasına bakın.

---

## Senkronizasyon

NeriPlayer, meta verileri **kendi** GitHub deponuza veya bir WebDAV uzak dosyasına senkronize edebilir. Uygulama içinden oluşturulan depolar varsayılan olarak gizlidir ve mevcut bir depoyu da bağlayabilirsiniz.

**Senkronize edilen nesneler**: yerel çalma listeleri, favori çalma listeleri, son çalınanlar, son çalınan silme kayıtları ve oynatma istatistikleri.

### Kullanım

1. Ayarlar'da Yedekleme ve Senkronizasyon'u açın.
2. GitHub için `repo` kapsamına sahip bir Personal Access Token oluşturun.
3. Belirteci doğrulayın, ardından varsayılan gizli depoyu oluşturun veya mevcut bir depoyu bağlayın.
4. Otomatik senkronizasyonu etkinleştirin veya Şimdi senkronize et'e dokunun.

Yeni kurulumlar doğrudan V4 kullanır. Bir yükseltme iletişim kutusu görünürse, önce senkronizasyona katılan her cihazı güncelleyin, ardından adresi taşımak için onaylayın ve hemen senkronize edin.

### Davranış

- 🔒 **Kimlik bilgileri**: GitHub belirteci ve WebDAV parolası `Android Keystore + EncryptedSharedPreferences` içinde saklanır.
- ⚠️ **Aktarım**: Düz HTTP adresleri bir şifresiz metin (cleartext) uyarısı gösterir.
- 🔄 **Zamanlama**: Bir değişiklik 5 saniye sonra senkronize edilir, ayrıca saatlik periyodik senkronizasyon yapılır.
- 🔀 **Birleştirme**: Çalma listeleri, geçmiş, istatistikler ve silme kayıtları üç yönlü birleştirme (three-way merge) kullanır. Bu, gerçek zamanlı itme değil, arka planda iki yönlü senkronizasyondur.
- 📦 **Uzak biçim**: V4, ZSTD parçalarını artımlı olarak yükler.
- 🧷 **Yazma koruması**: Güçlü ETag koşullu yazmaları tercih edilir; Nutstore ve benzeri hizmetler bunun yerine bildirim parmak izlerini kontrol eder.
- 🔓 **Şifreleme**: Uzak dosyalar uçtan uca şifrelenmez.

---

## Yol Haritası

Planlar bakımcı kapasitesine, platform kullanılabilirliğine ve topluluk geri bildirimlerine göre değişir.

- [ ] Video oynatma
- [ ] Üçüncü taraf oynatma, kütüphane sayfaları ve hesap desteğinin sürekli genişletilmesi
- [ ] Daha eksiksiz QQ Music hesap desteği, kütüphane verileri ve kimlik doğrulama akışı

Yayınlanan çalışmalar [Releases](https://github.com/cwuom/NeriPlayer/releases) sayfasında ve commit geçmişinde takip edilir.

---

## Hata Bildirimi

- Önce geliştirici modunu etkinleştirin (Ayarlar'da **sürüm numarasına** 7 kez dokunun). Bu işlem düz dosya günlüğünü açar ve çökme günlükleri ayrı olarak kaydedilir.
- İşletim sistemi sürümü, cihaz modeli, uygulama sürümü, yeniden oluşturma adımları ve ilgili günlüklerle bir [issue](https://github.com/cwuom/NeriPlayer/issues) açın.
- Windows'ta günlükleri filtreleme: `adb logcat | findstr NeriPlayer`
- Linux / macOS'ta günlükleri filtreleme: `adb logcat | grep NeriPlayer`

---

## Bilinen Sorunlar

### Ağ

- Üçüncü taraf platformlar için bölünmüş tünelleme (split-tunneling) kuralları kullanın; genel bir proxy bazı API'lerin beklenmeyen veriler döndürmesine neden olabilir.

### İndirmeler

- İndirmeler sistem indirme servisi yerine uygulama içi yöneticiyi kullanır. Uygulamanın arka plan yaşam döngüsü sınırları dahilinde devam ettirme ve başlangıç kurtarmayı desteklerler.
- Manuel iptal, kontrol noktalarını ve kısmi dosyaları temizler; yalnızca ağ politikası duraklatmaları ve kurtarılabilir hatalar devam ettirme durumunu korur.
- Özel bir SAF dizinine dışarıdan erişmek daha kolaydır, ancak tarama, taşıma ve yazma işlemleri uygulamanın özel dizininden daha yavaştır.

### USB özel çıktısı

- Uyumlu bir DAC ve ön plan servisi gerektirir. Ekran kapalıyken oynatma durursa, Ayarlar'da istendiği gibi pil ve arka plan kısıtlamalarını kaldırın.
- UAC2 eşzamansız modu tek bir açık geri bildirim uç noktası ve desteklenen bir saat topolojisi gerektirir; örtülü geri bildirim ve doğrulanamayan topolojiler desteklenmez.
- Sistem ses çıktısını kullanmak için USB özel modunu kapatın.
- Telefon ve DAC uyumluluğu gerçek cihaz kontrolleri gerektirir.

### Ses çıktısı

- 32-bit yüksek çözünürlüklü çıktı; ses yüksekliği dengelemeyi, kanal dengesini, ses görselleştirmeyi ve uygulama içi oynatma hızını atlar. Bu özelliklere ihtiyacınız olduğunda kapatın.
- DLNA / UPnP yayını, telefonun ve oynatıcının aynı yerel ağda (LAN) olmasını gerektirir; yayın sırasında kod çözme uzak oynatıcıda gerçekleştiğinden yerel DSP efektleri ve oynatma hızı atlanır.

### Bluetooth araç

- AVRCP'de şarkı sözü alanı bulunmadığından araç şarkı sözleri parça başlığı alanı üzerinden gönderilir. Yenileme hızı ve kapak görseli telefon işletim sistemine ve araç ünitesine bağlıdır.
- Android Auto standart araç içi arayüzünü kullanır; bağımsız bir Android Automotive OS derlemesi sağlanmaz.

### Şarkı Sözleri

- Transliterasyon, platform yanıtında veya gömülü etiketlerde transliterasyon verisi gerektirir.
- Oluşturulan şarkı sözü kartları uygulama önbellek dizininde tutulur ve önbellek temizlenerek silinebilir.
- ColorOS kilit ekranı sözleri deneyseldir, üretici desteğinin yanı sıra Root / Shizuku gerektirir ve sistem güncellemelerinden sonra yeniden kontrol edilmelidir.

### Platform kapsamı

- Bilibili tam bir video keşif akışına değil, arama, favoriler, koleksiyonlar ve ses oynatmaya odaklanır.
- QQ Music yalnızca Şu An Çalınıyor meta verisi ve şarkı sözü tamamlama için kullanılır.

### Senkronizasyon ve dışa aktarma

- Uzak senkronizasyon dosyaları uçtan uca şifrelenmez.
- Yapılandırma dışa aktarmaları varsayılan olarak oturum açma durumunu hariç tutar. Kimlik doğrulama bilgilerini dahil ederseniz bir parola belirleyin ve güvende tutun; parola dışa aktarma dosyasında saklanmaz ve kurtarılamaz.
- Eşzamanlı güncellemeler aynı dalı çatalladığında bir çakışma bildirilir ve uzak commit'ler asla zorla üzerine yazılmaz.

### Diğer

- Uzun ses konum belleği hiçbir zaman sizin ayarladığınız başlangıç konumunu geçersiz kılmaz ve üçüncü taraf platform geçmişine geri yazılmaz.
- Dinleme geçmişi senkronizasyonu (varsayılan olarak kapalıdır) Ayarlar → Üçüncü Taraf Oturum Açma bölümünden platform başına açılır. Bir NetEase şarkısı 30 saniye sonra veya bittiğinde dinleme kayıtlarına ve sıralamalara sayılır; Bilibili oynatma ilerlemesini alır ve hesapta Bilibili geçmişi duraklatılmışken hiçbir şey gönderilmez. Başarısız bildirimler oynatmayı asla etkilemez ve yeniden denenmez.
- Bilibili SponsorBlock herkese açık bir API'yi sorgular ve Birlikte Dinle odalarında devre dışıdır.

---

## Gizlilik

- Herkese açık bulut medya dağıtım hizmeti yoktur ve reklam, üçüncü taraf analiz veya çökme bildirimi SDK'ları bulunmaz.
- Senkronizasyon hedefini siz seçer ve sahip olursunuz; kişisel medya verileri asla bakımcı tarafından kontrol edilen bir platformda toplanmaz.
- Önbellek, indirmeler, yerel çalma listeleri, geçmiş, oynatma istatistikleri, ayarlar ve kimlik doğrulama verileri cihazda saklanır.
- Çalma listeleri, favoriler, geçmiş ve oynatma istatistikleri gibi meta veriler yalnızca GitHub veya WebDAV senkronizasyonunu etkinleştirdiğinizde senkronize edilir.
- Ses önbelleği, indirilen dosyalar, çerezler ve oynatma belirteçleri asla geliştiriciye yüklenmez.
- Oynatma geçmişi ve istatistikler varsayılan olarak üçüncü taraf platformlara gönderilmez. Yalnızca Ayarlar → Üçüncü Taraf Oturum Açma bölümünden dinleme geçmişi senkronizasyonunu etkinleştirdikten sonra şarkı / video kimlikleri, dinleme süresi ve oynatma ilerlemesi hesabınız altında NetEase Cloud Music veya Bilibili'ye gönderilir.
- Yapılandırma dışa aktarmaları varsayılan olarak oturum açma durumunu hariç tutar; dahil edilmesi kimlik doğrulama ve senkronizasyon bilgilerini dışa aktarır, bu nedenle bir parola belirleyin ve gizli tutun.
- Android sistem bulut yedeklemesi ve cihaz aktarımı varsayılan olarak devre dışıdır.
- Üçüncü taraf platformlardaki erişim günlükleri ve risk kontrol politikaları o platformların kendi gizlilik politikaları kapsamında ele alınır.

---

## Referanslar

- [netease-cloud-music](https://github.com/chaunsin/netease-cloud-music): Go dilinde NetEase Cloud Music uygulaması
- [bilibili-API-collect](https://github.com/SocialSisterYi/bilibili-API-collect): Bilibili API koleksiyonu
- [ejs](https://github.com/yt-dlp/ejs): Birçok çalışma zamanını destekleyen yt-dlp için harici JavaScript
- [accompanist-lyrics-core](https://github.com/6xingyv/accompanist-lyrics-core): Kotlin için şarkı sözü ayrıştırma, dönüştürme ve dışa aktarma
- [accompanist-lyrics-ui](https://github.com/6xingyv/accompanist-lyrics-ui): Bir karaoke şarkı sözü composable bileşeni
- [LDDC](https://github.com/chenmozhijin/LDDC): Çok platformlu şarkı sözü eşleştirme aracı; manuel eşleştirme kullanıcı deneyimi için referans
- [Metrolist](https://github.com/MetrolistGroup/Metrolist): Android için YouTube Music istemcisi
- Şu An Çalınıyor ekranının orijinal akışkan arka plan gölgelendiricisi [HyperCeiler](https://github.com/ReChronoRain/HyperCeiler) içindeki gölgelendiriciyi temel almıştır.
- freeman-jiang tarafından geliştirilen [BeatSync](https://github.com/freeman-jiang/beatsync), LAN Birlikte Dinle için eşleştirilmiş prob saat senkronizasyonu algoritmasını ve Kotlin portunu test etmek için kullanılan referans uygulamayı sağlar.
- [AndroidX Media3](https://github.com/androidx/media), LAN oda üyeleri için aşamalı ses oynatma ve perde koruyucu hız ayarlamaları sağlar.
- [Java-WebSocket](https://github.com/TooTallNate/Java-WebSocket), oda kontrol mesajları için sunucunun WebSocket sunucusunu sağlar.
- [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd), oda üyeleriyle ses paylaşımı için sunucunun HTTP sunucusunu sağlar.
- [OkHttp](https://github.com/square/okhttp), üyelerin odaya bağlanmasını ve paylaşılan sesi akışla almasını sağlar.

---

## Katkıda Bulunma, Destek ve Lisans

- **Katkıda bulunma**: Önce [CONTRIBUTING_EN.md](./CONTRIBUTING_EN.md) dosyasını okuyun. Öncelikli olarak temel oynatma, yerel veriler, senkronizasyon ve kurtarma yolları sürdürülür ve kararlı sürümler özellik grupları halinde manuel olarak yayınlanır.
- **Destek**: Bağış kabul edilmez; issue'lar, pull request'ler ve paylaşılan kullanım geri bildirimleri memnuniyetle karşılanır.
- **Lisans**: NeriPlayer **GPL-3.0** altında yayınlanmıştır. Kullanabilir, değiştirebilir ve dağıtabilirsiniz; değiştirilmiş dağıtımlar GPL-3.0 altında kalmalıdır. Tam koşullar [LICENSE](./LICENSE) dosyasındadır.
- **Native kaynak lisansı**: [Native kaynak lisansı notu](./modules/native/src/main/cpp/README.md), listelediği NeriPlayer'a ait native kaynaklar için atıf koşullarıyla alternatif bir lisans tanır. Üçüncü taraf kaynaklar ve listelenmeyen içerikler hariçtir.
- **Harici native katkılar**: Bir pull request bu kapsama otomatik olarak girmez; yalnızca açık bir çift lisans beyanı girer.
- **Üçüncü taraf kodu**: Xiaomi Super Island şarkı sözü Shizuku / XMSF ağ yalıtım adaptörü GPL-3.0 Capsulyric kaynaklarını port eder; bkz. [THIRD_PARTY_LICENSES.md](./THIRD_PARTY_LICENSES.md).

---

<p align="center">
  <img src="https://moe-counter.lxchapu.com/:neriplayer?theme=moebooru" alt="MoE Counter">
</p>

## Yıldız Geçmişi

<a href="https://www.star-history.com/?repos=cwuom%2Fneriplayer&type=timeline&legend=bottom-right">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=cwuom/neriplayer&type=timeline&theme=dark&legend=bottom-right" />
    <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=cwuom/neriplayer&type=timeline&legend=bottom-right" />
    <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=cwuom/neriplayer&type=timeline&legend=bottom-right" />
  </picture>
</a>
