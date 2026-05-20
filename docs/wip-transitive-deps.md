# WIP: Переход на транзитивные зависимости (ветка `refactor/transitive-deps`)

## Цель

Убрать паттерн `<exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion>` из `Nbm/pom.xml` —
он блокирует все транзитивные зависимости, что приводит к хрупкости: каждая новая нужная библиотека
должна быть прописана вручную. Вместо этого использовать транзитивные зависимости там, где это возможно,
и целевые эксклюзии (только конкретные конфликты) — там, где нет.

## Что уже сделано

### Bump версии
- `0.8.6-SNAPSHOT` → `0.8.7-SNAPSHOT` во всех модулях.

### Удалены FE10 зависимости
Из `Nbm/pom.xml` полностью удалены (плагин K2-only с C10, классы не используются в `src/main/java`):
- `base-fe10-analysis:231-1.9.20-506-IJ8109.175`
- `base-fe10-code-insight:231-1.9.20-506-IJ8109.175`
- `base-fe10-obsolete-compat:231-1.9.20-506-IJ8109.175`
- `base-psi:231-1.9.20-506-IJ8109.175`
- `analysis-api-fe10-for-ide:2.3.21`

Из `pom.xml` (root `dependencyManagement`) удалены те же артефакты.

Удалён репозиторий `jetbrains-kotlin-ki` (`packages.jetbrains.team/maven/p/ki/maven/`) — он был нужен только для тонких `base-fe10-*` JAR-ов.

### Убраны `*:*` эксклюзии с `-for-ide` артефактов
В `Nbm/pom.xml` убраны `<exclusion>*:*</exclusion>` со следующих зависимостей:
- `analysis-api-for-ide`
- `analysis-api-standalone-for-ide`
- `analysis-api-k2-for-ide`
- `analysis-api-impl-base-for-ide`
- `analysis-api-platform-interface-for-ide`
- `low-level-api-fir-for-ide`
- `symbol-light-classes-for-ide`
- `kotlin-compiler-common-for-ide`
- `kotlin-compiler-ir-for-ide`
- `formatter`

## Репозитории и доступность артефактов

### Где живут `-for-ide` артефакты

Артефакты `*-for-ide:2.3.21` опубликованы на `packages.jetbrains.team/maven/p/ij/intellij-dependencies`
(он же `jetbrains-intellij-dependencies` в `pom.xml`).
На CI они скачиваются напрямую; локально — через curl+SOCKS5 proxy.

```
Downloaded from jetbrains-intellij-dependencies:
  https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/org/jetbrains/kotlin/
    analysis-api-for-ide/2.3.21/...
    analysis-api-standalone-for-ide/2.3.21/...
    analysis-api-k2-for-ide/2.3.21/...
    analysis-api-impl-base-for-ide/2.3.21/...
    analysis-api-platform-interface-for-ide/2.3.21/...
```

### Поиск нон-for-ide артефактов

Проверены все публичные и JetBrains-доступные репозитории:

| Репозиторий | Результат |
|-------------|-----------|
| `repo.maven.apache.org/maven2` (Central) | 404 |
| `packages.jetbrains.team/maven/p/ij/intellij-dependencies` | только `-for-ide` варианты |
| `packages.jetbrains.team/maven/p/kt/kotlin-ide` | IDE-модули (`base-*`), не компилятор |
| `maven.pkg.jetbrains.space/kotlin/p/kotlin/kotlin-ide` (→ `p/kt/kotlin-ide`) | 404 |
| `packages.jetbrains.team/maven/p/kc/kotlin-compiler*` | 404 |
| `packages.jetbrains.team/maven/p/kotlin/*` | 404 |
| `oss.sonatype.org`, `s01.oss.sonatype.org` | 404 |
| `download-cdn.jetbrains.com/kotlin/bootstrap` | 404 |
| `dl.google.com/dl/android/maven2` | 404 |

**Вывод**: артефакты `org.jetbrains.kotlin:{analysis-api,low-level-api-fir,psi-api,parser,...}:2.3.21`
и другие нон-for-ide компиляторные модули **нигде не опубликованы публично** — ни на текущей версии
`2.3.21`, ни на более высоких (`2.4.0`, `2.4.0-RC`, `2.4.0-Beta1`).
Они являются внутренними JetBrains-артефактами, встроенными в `kotlin-compiler-embeddable`
и в `-for-ide` fat JAR-ы.

## Текущая проблема (не решена)

При сборке Maven требует транзитивные нон-for-ide артефакты, которые эти `-for-ide` POM-ы объявляют
как прямые зависимости:

```
org.jetbrains.kotlin:analysis-api:2.3.21
org.jetbrains.kotlin:analysis-api-standalone-base:2.3.21
org.jetbrains.kotlin:analysis-api-fir-standalone-base:2.3.21
org.jetbrains.kotlin:analysis-api-standalone:2.3.21
org.jetbrains.kotlin:analysis-api-fir:2.3.21
org.jetbrains.kotlin:analysis-api-impl-base:2.3.21
org.jetbrains.kotlin:analysis-internal-utils:2.3.21
org.jetbrains.kotlin:analysis-api-platform-interface:2.3.21
org.jetbrains.kotlin:low-level-api-fir:2.3.21
org.jetbrains.kotlin:symbol-light-classes:2.3.21
```

