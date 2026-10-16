# План ручного тестирования Refactor

## Цель

Проверить, что после установки новой версии плагина контекстное подменю **Refactor** снова отображается для Kotlin-файлов и Kotlin-папок/пакетов, а команды остаются внутри этого подменю.

## Подготовка

1. Закрыть NetBeans.
2. Установить NBM:
   `/home/oleg/my-projects/nbplugins/NetbeansPluginKotlin/Nbm/target/nbm/netbeans-plugin-kotlin-nbm-0.11.287-SNAPSHOT.nbm`
3. Запустить NetBeans и открыть Maven, Gradle или Ant/J2SE проект с Kotlin-файлами.
4. Убедиться, что Kotlin source root содержит хотя бы один `.kt` файл.

## 1. Kotlin-файл

1. В окне **Projects** выбрать Kotlin-файл.
2. Открыть контекстное меню.
3. Проверить, что присутствует пункт **Refactor**.
4. Открыть **Refactor**.
5. Проверить наличие доступных команд, например:
   - **Rename Kotlin File...**;
   - **Change Kotlin Package...** — если команда доступна для выбранного файла/контекста;
   - **Undo Last Refactoring** и **Redo Last Refactoring**.
6. Проверить, что эти команды не появились отдельными пунктами верхнего уровня.

## 2. Kotlin-папка или пакет

1. Выбрать Kotlin-папку в дереве проекта либо пакет в **Kotlin Source Packages** / **Kotlin Test Packages**.
2. Открыть контекстное меню.
3. Проверить наличие пункта **Refactor**.
4. Внутри **Refactor** проверить команды:
   - **Change Kotlin Package...**;
   - **Rename Kotlin Package Directory...**;
   - **Move Kotlin Directory...**;
   - **Undo Last Refactoring** и **Redo Last Refactoring**.
5. Убедиться, что ни одна из этих команд не отображается вне **Refactor**.

## 3. Открытие Change Kotlin Package

1. Выбрать папку/пакет, содержащий Kotlin-файлы.
2. Открыть **Refactor → Change Kotlin Package...**.
3. Проверить, что открывается диалог рефакторинга.
4. Изменить пакет и применить операцию.
5. Проверить package-директивы Kotlin-файлов и поддерживаемые ссылки.
6. Выполнить **Refactor → Undo Last Refactoring**.
7. Проверить, что изменения отменены.

## 4. Регрессия F7

1. На Kotlin-файле открыть **Refactor → Rename Kotlin File...**.
2. Выполнить переименование и проверить изменение имени файла.
3. На Kotlin-папке открыть **Refactor → Rename Kotlin Package Directory...**.
4. Выполнить переименование и проверить изменение каталога и сохранение содержимого.
5. Проверить **Undo Last Refactoring** для обеих операций.

## Ожидаемый результат

Для Kotlin-файла и Kotlin-папки/пакета контекстное меню содержит **Refactor**. Все Kotlin-команды рефакторинга доступны внутри него; отдельного верхнеуровневого пункта вместо подменю нет.
