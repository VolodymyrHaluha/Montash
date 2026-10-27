# Montash

Автономна копія Android-додатка [Mounter](https://github.com/VolodymyrHaluha/Mounter), без бізнес-інтеграцій і баз даних.

Збережено головний екран, об'єкти, замовників, бригаду, вироби, характеристики, примітки, фото та вбудований перегляд PDF. Інтерфейс, фон і ресурси взято з Mounter. Після запуску одразу відкривається головний екран.

Дані зберігаються лише в приватному сховищі Montash: об'єкти й вироби — у `objects.json`, довідники й бригада — у SharedPreferences, фото й PDF — у внутрішніх папках. Room, SQLite, PostgreSQL, сервери й API не використовуються. Немає дозволу INTERNET та NFC. Автоматичне резервне копіювання вимкнене.

За вимогою користувача залишено системну камеру, галерею та вибір PDF. Вони використовують стандартні механізми Android; це єдина взаємодія з іншими додатками. PDF переглядаються всередині Montash через Android PdfRenderer.

Видалено перевірку відмітки перед входом, вибір додатка відміток, запити до зовнішнього ContentProvider, його спостерігачі, NFC-передавання, callback повернення, зовнішні робочі сесії та їх таймери, серверні й інтеграційні заготовки.

## Запуск в Android Studio

1. Відкрити кореневу папку **Montash**, де лежить `settings.gradle.kts`, і дочекатися Gradle Sync. Проєкт має відкритися як Android/Gradle, модуль — `app`.
2. У **Settings → Build, Execution, Deployment → Build Tools → Gradle** вибрати встановлений JDK 21 як Gradle JDK, якщо IDE попросить налаштувати Java. Шлях до JDK не зберігається в репозиторії.
3. У списку конфігурацій запуску вибрати **app**: стандартна Android App конфігурація включена у `.idea/runConfigurations/app.xml` і запускає головну Activity модуля `app`.
4. Вибрати підключений пристрій з увімкненим USB debugging або емулятор (Android 7/API 24 чи новіший) і натиснути **Run ▶**. Android Studio збере debug APK, встановить його та запустить головний екран.

Якщо проєкт уже був відкритий до цих змін: закрити його через **File → Close Project**, повторно відкрити кореневу папку Montash, виконати **File → Sync Project with Gradle Files** і вибрати **app** замість старої конфігурації Montash.

Якщо список **Module** залишається порожнім, спочатку перевірити **Build → Sync**: модулі створює успішний Gradle Sync, а файл конфігурації запуску не може замінити імпорт. Для AGP 9.3 потрібна Android Studio, що підтримує цю версію плагіна; старіша IDE може відхилити синхронізацію. Помилки версії IDE/AGP, Gradle JDK або відсутнього Android SDK потрібно усунути за повідомленням Sync. Шлях SDK задається локально через SDK Manager / `local.properties` і не включається у Git.

Для запуску тестів інтерфейсу на цьому ж пристрої використати `./gradlew connectedDebugAndroidTest` або запустити тест із `app/src/androidTest` в IDE.

## Збірка

Відкрити корінь у Android Studio. Потрібні JDK 21, Android SDK Platform 37.1 та Gradle 9.5.0 (wrapper включено). Версії Android Gradle Plugin і бібліотек збережені з Mounter. Для завантаження інструментів і залежностей потрібен інтернет; сам додаток працює офлайн.

```sh
./gradlew testDebugUnitTest assembleDebug
./gradlew connectedDebugAndroidTest
```

Друга команда потребує підключеного Android-пристрою або емулятора. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

Назва: **Montash**. Ідентифікатор: `com.frop.montash`. Можна встановлювати поруч із Mounter; дані з Mounter автоматично не імпортуються. Namespace, пакети основного коду й тестів — `com.frop.montash`.

Вихідна версія та звіт: [docs/migration.md](docs/migration.md).
