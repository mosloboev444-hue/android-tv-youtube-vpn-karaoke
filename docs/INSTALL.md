# Установка

## Что нужно

- Android TV 8.0+ (проект проверен на Philips 50PUS8057/60, Android TV 14).
- Включённая отладка по сети (ADB) на телевизоре.
- Компьютер с Windows/Linux/macOS и `adb` из Android platform-tools.
- Установленный и настроенный Юбуст (`st.uboo.android.client`), вход в аккаунт.
- YouTube TV (`com.google.android.youtube.tv`) — на телевизоре уже есть.

## 1. Включить ADB на телевизоре

1. Настройки → Параметры устройства → О телевизоре → несколько раз нажать «Номер сборки».
2. Настройки → Параметры устройства → Для разработчиков → включить «Отладка по USB» и
   «Отладка по сети» (названия зависят от прошивки).
3. Узнать IP: Настройки → Сеть → статус подключения.

Проверка с компьютера:

```powershell
adb connect 192.168.1.50:5555
adb devices
```

На телевизоре появится запрос «Разрешить отладку» — подтвердить.

## 2. Установить YouTube VPN Gate

```powershell
powershell -ExecutionPolicy Bypass -File app\install.ps1 -TvHost 192.168.1.50
```

Скрипт:

- ставит `apk/YouTube-VPN-Gate-1.0.apk` (или собранный `app/YouTube-VPN-Gate.apk`);
- добавляет компонент
  `com.gathin.vpngate/com.gathin.vpngate.GateAccessibilityService` в
  `enabled_accessibility_services`, не трогая другие службы;
- включает `accessibility_enabled=1`;
- печатает состояние.

Вручную:

```powershell
adb -s 192.168.1.50:5555 install -r apk\YouTube-VPN-Gate-1.0.apk
adb -s 192.168.1.50:5555 shell "settings put secure enabled_accessibility_services \
  'com.gathin.vpngate/com.gathin.vpngate.GateAccessibilityService'"
adb -s 192.168.1.50:5555 shell "settings put secure accessibility_enabled 1"
```

Если на телевизоре уже включены другие службы доступности, перечислите их через `:` и добавьте нашу
в конец.

## 3. Настроить Юбуст

1. Открыть Юбуст, войти в аккаунт.
2. Выбрать режим **«Только YouTube»**.
3. Один раз нажать «Подключить» и убедиться, что YouTube грузится.
4. Нажать «Отключить».

Режим «Только YouTube» запоминается приложением: остальные приложения (IPTV, TiviMate, Кинопоиск)
ходят напрямую.

## 4. Проверить

1. Выйти на главный экран.
2. Открыть YouTube.
3. В течение 1–3 секунд должен открыться Юбуст, нажаться «Подключить», и YouTube вернётся уже с
   рабочей сетью.

Логи:

```powershell
adb -s 192.168.1.50:5555 logcat -s VpnGate
```

Успешный сценарий:

```
I VpnGate : YouTube in front, no VPN: opening st.uboo.android.client
I VpnGate : startActivity(Юбуст) ok
I VpnGate : pressed connect (attempt 1)
I VpnGate : VPN is up
I VpnGate : YouTube opened
```

## 5. Кнопка «Караоке!!!» (опционально)

Плитка находится в форке лаунчера CleanYourTV:

```powershell
adb -s 192.168.1.50:5555 install -r apk\CleanYourTV-Karaoke-3.2.apk
```

Если лаунчер уже стоял с другой подписью, установка завершится
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Варианты:

- пересобрать лаунчер своим ключом (см. [`../launcher/README.md`](../launcher/README.md));
- удалить старую версию (`adb uninstall com.gathin.tvhome`) и поставить эту, затем назначить
  «Домой»;
- пропустить плитку — автоматика YouTube→VPN работает и без неё.

## 6. После перезагрузки

Служба доступности включена в настройках, Android сам поднимает её после загрузки. VPN после
перезагрузки выключен (always-on на этом телевизоре не работает), поэтому первый запуск YouTube
после включения телевизора занимает на 2–4 секунды больше.

## Удаление

```powershell
powershell -ExecutionPolicy Bypass -File app\uninstall.ps1 -TvHost 192.168.1.50
```

Скрипт выключает службу, удаляет пакет и чистит настройки always-on VPN.

## Безопасность

- После настройки выключите отладку по сети/USB на телевизоре.
- Служба доступности видит содержимое окон; не устанавливайте APK из непроверенных источников.
- APK подписаны debug-ключом, это sideload-сборка, не для магазинов.
