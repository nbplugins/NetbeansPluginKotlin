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

- [ ] Опечатка в имени типа подчёркивается красным
- [ ] Несовпадение типов (`val x: Int = "hello"`) показывает ошибку
- [ ] Неиспользуемая переменная показывает предупреждение
- [ ] Маркеры в полосе прокрутки (error stripe) отображаются

---

## 4. Автодополнение (Code Completion)

- [ ] Ctrl+Space на объекте показывает список методов/свойств
- [ ] Автодополнение работает для стандартной библиотеки (`listOf`, `mapOf`, …)
- [ ] Автодополнение работает для классов из зависимостей проекта
- [ ] Автодополнение показывает конструкторы при вводе имени класса
- [ ] Фильтрация по введённым символам работает

---

## 5. Навигация

- [ ] **Ctrl+Click** на имени функции/класса/свойства переходит к декларации
- [ ] Ctrl+Click на элементе из stdlib открывает декларацию в stdlib-sources (или показывает декомпилированный байткод)
- [ ] **Alt+F7** (Find Usages) находит все использования символа
- [ ] **Ctrl+B** (Go to Declaration) работает аналогично Ctrl+Click
- [ ] Навигация по методам класса через Navigator (Window → Navigating → Navigator)

---

## 6. Форматирование

- [ ] **Alt+Shift+F** форматирует файл без исключений
- [ ] Отступы расставляются по Kotlin-конвенции (4 пробела)
- [ ] Форматирование не портит код (сравнить до/после для нетривиального файла)

---

## 7. Быстрые исправления (Quick Fixes / Hints)

- [ ] На ошибке нажать Alt+Enter → появляется меню с вариантами исправления
- [ ] «Add import» для неимпортированного класса работает
- [ ] «Create function» для несуществующего вызова создаёт заглушку

---

## 8. Рефакторинг: Rename

- [ ] Переименование локальной переменной (Refactor → Rename) обновляет все вхождения в файле
- [ ] Переименование публичной функции обновляет вхождения в других файлах проекта

---

## 9. Конвертация Java → Kotlin (J2K)

- [ ] Открыть `.java` файл → меню Code → Convert Java to Kotlin
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

- [ ] Run → Build Project компилирует Kotlin-файлы без ошибок
- [ ] Ошибки компиляции отображаются в окне Output
- [ ] Run → Run Project запускает приложение

---

## 12. Совместимость типов проектов

- [ ] **Maven** проект: все пункты выше работают
- [ ] **Gradle** проект: синтаксис подсвечивается, нет краша при открытии
- [ ] **Ant/J2SE** проект: синтаксис подсвечивается, нет краша при открытии

---

## Известные ограничения (NB 28)

- `SourceRoots` недоступен из classloader плагина → classpath J2SE-проектов не расширяется (ошибка поймана, не вызывает краш)
- `DependencyResolutionRequiredException` из Maven API недоступен → compile-classpath Maven-проекта может быть неполным
- Навигация в декларации stdlib требует наличия sources-артефакта в локальном Maven-репозитории
