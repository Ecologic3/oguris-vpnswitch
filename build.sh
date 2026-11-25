#!/bin/sh
# Builds OguriVpnSwitch-v1.3.apk with the manual pipeline (no Gradle).
# Same pattern as the other Oguri apps (printer/chips): aapt2 link,
# javac (no lambdas, they break d8), d8, dex zipped in from inside the
# dex dir so classes.dex lands at the APK root, zipalign, apksigner.
set -e
cd "$(dirname "$0")"
export JAVA_HOME="$HOME/workspace/android-apk/toolchain/amazon-corretto-17.0.20.12.1-linux-x64"
SDK="$HOME/workspace/android-apk/toolchain/sdk"
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
export PATH="$JAVA_HOME/bin:$BT:$PATH"

KS="oguris-vpnswitch.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias oguris-vpnswitch -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass ogurisvpnswitch2026 -keypass ogurisvpnswitch2026 \
    -dname "CN=Oguri VPN Switch, O=Oguri, C=SK"
fi

OUT="OguriVpnSwitch-v1.3.apk"
rm -rf build
mkdir -p build/classes build/dex

aapt2 link -o build/unsigned.apk -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml
javac -encoding UTF-8 -source 8 -target 8 -cp "$ANDROID_JAR" \
    -d build/classes $(find src -name '*.java')
d8 --lib "$ANDROID_JAR" --min-api 29 --output build/dex \
    $(find build/classes -name '*.class')
(cd build/dex && zip -q ../unsigned.apk classes.dex)
zipalign -f -p 4 build/unsigned.apk build/aligned.apk
apksigner sign --ks "$KS" --ks-key-alias oguris-vpnswitch \
    --ks-pass pass:ogurisvpnswitch2026 --key-pass pass:ogurisvpnswitch2026 \
    --out "$OUT" build/aligned.apk
apksigner verify --print-certs "$OUT"
echo "Built $OUT"
