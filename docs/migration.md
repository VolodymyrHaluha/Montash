# Перенесення Mounter → Montash

Джерело: https://github.com/VolodymyrHaluha/Mounter

Гілка: master. Commit: `34ed4cc46948779c38a087bf0ca8744841a16f86`.

## Аналіз

Оригінал — Android/Kotlin/Jetpack Compose, мінімум Android 7 (API 24), горизонтальна орієнтація. Основні дані вже локальні; підключення до PostgreSQL згадувалося в серверних та інтеграційних матеріалах, а не в основному модулі. Головна зовнішня залежність — AttendanceGate: доступ до додатка блокувався до підтвердженої відмітки з іншого додатка. Стан карток читався через ContentResolver, оновлювався спостерігачем, а NFC та Intent передавали керування до вибраного додатка відміток.

## Зміни

- MainActivity запускає MontashApp без AttendanceGate та зовнішніх параметрів сесій.
- Видалені AttendanceGate, AttendanceAppSelection, AttendanceContract, обидва CardSessions, WorkHours і WorkHoursTimer та пов'язані тести. Таймер не замінений фіктивними даними.
- Не перенесені `attendance-integration/`, `server/`, стара документація інтеграцій, архів-дублікат `app/src.zip` та налаштування IDE джерела.
- Прибрані NFC permission/feature та queries сторонніх пакетів, провайдерів і списку встановлених додатків.
- Встановлені назва Montash, окремий applicationId та назва Gradle-проєкту. Тест packageName оновлений.
- Вимкнене автоматичне резервне копіювання, видалені невикористані приклади backup XML.
- Прибрані машинозалежні URL завантаження JDK із daemon-конфігурації; використовується JDK, налаштований у середовищі збірки / Android Studio.
- Збережені системні TakePicture, GetContent/GetMultipleContents, OpenMultipleDocuments і приватний FileProvider для фото. Зовнішніх бізнес-провайдерів немає.
- Збережені локальні моделі, формати зберігання, довідники, фото, PDF, графіка та редагування вводу.

## Обмеження

Монтажні дані Montash ізольовані від Mounter через новий applicationId. Автоматичної міграції даних, синхронізації та серверного резервування немає. Видалення Montash або очищення його даних видалить локальні записи й вкладення.

## Ручна перевірка на пристрої

1. Запустити Montash без встановленого додатка відміток: головний екран має відкритися одразу.
2. Додати працівника й замовника, створити об'єкт і виріб, записати характеристики та примітку.
3. Прикріпити фото з галереї та камери; відкрити, замінити й видалити фото.
4. Прикріпити PDF, переглянути сторінки, масштабування й поворот усередині Montash.
5. Перезапустити Montash і перевірити збереження записів і вкладень.
6. Повторити роботу в режимі польоту; сканування NFC не має запускати інший додаток.

## Виконані перевірки

- `testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug` — BUILD SUCCESSFUL (81 задачa). Успадкований JVM-тест пройшов; це лише арифметичний приклад, а не функціональна перевірка додатка.
- Android Lint: 0 помилок, 19 попереджень (оновлення залежностей/target API, ресурси, стиль коду, орієнтація екрана та правила перенесення даних).
- Тести інтерфейсу, включно з новим StandaloneLaunchTest, успішно скомпільовані у тестовий APK; на пристрої чи емуляторі не запускалися.
- Зведений Manifest і готовий APK: applicationId Montash, без INTERNET, NFC та queries бізнес-додатків; приватний FileProvider фото належить Montash.
- Підпис debug APK перевірено через apksigner.
- XML валідні; `git diff --check` без зауважень.

APK зібрано з debug-підписом для тестового встановлення. Для публікації потрібно зібрати release із власним ключем підпису.

## Самостійний пакет і конфігурація IDE

Namespace, applicationId, пакети Kotlin і тестів, а також шляхи вихідних файлів узгоджені з `com.volodymyrhaluha.montash`. Теми та кореневий компонент називаються MontashTheme/MontashApp; XML-стилі — Theme.Montash.

Прибрані застарілий Java-модуль IDE, його список модулів, машинозалежна прив’язка до JDK 25 і кеш пристроїв. `.idea/gradle.xml` підключає корінь і Android-модуль `app`. `.idea/runConfigurations/app.xml` додає стандартну Android App конфігурацію app для модуля Montash.app.main: Gradle-збірка, встановлення debug APK і запуск launcher activity на вибраному пристрої.

## Імпорт Android-модуля та звичайний запуск app

- `.idea/AndroidProjectSystem.xml` явно вибирає Android Gradle Project System.
- У `.idea/gradle.xml` підключені корінь і `app`; прибрана прив’язка до локального псевдоніма JDK. JDK 21 налаштовується в Android Studio.
- Конфігурація `app` зберігається у стандартній папці `.idea/runConfigurations`, посилається на Android main-модуль `Montash.app.main` і запускає Default Activity після Gradle-збірки. Стара конфігурація Montash видалена.
- Файли IML і список IDE-модулів вручну не створюються: їх формує Android Studio після успішного Gradle Sync. README містить відновлення імпорту для вже відкритого проєкту та діагностику порожнього Module.

Перевірка через Gradle Tooling API (Android-модель для IDE) успішна: `:app`, тип `APPLICATION`, namespace `com.volodymyrhaluha.montash`, 2 варіанти збірки. XML конфігурації app і Project System перевірені. Це підтверджує, що Gradle може надати Android-модуль IDE; імпорт у локальній Android Studio користувача потребує успішного Gradle Sync і сумісної версії IDE.
