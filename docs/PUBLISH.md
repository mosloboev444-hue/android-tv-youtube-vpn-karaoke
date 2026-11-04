# Как выложить проект на GitHub

В этом каталоге уже лежит готовая структура репозитория. Достаточно создать пустой репозиторий и
запушить содержимое.

## Вариант 1: через сайт GitHub

1. Создайте репозиторий: <https://github.com/new>.
   - Имя: `android-tv-youtube-vpn-karaoke` (или своё).
   - Public.
   - **Не** добавляйте README/LICENSE/.gitignore — они уже есть.
2. Распакуйте `android-tv-youtube-vpn-gate.zip`.
3. Загрузите файлы через «Add file → Upload files» (или через GitHub Desktop).

## Вариант 2: через git

```powershell
# один раз: установить Git for Windows и представиться
git config --global user.name "Your Name"
git config --global user.email "you@example.com"

cd android-tv-youtube-vpn-gate
git init
git add .
git commit -m "YouTube VPN Gate: auto VPN for YouTube TV + Karaoke fire tile"
git branch -M main
git remote add origin https://github.com/<account>/android-tv-youtube-vpn-karaoke.git
git push -u origin main
```

Если Git ещё не установлен: <https://git-scm.com/download/win>.

## Описание репозитория

Короткое:

> Android TV: автоматически включает VPN (Юбуст) при запуске YouTube и возвращает YouTube на
> экран. Плюс кнопка «Караоке!!!» с анимированным огнём.

Topics:

```
android-tv  android  youtube  vpn  accessibility-service  karaoke  uboo  philips  java
```

## Релиз

Соберите APK и приложите к релизу:

```powershell
git tag v1.0
git push origin v1.0
```

На GitHub: Releases → Draft a new release → tag `v1.0` → прикрепите:

- `apk/YouTube-VPN-Gate-1.0.apk`
- `apk/CleanYourTV-Karaoke-3.2.apk`
- `video/karaoke_tiktok.mp4` (по желанию)

В описании релиза укажите:

- что APK подписаны debug-ключом (sideload);
- что лаунчер — форк CleanYourTV и может конфликтовать с уже установленной версией;
- ссылку на исходники и лицензию.

## Перед публикацией проверьте

- [ ] нет каталога `.cytv` (SDK, бэкапы, `getprop`, MAC/IP);
- [ ] нет файлов с личным e-mail/аккаунтом (в видео почта заблюрена);
- [ ] нет keystore и `*.idsig`;
- [ ] нет сырых записей экрана (`tiktok/raw`), там виден e-mail;
- [ ] в README нет вашего домашнего IP и имени пользователя;
- [ ] лицензия MIT и указание на CleanYourTV на месте.

## Если репозиторий уже существует

```powershell
git remote add origin <url>
git push -u origin main
```

Если история уже была — не делайте `git init` заново, просто скопируйте файлы в клон и закоммитьте.
