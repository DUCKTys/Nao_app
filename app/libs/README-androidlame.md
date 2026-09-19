# Cara pasang androidlame.jar + libandroidlame.so

Karena AAR `com.github.NorthernCaptain:TAndroidLame:1.1` bawa resource lama
yang bentrok (lihat komentar di `app/build.gradle.kts`), kita pakai isinya
langsung tanpa lewat mekanisme AAR/resource merge.

Jalankan ini di Termux, di root proyek (sudah pernah build sekali supaya AAR
ini ke-download ke gradle cache):

```sh
# 1. Cari lokasi AAR yang sudah di-download & di-transform gradle
find ~/.gradle/caches -iname "*androidlame*" -o -iname "*TAndroidLame*" 2>/dev/null
```

Akan muncul beberapa path. Cari folder hasil "transformed" (mirip pola
`material-1.13.0` di error log sebelumnya), biasanya:

```
~/.gradle/caches/9.6.1/transforms/<hash>/transformed/TAndroidLame-1.1/
```

Di dalam folder itu ada:
- `jars/classes.jar` (atau langsung `classes.jar` di root folder)
- `jni/<abi>/libandroidlame.so` (abi: armeabi-v7a, arm64-v8a, x86, x86_64)
  -- atau kadang di folder `libs/<abi>/` alih-alih `jni/<abi>/`, tergantung
  cara AAR lama ini dikemas. Cek dengan `find <folder_transformed> -name "*.so"`.

```sh
# 2. Salin classes.jar ke sini (folder app/libs/)
cp ~/.gradle/caches/9.6.1/transforms/<hash>/transformed/TAndroidLame-1.1/jars/classes.jar \
   app/libs/androidlame.jar

# 3. Salin tiap .so ke app/src/main/jniLibs/<abi>/
mkdir -p app/src/main/jniLibs/armeabi-v7a app/src/main/jniLibs/arm64-v8a \
         app/src/main/jniLibs/x86 app/src/main/jniLibs/x86_64

find ~/.gradle/caches/9.6.1/transforms/<hash>/transformed/TAndroidLame-1.1 -name "*.so"
# lalu cp masing-masing .so yang ketemu ke folder jniLibs/<abi> yang sesuai namanya
```

Kalau proyek cuma perlu jalan di HP kamu sendiri, boleh cuma salin `.so`
untuk ABI HP kamu saja (cek dengan `getprop ro.product.cpu.abi` di Termux) --
tapi kalau mau APK-nya jalan di HP lain juga, salin semua 4 ABI di atas.

Setelah `androidlame.jar` ada di `app/libs/` dan minimal satu `.so` ada di
`app/src/main/jniLibs/<abi>/`, langsung `./gradlew assembleDebug` lagi --
tidak perlu ubah apa pun lagi di build.gradle.kts, itu sudah otomatis
mengambil dari folder ini.

Kalau nanti AAR-nya ternyata TIDAK menyertakan `.so` sama sekali (murni
Java tanpa native lib bawaan, jarang tapi mungkin untuk versi tertentu),
kabari saya isi hasil `find ... -name "*.so"` di atas (kosong atau
ada) supaya saya bisa sesuaikan lagi.