Также из `formatter` POM-а:
```
org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.20-506  (runtime)
org.jetbrains.kotlin:base-frontend-agnostic:231-1.9.20-506-IJ8109.175  (runtime)
```

Эти нон-for-ide артефакты **не опубликованы публично** — они JetBrains-internal.
Проверены репозитории: Maven Central, jetbrains-intellij-releases, kotlin-ide Space,
packages.jetbrains.team/maven/p/ki/maven, packages.jetbrains.team/maven/p/kotlin/kotlin-ide-plugin-dependencies —
везде 404.

### Предположение пользователя
Артефакты должны быть доступны для скачивания через curl. Нужно посмотреть логи CI-сборки
https://github.com/nbplugins/NetbeansPluginKotlin/actions/runs/26172414487 — в них видно
откуда именно Maven скачивал эти артефакты на CI.

## Вывод и скорректированная стратегия

### Ключевое открытие: `-for-ide` POM-ы сами уже содержат `*:*` exclusions

Анализ `kotlin-compiler-common-for-ide` и `kotlin-compiler-ir-for-ide`:
- каждый из них объявляет 40+ транзитивных нон-for-ide модулей (`psi-api`, `parser`, `ir.tree`, ...)
- **но каждая зависимость в POM уже помечена `excl=[*]`** — т.е. JetBrains сам исключил
  транзитивные deps нон-for-ide модулей
- Maven при этом всё равно пытается скачать JAR-файл нон-for-ide модуля (без POM не нужен,
  но сам JAR требуется как compile-артефакт)
- эти JAR-ы нигде не публикуются → сборка падает

Итог: **снятие `*:*`-эксклюзий с `-for-ide` в `Nbm/pom.xml` не даёт никаких полезных транзитивных
зависимостей** — только заставляет Maven искать непубличные внутренние JAR-ы.

В отличие от `kotlin-compiler:2.3.21` (полный публичный артефакт на Maven Central), в нашем
стеке используется `kotlin-compiler-ir-for-ide:2.3.21` (stripped fat JAR без публичных
гранулярных зависимостей), поэтому «транзитивные зависимости компилятора» по пути
`kotlin-compiler → stdlib/coroutines/etc` не применимы.

### Скорректированная стратегия

**Класс A — `-for-ide` артефакты (fat JAR-ы):**
- `kotlin-compiler-common-for-ide`, `kotlin-compiler-ir-for-ide`
- все `analysis-api-*-for-ide`, `low-level-api-fir-for-ide`, `symbol-light-classes-for-ide`
- **`*:*`-эксклюзии необходимы** и должны остаться в `Nbm/pom.xml`.
  Классы встроены, нон-for-ide JAR-ы не публикуются.
- Внешних (Maven Central) зависимостей у `-for-ide` POM-ов нет: все задекларированные
  зависимости — внутренние `org.jetbrains.kotlin`-модули с `excl=[*]`.
  Единственное исключение — `protobuf-relocated:2.6.1-2` в `kotlin-compiler-common-for-ide`,
  но он тоже нигде публично не опубликован. Снятие `*:*` с Класса A не принесёт ни одной
  транзитивной зависимости — только сломает сборку.

**Класс B — `formatter` (fat JAR с опубликованными зависимостями):**
- его POM объявляет `kotlin-stdlib-jdk8:1.9.20-506` и `base-frontend-agnostic:231-*`
- оба доступны на `jetbrains-intellij-dependencies` (CI лог подтверждает скачивание)
- **убрать `*:*`-эксклюзии с `formatter`** → deps придут транзитивно

**Класс C — зависимости в `Nbm/pom.xml`, которые можно убрать как явные:**
Если они уже приходят транзитивно через другие артефакты (например, через `formatter`):
- `kotlinx-collections-immutable-jvm`
- `caffeine`
- `kotlinx-serialization-core-jvm`
- `intellij-deps-fastutil`
- `asm-tree`, `asm-util`
- `org.jetbrains:annotations`

Проверить через `mvn dependency:tree` после шага B.

## Следующие шаги

1. **Вернуть `*:*`-эксклюзии** на все Класс-A артефакты в `Nbm/pom.xml`
   (откатить часть изменений WIP-коммита).

2. **Убрать `*:*`-эксклюзии только с `formatter`** (Класс B):
   - убедиться, что `kotlin-stdlib-jdk8:1.9.20-506` и `base-frontend-agnostic:231-*`
     скачаны в локальный репо (через curl+SOCKS5 если нет)

3. **Запустить сборку**:
   ```bash
   mvn package -DskipTests
   ```

4. **Проверить dependency tree** на предмет Класс-C кандидатов на удаление:
   ```bash
   mvn dependency:tree -pl Nbm | grep "kotlinx-collections\|caffeine\|serialization\|fastutil\|asm-tree\|asm-util\|annotations"
   ```

5. Убрать явные зависимости, которые подтверждённо приходят транзитивно.

6. Запустить тесты и убедиться что сборка зелёная.

## Локальный контекст

- Ветка: `refactor/transitive-deps`
- Maven mirror в `~/.m2/settings.xml` блокирует все внешние репозитории через
  корпоративный Artifactory (`openintegration.inc`), который не имеет JetBrains-internal артефактов.
  Поэтому все JetBrains артефакты скачиваются вручную через curl+SOCKS5.
- SOCKS5 proxy: `router.oleghome:11337`
- Локальный Maven repo: `~/.m2/repository/`
