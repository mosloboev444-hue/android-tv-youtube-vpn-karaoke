# Плитка «Караоке!!!» с огнём для CleanYourTV

Опциональная часть проекта: плитка приложения `com.gathin.vpngate` в доке лаунчера рисуется
анимированным огнём (языки пламени, угли, горящий текст «КАРАОКЕ!!!»). Нажатие открывает
`KaraokeActivity`, которая запускает сценарий «VPN → YouTube-караоке».

Патч сделан для [CleanYourTV](https://github.com/Gathin23/cleanyourtv) версии 3.2
(`versionCode 11`, лаунчер `com.gathin.tvhome`). На более новых версиях контекст может не совпасть —
правьте вручную по хункам.

## Что добавить

1. `FireBannerDrawable.java` — новый класс, анимированный баннер.
2. `patches/AppAdapter.patch` — выбор огня для пакета `fire_app`, запуск/остановка анимации.
3. `patches/MainActivity.patch` — `setFireRunning(false/true)` в `onPause`/`onResume`.
4. `patches/config.patch` — пример `dock_apps` и ключ `fire_app`.

## Применение

```bash
git clone https://github.com/Gathin23/cleanyourtv
cd cleanyourtv

cp /path/to/FireBannerDrawable.java \
   launcher/app/src/main/java/com/gathin/tvhome/

git apply -p1 /path/to/patches/AppAdapter.patch
git apply -p1 /path/to/patches/MainActivity.patch
git apply -p1 /path/to/patches/config.patch
```

Если `git apply` ругается на контекст — примените хунки вручную: изменения небольшие.

## Настройка config.xml

В `launcher/app/src/main/res/values/config.xml`:

```xml
<!-- Пакет VPN-клиента: красный бейдж «VPN отключён» и переход по клику -->
<string name="vpn_app" translatable="false">st.uboo.android.client</string>

<!-- Пакет, чья плитка рисуется огнём -->
<string name="fire_app" translatable="false">com.gathin.vpngate</string>

<!-- Плитка в доке; можно поставить в любое место -->
<string-array name="dock_apps">
    <item>com.google.android.youtube.tv</item>
    <item>com.gathin.vpngate</item>
    <item>ar.tvplayer.tv</item>
    <item>ru.iptvremote.android.iptv</item>
</string-array>
```

`fire_app` можно оставить пустым — тогда огня не будет, а плитка покажет обычный баннер.
`vpn_app` не обязателен для работы автоматики: он только рисует индикатор VPN в шапке лаунчера.

## Сборка

Дальше — как в upstream:

```bash
cd launcher
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Лаунчер подписывается debug-ключом из `~/.android/debug.keystore`. Если на телевизоре уже стоит
сборка с другим сертификатом, обновление не установится: пересоберите своим ключом или удалите
старую версию (потеряется роль «Домой»).

## Как работает анимация

`FireBannerDrawable` — это `Drawable`, реализующий `Animatable`. Он рисует три слоя языков пламени
(каждый — сумма треугольных «языков» с синусоидальным дыханием), угли и текст. `ValueAnimator`
обновляет кадр примерно 30 раз в секунду; `setVisible` от `ImageView` запускает и останавливает
анимацию, а `MainActivity.onPause/onResume` дополнительно гасит её, когда Home не на экране.

## Ограничения

- Патч завязан на внутренности CleanYourTV 3.2; в других лаунчерах код не применится.
- Анимация на 2 ГБ ТВ стоит ~1–2% CPU; если хочется тише, увеличьте `FRAME_MS`/интервал в
  `ValueAnimator`.
- Никаких сетевых разрешений лаунчеру не добавляется.
