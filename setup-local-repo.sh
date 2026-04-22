#!/bin/bash
# Run this once to populate the project-local Maven repository (repo/)
# with the bundled JARs from lib/. After this, `mvn package` works normally.
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO="$SCRIPT_DIR/repo"

declare -A JARS=(
  ["lib/kotlin-ide-common.jar"]="org.jetbrains.kotlin:kotlin-ide-common"
  ["lib/openapi-formatter.jar"]="org.jetbrains.kotlin:openapi-formatter"
  ["lib/kotlin-formatter.jar"]="org.jetbrains.kotlin:kotlin-formatter"
  ["lib/idea-formatter.jar"]="org.jetbrains.kotlin:idea-formatter"
  ["lib/intellij-core.jar"]="org.jetbrains.kotlin:intellij-core"
  ["lib/kotlin-converter.jar"]="org.jetbrains.kotlin:kotlin-converter"
  ["lib/asm-all.jar"]="org.jetbrains.kotlin:asm"
)

for file in "${!JARS[@]}"; do
  coords="${JARS[$file]}"
  groupId="${coords%%:*}"
  artifactId="${coords##*:}"
  echo "Installing $file -> $groupId:$artifactId:1.0"
  mvn install:install-file \
    -Dfile="$SCRIPT_DIR/$file" \
    -DgroupId="$groupId" \
    -DartifactId="$artifactId" \
    -Dversion=1.0 \
    -Dpackaging=jar \
    -DlocalRepositoryPath="$REPO" \
    -DcreateChecksum=true \
    -q
done

echo "Done. repo/ is populated. You can now run: mvn clean package"
