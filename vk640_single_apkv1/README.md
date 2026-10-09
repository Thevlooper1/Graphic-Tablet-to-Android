# VK640 tek APK (Shizuku + imlec + surucu)

Termux, rish ve PC betigi gerekmez. Surucu (vk640_uhid) APK'nin icinde gelir,
Shizuku uzerinden shell yetkisiyle baslar, imlec ayni uygulamadadir.

> UYARI: Bu proje yazildigi ortamda DERLENMEDI ve tablette DENENMEDI.
> Ilk derlemede kucuk hata cikabilir. Hata metnini gonderirsen duzeltilir.

## 1) APK'yi derle (bilgisayara bir sey kurmadan)

1. github.com'da bos bir depo ac (Private olabilir).
2. Bu klasorun TUM icerigini (gizli `.github` klasoru dahil) depoya yukle.
3. Depoda **Actions** sekmesi > **APK derle** > **Run workflow**.
4. Bitince calismanin sayfasinda **vk640-apk** (artifact) indir, zip'ten `app-release.apk` cikar.

Android Studio kullanirsan: klasoru ac, NDK 26.1.10909125 ve SDK 34'u kur, Build > Build APK.

## 2) Kurulum

Eski uygulama farkli anahtarla imzali oldugu icin once kaldir:

    adb uninstall com.exalted.vk640cursor

(ya da tabletten Ayarlar > Uygulamalar > VK640 Imlec > Kaldir). Sonra yeni APK'yi kur.

## 3) Her acilista

1. Wi-Fi'a baglan, Gelistirici secenekleri > Kablosuz hata ayiklama'yi ac.
2. Shizuku'yu baslat (kablosuz hata ayiklama ile).
3. VK640 uygulamasini ac. Ilk seferde:
   - Shizuku izin penceresinde izin ver,
   - "Ekran ustu cizim izni ver" ile izni ac.
4. Durum satirlarinda `Surucu: dongu: var, surucu: calisiyor` gorunmeli.

Sonrasi: uygulama acilinca kendiliginden baslar. Tablet yeniden basladiginda imlec
servisi acilir, Shizuku'yu baslatinca surucu kendiliginden devam eder (uygulamayi
acmak gerekmeyebilir; olmazsa bir kez ac).

## Ayarlar

- **Ekran donusu**: dokununca 180 > 0 > 90 > 270 > auto doner. Senin tablette dikeyde 180 gerekiyordu.
- **Imlec boyutu**: kaydirici.
- **Durdur**: imleci ve surucuyu kapatir; uygulama bir sonraki acilista kendiliginden baslamaz (BASLAT'a bas).

## Sorun giderme

- `Surucu: HATA: surucu kopyalanamadi`: hata metnindeki yolu gonder.
- `dongu: var, surucu: YOK`: tablet takili mi? `Log:` satirindaki metni gonder.
- Shizuku izin penceresi cikmiyorsa Shizuku > Yetkili uygulamalar'dan VK640'a elle izin ver.
