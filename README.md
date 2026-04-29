[![JetBrains team project](http://jb.gg/badges/team.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub)
[![TeamCity (simple build status)](https://img.shields.io/teamcity/http/teamcity.jetbrains.com/s/Kotlin_NetBeansPlugin.svg)](https://teamcity.jetbrains.com/viewType.html?buildTypeId=Kotlin_NetBeansPlugin&branch_Kotlin=%3Cdefault%3E&tab=buildTypeStatusDiv)
[![GitHub license](https://img.shields.io/badge/license-Apache%20License%202.0-blue.svg?style=flat)](http://www.apache.org/licenses/LICENSE-2.0)

# Kotlin plugin for NetBeans IDE

**NOTE**: This plugin is no longer actively developed. See https://github.com/JetBrains/kotlin-netbeans/issues/122 for more information.

## Installing Kotlin plugin

### NetBeans Update Center

The plugin could be installed via NetBeans Update Center.

### Manual installation

1. Download the latest release: [0.2.0.1](https://github.com/JetBrains/kotlin-netbeans/releases/tag/v0.2.0.1)
2. Launch NetBeans IDE
3. Choose **Tools** and then **Plugins** from the main menu
4. Switch to **Downloaded** tab
5. On the **Downloaded** tab click **Add Plugins...** button
6. In the file chooser, navigate to the folder with downloaded plugin. Select the NBM file and click OK. The plugin will show up in the list of plugins to be installed.
7. Click **Install** button in the Plugins dialog
8. Complete the installation wizard by clicking **Next**, agreeing to the license terms and clicking **Install** button.

### Required JVM flags (NetBeans 23+ / Java 17+)

The plugin uses `sun.misc.Unsafe` and `java.lang.reflect` APIs that are encapsulated by default in Java 17+. Without the following flags, opening a `.kt` file causes `ExceptionInInitializerError` in `AtomicFieldUpdater` and the Kotlin environment never loads.

Add these flags to your user NetBeans configuration (no `sudo` required):

```bash
mkdir -p ~/.netbeans/27/etc
cat >> ~/.netbeans/27/etc/netbeans.conf << 'EOF'
netbeans_default_options="$netbeans_default_options -J--add-opens=java.base/java.lang.reflect=ALL-UNNAMED -J--add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED -J--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED -J--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"
EOF
```

Replace `27` with your NetBeans major version (check `Help → About`).

Verify the flags were applied:
```bash
grep "add-opens" ~/.netbeans/27/etc/netbeans.conf
```


## Plugin feature set

1. Syntax highlighting
2. Semantics highlighting
3. Diagnostics
4. Code completion
5. Navigation in Source Code
6. Quick fixes
7. Intentions and Inspections
8. Occurrences finder
9. Code folding 
10. Unit testing support
11. Ant, Maven and Gradle support
12. Navigation by Kotlin class name
13. Debugging support
