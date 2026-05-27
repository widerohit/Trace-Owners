# Build Instructions for TraceOwners Plugin

## Quick Build Command

Open a terminal/command prompt in the project directory and run:

### Windows (Command Prompt or PowerShell)
```batch
cd w:\7.Unsync Project\TraceOwners
gradlew.bat buildPlugin --no-build-cache
```

### macOS/Linux
```bash
cd /path/to/TraceOwners
./gradlew buildPlugin --no-build-cache
```

## What This Does

The `buildPlugin` task will:
1. ✅ Compile Kotlin source code
2. ✅ Compile Java source code
3. ✅ Run all tests (if any)
4. ✅ Package classes into JAR
5. ✅ Create the plugin descriptor
6. ✅ Generate plugin ZIP file

## Expected Output

```
> Task :compileKotlin
Compiling 16 source files with kotlin compiler

> Task :compileJava
Compiling 0 source files

> Task :jar
Building jar: build/libs/TraceOwners-0.1.0.jar

> Task :buildPlugin
Building plugin: build/distributions/TraceOwners-0.1.0.zip

BUILD SUCCESSFUL in 45s
```

## Generated Artifact

After successful build, you'll find:

```
build/distributions/TraceOwners-0.1.0.zip
```

This ZIP file contains the complete plugin ready for installation in IntelliJ IDEA.

## Installation

### In IntelliJ IDEA:
1. **File** → **Settings** → **Plugins**
2. Click the **⚙️ (Settings)** icon
3. Select **Install Plugin from Disk...**
4. Browse to: `build/distributions/TraceOwners-0.1.0.zip`
5. Click **Open**
6. Restart IntelliJ IDEA

## Troubleshooting

### Issue: "java: command not found"
**Fix:** Install Java 17 or later and add to PATH

### Issue: Gradle build fails with "Out of Memory"
**Fix:** Increase memory: `set GRADLE_OPTS=-Xmx2g` (Windows) or `export GRADLE_OPTS="-Xmx2g"` (macOS/Linux)

### Issue: Build takes very long first time
**Fix:** This is normal - first build downloads dependencies and plugins. Subsequent builds are faster.

### Issue: Plugin not loaded after restart
**Fix:** 
1. Invalidate IDE cache: **File** → **Invalidate Caches...**
2. Restart IDE

## Verify Build Success

After building, verify the ZIP exists:

```bash
# Windows
dir build\distributions\

# macOS/Linux
ls -la build/distributions/
```

You should see:
```
TraceOwners-0.1.0.zip
```

## Next Steps

1. ✅ Build the plugin: `gradlew buildPlugin --no-build-cache`
2. ✅ Find: `build/distributions/TraceOwners-0.1.0.zip`
3. ✅ Install in IntelliJ IDEA (see Installation above)
4. ✅ Restart IntelliJ IDEA
5. ✅ Open a Java/Kotlin file
6. ✅ Test: Right-click → TraceOwners → Find Experts
7. ✅ Or use: Ctrl+Alt+Shift+O shortcut

## Build Customization

### Skip tests:
```bash
gradlew buildPlugin --no-build-cache -x test
```

### Verbose output:
```bash
gradlew buildPlugin --no-build-cache --info
```

### Full stacktrace on error:
```bash
gradlew buildPlugin --no-build-cache --stacktrace
```

### Clean build (remove all build artifacts first):
```bash
gradlew clean buildPlugin --no-build-cache
```

## System Requirements for Building

- **Java:** 17 or later
- **Gradle:** 8.0 or later (included via wrapper)
- **Kotlin:** 2.2.21 (included via plugin)
- **IntelliJ Platform:** 2024.1.4+ (downloaded by build)

## IDE Compatibility

Built plugin supports IntelliJ IDEA builds:
- ✅ 241 (2024.1.x)
- ✅ 242 (2024.2.x)
- ✅ 251 (2025.1.x)
- ✅ 261 (2025.1.x)

Compatible with:
- ✅ IntelliJ IDEA Community Edition
- ✅ IntelliJ IDEA Ultimate Edition
- ✅ JetBrains IDEs with Java/Kotlin support
