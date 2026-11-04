# Сборка

## YouTube VPN Gate

### Что нужно

- JDK 17+ (`JAVA_HOME`).
- Android SDK: platform 34, build-tools 34.0.0 (`ANDROID_HOME` или `ANDROID_SDK_ROOT`).
- Debug-keystore: `%USERPROFILE%\.android\debug.keystore` или переменная `DEBUG_KEYSTORE`.
  Если файла нет, `build.ps1` создаст его через `keytool`.

### Сборка

```powershell
$env:JAVA_HOME   = "C:\Program Files\Java\jdk-21"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

powershell -ExecutionPolicy Bypass -File app\build.ps1
```

Результат: `app\YouTube-VPN-Gate.apk`.

Скрипт делает всё без Gradle:

1. `aapt2 compile` + `aapt2 link` (манифест, ресурсы, R.java);
2. `javac --release 8 -encoding UTF-8`;
3. `d8` → `classes.dex`;
4. упаковка dex в APK;
5. `zipalign`;
6. `apksigner sign` debug-ключом;
7. `apksigner verify`.

### Ручная сборка

Если хочется без PowerShell:

```bash
SDK=$ANDROID_HOME
BT=$SDK/build-tools/34.0.0
AJ=$SDK/platforms/android-34/android.jar

$BT/aapt2 compile --dir app/res -o build/res.zip
$BT/aapt2 link -o build/base.apk -I $AJ \
  --manifest app/AndroidManifest.xml -R build/res.zip --auto-add-overlay \
  --java build/gen --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 1 --version-name 1.0

javac --release 8 -encoding UTF-8 -parameters -classpath $AJ \
  -d build/classes $(find app/src build/gen -name '*.java')
$BT/d8 --lib $AJ --min-api 26 --output build/dex $(find build/classes -name '*.class')

# classes.dex -> build/base.apk (zip), затем:
$BT/zipalign -f 4 build/base.apk build/aligned.apk
$BT/apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
  --key-pass pass:android --ks-key-alias androiddebugkey \
  --out app/YouTube-VPN-Gate.apk build/aligned.apk
```

## Лаунчер с огнём

Лаунчер — это форк [CleanYourTV](https://github.com/Gathin23/cleanyourtv). Патч и инструкция:
[`../launcher/README.md`](../launcher/README.md).

Коротко:

```bash
git clone https://github.com/Gathin23/cleanyourtv
cd cleanyourtv
cp /path/FireBannerDrawable.java launcher/app/src/main/java/com/gathin/tvhome/
git apply -p1 /path/patches/AppAdapter.patch
git apply -p1 /path/patches/MainActivity.patch
git apply -p1 /path/patches/config.patch
cd launcher
./gradlew assembleRelease
```

APK: `launcher/app/build/outputs/apk/release/app-release.apk`.

## Подпись

- В репозитории лежат APK, подписанные debug-ключом. Для публикации в магазине/своей раздачи
  используйте собственный keystore.
- Лаунчер нельзя обновить поверх сборки с другим сертификатом: Android откажет
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

## Версии

- `minSdk 26` (Android 8.0), `targetSdk 34`, `compileSdk 34`.
- Проверено на Android TV 14 (Philips 50PUS8057/60).
