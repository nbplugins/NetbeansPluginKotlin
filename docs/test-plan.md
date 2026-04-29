# Kotlin NetBeans Plugin — Manual Test Plan

Target: NetBeans 28 + Kotlin plugin 0.3.x  
Prerequisites:
- Plugin installed via Tools → Plugins → Downloaded
- `/usr/lib/apache-netbeans/etc/netbeans.conf` содержит `--add-opens=java.base/java.lang.reflect=ALL-UNNAMED`
- Открыт Maven-проект с `.kt` файлами

---

## 1. Установка и загрузка

- [x] Плагин отображается в Tools → Plugins → Installed как «Kotlin»
- [x] При открытии Maven Kotlin-проекта прогресс-бар «Loading Kotlin environment» появляется и **завершается** (не зависает)
- [x] В `messages.log` нет `ExceptionInInitializerError` от `KotlinEnvironment`

---

## 2. Подсветка синтаксиса

- [x] `.kt` файл открывается с подсветкой ключевых слов (`fun`, `class`, `val`, `var`, `if`, `when`, …)
- [x] Строки и комментарии подсвечены отдельными цветами
- [x] Аннотации (`@Override`, `@JvmStatic`) подсвечены
- [x] Семантическая подсветка: локальные переменные, поля, параметры различаются цветом

---

## 3. Диагностика (ошибки и предупреждения)

- [x] Опечатка в имени типа подчёркивается красным
- [x] Несовпадение типов (`val x: Int = "hello"`) показывает ошибку
- [x] Неиспользуемая переменная показывает предупреждение
- [x] Маркеры в полосе прокрутки (error stripe) отображаются

---

## 4. Автодополнение (Code Completion)

- [x] Ctrl+Space на объекте показывает список методов/свойств
- [x] Автодополнение работает для стандартной библиотеки (`listOf`, `mapOf`, …)
- [x] Автодополнение работает для классов из зависимостей проекта
- [x] Автодополнение показывает конструкторы при вводе имени класса
- [x] Фильтрация по введённым символам работает

---

## 5. Навигация

> Примечание: тестировать навигацию только на `.kt`-файлах проекта, используя ссылки на типы и функции,
> объявленные в `.kt`-файлах (не в JDK/stdlib). Причины — см. раздел «Известные ограничения».
> Тестовый файл: `MultiRangeSequence.kt`.
>
> **ВАЖНО:** если Right Ctrl не работает — проверьте, не назначен ли он как Host Key в VirtualBox (File → Preferences → Input → Host Key Combination).

- [x] **Ctrl+Click** на имени функции/класса/свойства переходит к декларации
  - `putInitialSegment(rangeSelector, ...)` → декларация в `IRangeStore`
  - `RangeSelector(destination, rangeCode)` → `data class RangeSelector`
  - `StoredSegmentRange(rangeStart, rangeEnd)` → `data class StoredSegmentRange`
  - `SetStartResult.SET_START_NEXT_OK` → enum-константа в `SetStartResult`
- [ ] Ctrl+Click на элементе из stdlib открывает декларацию в stdlib-sources (или показывает декомпилированный байткод)
  > ⚠ Заблокировано known limitation: JDK/stdlib-классы недоступны при анализе (см. раздел «Известные ограничения»). `Math.min`, `kotlin.math.max` — «Unresolved reference».
- [ ] **Alt+F7** (Find Usages) находит все использования символа
  > ⚠ Не реализовано: плагин не регистрирует `IndexSearcher`/`WhereUsedQuery` в CSL. Alt+F7 не вызывает код плагина.
- [ ] **Ctrl+B** (Go to Declaration) работает аналогично Ctrl+Click
  > ⚠ Не реализовано: `getDeclarationFinder()` в `KotlinLanguage` не переопределён. Используй Ctrl+Click вместо Ctrl+B.
- [ ] Навигация по методам класса через Navigator (Window → Navigating → Navigator)
  > ⚠ Баг: Navigator показывает посторонние элементы вместо структуры класса. Вероятная причина: `KotlinStructureScanner.scan()` возвращает пустой список (bindingContext == null при вызове из Navigator), и NB откатывается к дефолтному Java-навигатору (зарегистрированному через `JavaIndexer.shadow` в `layer.xml`).

---

## 6. Форматирование

