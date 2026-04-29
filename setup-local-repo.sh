#!/bin/bash
# Run this once to populate the project-local Maven repository (repo/)
# with the bundled JARs from lib/. After this, `mvn package` works normally.
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO="$SCRIPT_DIR/repo"

install_jar() {
  local file="$1" groupId="$2" artifactId="$3" version="$4" pomFile="${5:-}"
  [ -f "$SCRIPT_DIR/$file" ] || { echo "Skipping missing $file"; return; }
  echo "Installing $file -> $groupId:$artifactId:$version"
  if [ -n "$pomFile" ]; then
    mvn install:install-file \
      -Dfile="$SCRIPT_DIR/$file" \
      -DpomFile="$SCRIPT_DIR/$pomFile" \
      -DlocalRepositoryPath="$REPO" \
      -DcreateChecksum=true \
      -q
  else
    mvn install:install-file \
      -Dfile="$SCRIPT_DIR/$file" \
      -DgroupId="$groupId" \
      -DartifactId="$artifactId" \
      -Dversion="$version" \
      -Dpackaging=jar \
      -DlocalRepositoryPath="$REPO" \
      -DcreateChecksum=true \
      -q
  fi
}

# kotlin-compiler: use committed POM so transitive deps (stdlib, trove4j, etc.) are inherited
install_jar \
  "lib/kotlin-compiler-1.3.72-PATCHED.jar" \
  "org.jetbrains.kotlin" "kotlin-compiler" "1.3.72-PATCHED" \
  "lib/kotlin-compiler-1.3.72-PATCHED.pom"

# Patched JARs (version 1.0-PATCHED)
install_jar "lib/kotlin-ide-common-1.0-PATCHED.jar"  org.jetbrains.kotlin kotlin-ide-common  1.0-PATCHED
install_jar "lib/kotlin-formatter-1.0-PATCHED.jar"   org.jetbrains.kotlin kotlin-formatter   1.0-PATCHED
install_jar "lib/intellij-core-1.0-PATCHED.jar"      org.jetbrains.kotlin intellij-core      1.0-PATCHED
install_jar "lib/kotlin-converter-1.0-PATCHED.jar"   org.jetbrains.kotlin kotlin-converter   1.0-PATCHED

# Unpatched JARs (version 1.0)
install_jar "lib/openapi-formatter.jar" org.jetbrains.kotlin openapi-formatter 1.0
install_jar "lib/idea-formatter.jar"    org.jetbrains.kotlin idea-formatter    1.0
install_jar "lib/asm-all.jar"           org.jetbrains.kotlin asm               1.0

echo "Done. repo/ is populated. You can now run: mvn clean package"
