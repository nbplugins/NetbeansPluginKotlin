# C3 Resume Prompt

## Ветка
`feature/c3-analysis-api-session` (уже запушена на `origin`)

## Цель задачи C3

Интегрировать K2 Analysis API (`buildStandaloneAnalysisAPISession`) в NetBeans-плагин Kotlin.
K2-сессия должна сосуществовать с K1 (`KotlinEnvironment`) в одной JVM.

## Что уже сделано (коммит ba0dc146)

1. **`FakeIntellijHome.kt`** — создаёт минимальный fake IntelliJ home в `/tmp` (нужен `PathManager.getHomePath()`).
   Файл: `Nbm/src/main/java/io/github/nbplugins/kotlin/nbm/startup/FakeIntellijHome.kt`

2. **`KotlinInstaller.kt`** — в `restored()` добавлены вызовы:
   ```kotlin
   FakeIntellijHome.StartingUp().run()
   KotlinAnalysisAPISession.initApplicationEnvironment()
   ```

3. **`KotlinTestCase.kt`** — в `setUp()` добавлены:
   ```kotlin
   FakeIntellijHome.startUp()
   KotlinAnalysisAPISession.initApplicationEnvironment()
   ```

4. **`KotlinAnalysisAPISession.kt`** — добавлен метод `initApplicationEnvironment()`,
   который создаёт K2 app env первым (до K1), чтобы K1's `getOrCreate` нашёл уже
   существующее окружение и пропустил `loadForCoreEnv` (где происходит ClassNotFoundException).

5. **`KotlinEnvironment.kt`** — три вызова `registerService` обёрнуты в `try-catch`,
   чтобы не падать если K2 уже зарегистрировал сервисы.

6. **`pom.xml`** — `analysis-api-*-for-ide` с `2.0.21` → `2.1.20`.
   Версия `2.1.20` реализует `DataLoader.load(String, boolean)` (интерфейс из `core-impl:242`),
   что устранило `AbstractMethodError`.

7. **`FakeIntellijHomeTest.kt`** и **`KotlinAnalysisAPISessionTest.kt`** — тесты добавлены.

## Текущая проблема

При запуске тестов (`mvn clean test -pl Nbm -Dtest=KotlinAnalysisAPISessionTest`):

```
RuntimeException: Cannot resolve /META-INF/analysis-api/analysis-api-fir.xml
```

Реальная причина (из глубины стека):
```
NoClassDefFoundError: kotlinx/serialization/KSerializer
ExceptionInInitializerError at com.intellij.util.xml.dom.XmlElement.<clinit>
```

`analysis-api-*-for-ide:2.1.20` зависит от `kotlinx-serialization-core`, которого нет
в classpath проекта. Нужно добавить его как зависимость в `Nbm/pom.xml`.

## Следующий шаг

1. Определить нужную версию `kotlinx-serialization-core`:
   - Проверить в JAR `analysis-api-standalone-for-ide-2.1.20.jar` что за версия ожидается,
     или взять из [Maven Central](https://central.sonatype.com/artifact/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm)
     версию, совместимую с Kotlin 2.1.20 (обычно `1.7.x` или `1.8.x`).
   - Версию `2.0.21` runtime compiler мы не меняем.

2. Добавить в `pom.xml` (dependencyManagement) и `Nbm/pom.xml` (dependencies):
   ```xml
   <dependency>
     <groupId>org.jetbrains.kotlinx</groupId>
     <artifactId>kotlinx-serialization-core-jvm</artifactId>
     <version>???.??</version>
   </dependency>
   ```

3. Запустить `mvn clean test -pl Nbm -Dtest=KotlinAnalysisAPISessionTest` и исправить
   следующую ошибку, пока все тесты не пройдут.

4. Запустить `mvn clean test` (все тесты).

5. Запустить `mvn clean package -DskipTests`.

6. Предложить пользователю ручное тестирование.

## Принципы проекта (важно соблюдать)

- При конфликте версий классов — использовать **более новую** версию.
- Все версии зависимостей — в `<dependencyManagement>` корневого `pom.xml`.
- Новые классы плагина — в пакете `io.github.nbplugins.kotlin.nbm.*`.
- Каждый публичный класс — с KDoc.
- Каждый новый класс — с тест-классом.
- **Не принимать архитектурных решений без обсуждения с пользователем.**

## Maven-команды

```bash
# Тесты (только K2)
JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk mvn clean test -pl Nbm -Dtest=KotlinAnalysisAPISessionTest

# Все тесты
JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk mvn clean test

# Сборка
JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk mvn clean package -DskipTests
```

Зависимости из JetBrains repo качаются через SOCKS5 прокси:
```bash
curl -x socks5h://localhost:11337 -sSL <URL> -o <file>
```