- [x] **Alt+Shift+F** форматирует файл без исключений
- [x] Отступы расставляются по Kotlin-конвенции (4 пробела)
  > Размер отступа захардкожен (`IndenterUtil.DEFAULT_INDENT = 4`). Настройка через Tools → Options не реализована — требует чтения из `CodeStylePreferences` для `text/x-kt`.
- [x] Форматирование не портит код (сравнить до/после для нетривиального файла)

---

## 7. Быстрые исправления (Quick Fixes / Hints)

- [x] На ошибке нажать Alt+Enter → появляется меню с вариантами исправления
- [x] «Add import» для неимпортированного класса работает
- [ ] «Create function» для несуществующего вызова создаёт заглушку
  > ⚠ Не работает: Alt+Enter на несуществующей функции не предлагает «Create function».

---

## 8. Рефакторинг: Rename

- [ ] Переименование локальной переменной (Refactor → Rename) обновляет все вхождения в файле
  > ⚠ Не работает: «The Rename refactoring cannot be applied in this context.»
- [ ] Переименование публичной функции обновляет вхождения в других файлах проекта
  > ⚠ Не работает (то же сообщение).

---

## 9. Конвертация Java → Kotlin (J2K)

- [ ] Открыть `.java` файл → меню Code → Convert Java to Kotlin
  > ⚠ Пункт меню отсутствует: `Java2KotlinConverter` не зарегистрирован ни в `layer.xml`, ни через `@ActionRegistration`. Класс существует, но action не подключён.
- [ ] Конвертированный `.kt` файл синтаксически корректен
- [ ] Не появляется NPE или диалог с исключением

---

## 10. Отладка

> **Внимание:** регистрация debugger-провайдеров (`KotlinToggleBreakpointActionProvider`, `KotlinSourcePathProvider`, `GlyphGutterActions/KotlinToggleBreakpointAction`) временно отключена в `layer.xml` (с версии 0.3.19), потому что `org.netbeans.modules.debugger.jpda.EditorContextBridge` находится в Friend-restricted модуле и недоступен из нашего classloader-а. Перед прогоном этого раздела:
> 1. Переписать `KotlinToggleBreakpointActionProvider` (и связанные debugger-классы) на reflection-доступ к `EditorContextBridge`, по аналогии с `MavenHelper`.
> 2. Восстановить блок `<folder name="Debugger">` и `<folder name="GlyphGutterActions">` в `src/main/resources/org/jetbrains/kotlin/layer.xml`.
> 3. Только после этого имеет смысл проверять пункты ниже.

- [ ] Точка останова на строке `.kt` файла устанавливается (красный кружок в gutter)
- [ ] Запуск проекта в Debug-режиме останавливается на точке останова
- [ ] В окне Variables видны локальные переменные Kotlin
- [ ] Step Over / Step Into работают

---

## 11. Сборка

- [x] Run → Build Project компилирует Kotlin-файлы без ошибок
- [x] Ошибки компиляции отображаются в окне Output (вывод Maven)
- [x] Run → Run Project запускает приложение

---

## 12. Совместимость типов проектов

- [x] **Maven** проект: все пункты выше работают
- [x] **Gradle** проект: синтаксис подсвечивается, нет краша при открытии
  > NB не открывает Gradle multi-project как проект (ограничение NB, не плагина); отдельные `.kt` файлы открываются с подсветкой.
- [ ] **Ant/J2SE** проект: синтаксис подсвечивается, нет краша при открытии
  > Не проверено: подходящего проекта нет под рукой.

---

## Известные ограничения (NB 28)

- `SourceRoots` недоступен из classloader плагина → classpath J2SE-проектов не расширяется (ошибка поймана, не вызывает краш)
- `DependencyResolutionRequiredException` из Maven API недоступен → compile-classpath Maven-проекта может быть неполным
- Навигация в декларации stdlib требует наличия sources-артефакта в локальном Maven-репозитории
- **JDK-классы недоступны при анализе** (`java.io.Serializable`, `ConcurrentHashMap` и др.) — Kotlin 1.3.72 компилятор не может правильно настроить classpath для JDK модульной системы (JPMS). Следствие: (а) навигация на JDK-классы не работает; (б) навигация на свойства/методы объектов, чьи типы транзитивно зависят от JDK-типов, не работает. Для тестирования навигации использовать только ссылки на типы и функции, объявленные в `.kt`-файлах проекта.
