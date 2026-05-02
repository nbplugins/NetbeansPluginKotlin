# Installation Guide

## Prerequisites

| Requirement | Version |
|-------------|---------|
| NetBeans IDE | 23.0 (RELEASE230) or later |
| Java | 17 (required; Java 25 is not supported) |
| Maven | 3.6+ |
| Git | any recent version |

---

## Required JVM flags (NetBeans 23+ / Java 17+)

The plugin uses `sun.misc.Unsafe` and reflection APIs that are encapsulated by default in Java 17+.
Without these flags, opening any `.kt` file triggers `ExceptionInInitializerError` in
`AtomicFieldUpdater` and the Kotlin environment never loads.

**Apply these flags before launching NetBeans for the first time with the plugin.**

### User configuration (no sudo required — recommended)

```bash
# Replace 27 with your NetBeans major version (check Help → About)
NB_VERSION=27
mkdir -p ~/.netbeans/${NB_VERSION}/etc
cat >> ~/.netbeans/${NB_VERSION}/etc/netbeans.conf << 'EOF'
netbeans_default_options="$netbeans_default_options -J--add-opens=java.base/java.lang.reflect=ALL-UNNAMED -J--add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED -J--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED -J--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"
EOF
```

Verify:

```bash
grep "add-opens" ~/.netbeans/${NB_VERSION}/etc/netbeans.conf
```

### System-wide configuration (requires sudo)

```bash
sudo sed -i \
  's|-J--add-opens=java.base/java.lang=ALL-UNNAMED|-J--add-opens=java.base/java.lang=ALL-UNNAMED -J--add-opens=java.base/java.lang.reflect=ALL-UNNAMED -J--add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED -J--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED -J--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED|' \
  /usr/lib/apache-netbeans/etc/netbeans.conf
```

---

## Download

Download the latest `.nbm` file from [GitHub Releases](https://github.com/nbplugins/NetbeansPluginKotlin/releases/latest).

Intermediate builds between releases are available as artifacts on the
[Actions](https://github.com/nbplugins/NetbeansPluginKotlin/actions) page — open the latest
successful workflow run and download the `nbm` artifact (delivered as a zip file; extract
the `.nbm` before installing).

### Install into NetBeans

1. Open NetBeans → **Tools → Plugins**
2. Switch to the **Downloaded** tab
3. Click **Add Plugins…** and select the `.nbm` file
4. Click **Install** and follow the wizard (accept the license when prompted)
5. Restart NetBeans when prompted

---

## Build from source

### Step 1. Clone the repository

```bash
git clone --recurse-submodules https://github.com/nbplugins/NetbeansPluginKotlin.git
cd NetbeansPluginKotlin
```

### Step 2. Populate the local Maven repository

The plugin depends on bundled JARs that are not published to Maven Central. Install them once:

```bash
bash setup-local-repo.sh
```

Re-run this script any time a JAR in `lib/` is updated.

### Step 3. Build the plugin

```bash
JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk mvn clean package -DskipTests
```

The `.nbm` file is produced at `target/kotlin-netbeans-*.nbm`.

### Step 4. Install in NetBeans

1. Open NetBeans IDE.
2. Go to **Tools → Plugins**.
3. Switch to the **Downloaded** tab.
4. Click **Add Plugins…** and select the `.nbm` file from `target/`.
5. Click **Install** and follow the wizard (accept the license when prompted).
6. Restart NetBeans when asked.

---

## Verify the installation

1. Restart NetBeans after adding the JVM flags.
2. Open or create a `.kt` file — you should see Kotlin syntax highlighting and code completion.

If the editor shows plain text with no highlighting, check the log:

```bash
grep -i "kotlin\|AtomicFieldUpdater\|ExceptionInInitializerError" \
    ~/.netbeans/<version>/var/log/messages.log | tail -30
```

Common causes:

| Symptom | Cause | Fix |
|---------|-------|-----|
| No highlighting, `ExceptionInInitializerError` in log | Missing `--add-opens` flags | Add the JVM flags and restart |
| Build fails with `NoSuchMethodError` | Local Maven repo not populated | Run `bash setup-local-repo.sh` |
| Build fails with wrong Java version error | Wrong `JAVA_HOME` | Set `JAVA_HOME` to Java 17 |

---

## Running tests

```bash
# Full test suite
JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk mvn clean test

# Single test class
JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk mvn test -Dtest=ClassName
```

Tests require a display. On headless servers, Xvfb is started automatically by Maven on display `:99`.
