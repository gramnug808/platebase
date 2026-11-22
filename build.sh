#!/bin/bash
# PlateBase v1.0 — manual APK build, no Gradle (blocked in this VM:
# Java localhost TCP is intercepted, Gradle daemon fails).
# kotlinc + aapt2 + d8 + zipalign + apksigner, all single-process tools.
# Pattern proven by ~/workspace/alpr-signal/build.sh (v1.1).
#
# ML Kit closure: vendored from ~/workspace/alpr-signal/libs/mlkit/
# (ML Kit Text Recognition 16.0.1 bundled, explicit registrars,
# collection-jvm 1.3.0, javax.inject, androidx.core, arch core).
# NO Tesseract anywhere in this app — not in the dex, not in assets,
# not claimed as a fallback. ML Kit only.
set -e
cd "$(dirname "$0")"
export JAVA_HOME=$HOME/jdk17
export PATH=$JAVA_HOME/bin:$PATH
unset JAVA_TOOL_OPTIONS
BT=$HOME/Android/sdk/build-tools/34.0.0
ANDROID_JAR=$HOME/Android/sdk/platforms/android-34/android.jar
KOTLINC=$HOME/workspace/iot-lights-simple/tools/kotlinc/bin/kotlinc
STDLIB=$HOME/workspace/iot-lights-simple/tools/kotlinc/lib/kotlin-stdlib.jar
MLKIT_JARS=$(ls libs/mlkit/*.jar | tr '\n' ':')
MLKIT_FILES=$(ls libs/mlkit/*.jar)
mkdir -p build/classes build/dex build/test-classes tools

test -f assets/mlkit-google-ocr-models/taser_tflite_gocrlatin_mbv2_scriptid_aksara_layout_gcn_mobile_engine.binarypb || { echo "MISSING ML Kit bundled model assets"; exit 1; }
test -f libs/mlkit-native/arm64-v8a/libmlkit_google_ocr_pipeline.so || { echo "MISSING ML Kit native lib"; exit 1; }
test -f libs/mlkit/javax.inject-1.jar || { echo "MISSING libs/mlkit/javax.inject-1.jar"; exit 1; }
for j in collection-jvm-1.3.0.jar fragment-1.5.7-classes.jar activity-1.6.1-classes.jar savedstate-1.2.1-classes.jar versionedparcelable-1.2.0-classes.jar lifecycle-viewmodel-2.6.2-classes.jar lifecycle-livedata-core-2.5.1-classes.jar lifecycle-viewmodel-savedstate-2.5.1-classes.jar loader-1.1.0-classes.jar interpolator-1.0.0-classes.jar tracing-1.2.0-classes.jar lifecycle-event-companion.jar core-bundlekt.jar kotlinx-coroutines-core-jvm-1.7.3.jar; do
  test -f "libs/mlkit/$j" || { echo "MISSING libs/mlkit/$j — dex audit will fail"; exit 1; }
done
test -f assets/test-plates/ABC123.png || { echo "MISSING test plate ABC123.png"; exit 1; }
test -f assets/test-plates/123ABC.png || { echo "MISSING test plate 123ABC.png"; exit 1; }

echo "== unit tests =="
"$KOTLINC" -cp "$ANDROID_JAR" -d build/test-classes \
  src/art/phoenix/platebase/PlateLogic.kt src/art/phoenix/platebase/OcrPreprocess.kt \
  src/art/phoenix/platebase/Db.kt src/art/phoenix/platebase/Export.kt test/art/phoenix/platebase/Tests.kt
"$JAVA_HOME/bin/java" -cp "build/test-classes:$ANDROID_JAR:$STDLIB" \
  art.phoenix.platebase.PlateBaseTests

echo "== kotlinc compile =="
rm -rf build/classes; mkdir -p build/classes
"$KOTLINC" -cp "$ANDROID_JAR:$MLKIT_JARS" -d build/classes src/art/phoenix/platebase/*.kt

echo "== aapt2 link (assets: ML Kit models + test plates) =="
"$BT/aapt2" link -o build/app-unsigned.apk --manifest AndroidManifest.xml -I "$ANDROID_JAR" --auto-add-overlay -A assets

echo "== d8 dex (app + stdlib + ML Kit jars) =="
rm -rf build/dex; mkdir -p build/dex
jar cf build/app-classes.jar -C build/classes .
"$BT/d8" --output build/dex --lib "$ANDROID_JAR" --min-api 26 --release build/app-classes.jar "$STDLIB" $MLKIT_FILES

echo "== add classes.dex + native libs to APK =="
python3 -c "
import zipfile, os
with zipfile.ZipFile('build/app-unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:
    z.write('build/dex/classes.dex','classes.dex')
    d = 'libs/mlkit-native/arm64-v8a'
    for name in sorted(os.listdir(d)):
        if name.endswith('.so'):
            z.write(os.path.join(d, name), 'lib/arm64-v8a/%s' % name, compress_type=zipfile.ZIP_STORED)
print('dex + native libs added')
"

echo "== dex audit v2 (reachability: app + ML Kit entry classes over final dex) =="
python3 tools/dex_audit.py build/dex/classes.dex "$ANDROID_JAR" "$BT/dexdump"

echo "== zipalign =="
"$BT/zipalign" -f 4 build/app-unsigned.apk build/app-aligned.apk

echo "== apksigner =="
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android --out build/platebase.apk build/app-aligned.apk

echo "== verify =="
"$BT/apksigner" verify --verbose build/platebase.apk
"$BT/aapt2" dump badging build/platebase.apk | head -12
python3 -c "
import zipfile
z = zipfile.ZipFile('build/platebase.apk')
print('APK entries:', len(z.namelist()))
print(' mlkit model assets:', sum(1 for n in z.namelist() if n.startswith('assets/mlkit-google-ocr-models/')))
print(' test plates:', [n for n in z.namelist() if 'test-plates' in n])
print(' mlkit native libs:', [n for n in z.namelist() if 'libmlkit_google_ocr_pipeline' in n])
print(' tessdata present (must be empty):', [n for n in z.namelist() if 'tess' in n.lower()])
"
ls -lh build/platebase.apk
sha256sum build/platebase.apk
