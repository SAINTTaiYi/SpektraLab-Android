#!/data/data/com.termux/files/usr/bin/bash
# javac + d8 for the app's Java. Separate from build-apk.sh (aapt + zip +
# apksigner) for the same reason build-engine.sh is separate from
# build-native.sh: a step that can fail needs to fail on its own line.
#
# Only the Java sources under app/src/main/java are built; the manifest names
# MainActivity.java and this toolchain has no Kotlin compiler.
set -e
cd "$HOME/SpektraLab-apk"
SDK="${ANDROID_SDK:-$HOME/Android/Sdk}"
JAR="$SDK/platforms/android-36/android.jar"
rm -rf app/build/classes app/build/dex
mkdir -p app/build/classes app/build/dex
find app/src/main/java -name '*.java' | sort > app/build/sources.txt
javac -source 17 -target 17 -encoding UTF-8 -Xlint:-options -nowarn \
  -classpath "$JAR" -d app/build/classes @app/build/sources.txt
d8 --lib "$JAR" --min-api 24 --output app/build/dex \
  $(find app/build/classes -name '*.class' | sort)
ls -la app/build/dex/classes.dex
