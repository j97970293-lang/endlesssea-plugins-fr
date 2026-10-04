#!/usr/bin/env bash
# Génère le squelette d'un module d'extension Endless Sea (.esx).
#   ./tools/new-extension.sh <ModuleName> <package> <EntryClass> <versionCode> <versionName> <"Nom affiché"> <"description"> <langues csv> <types csv> <nsfw true|false> <iconDomain>
set -euo pipefail
cd "$(dirname "$0")/.."

MODULE="$1"; PKG="$2"; ENTRY="$3"; VC="$4"; VN="$5"; DISPLAY="$6"; DESC="$7"; LANGS="$8"; TYPES="$9"; NSFW="${10}"; ICON="${11}"

DIR="extensions/$MODULE"
mkdir -p "$DIR/src/main/assets" "$DIR/src/main/kotlin/${PKG//.//}"

cat > "$DIR/build.gradle.kts" <<EOF
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "$PKG"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "$PKG"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = $VC
        versionName = "$VN"
    }

    buildTypes {
        release {
            isMinifyEnabled = false   // le .esx est chargé par réflexion : pas d'obfuscation
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
    }
    sourceSets["main"].java.srcDir("src/main/kotlin")
}

dependencies {
    // Fournie par l'app hôte à l'exécution : jamais embarquée dans le .esx.
    compileOnly(project(":api-stub"))
    // Boîte à outils native (HTTP, JSON, extracteurs…) : embarquée.
    implementation(project(":common"))
    implementation(libs.org.jsoup)
}
EOF

cat > "$DIR/src/main/AndroidManifest.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <application android:hasCode="true" />
</manifest>
EOF

python3 - "$DIR/src/main/assets/extension.json" "$PKG" "$ENTRY" "$VC" "$VN" "$DISPLAY" "$DESC" "$LANGS" "$TYPES" "$NSFW" "$ICON" <<'PY'
import json, sys
out, pkg, entry, vc, vn, display, desc, langs, types, nsfw, icon = sys.argv[1:12]
manifest = {
    "id": pkg,
    "name": display,
    "version": int(vc),
    "versionName": vn,
    "apiVersion": 1,
    "description": {"fr": desc},
    "author": {"name": "j97970293-lang", "url": "https://github.com/j97970293-lang/endlesssea-plugins-fr"},
    "languages": langs.split(","),
    "types": types.split(","),
    "iconUrl": f"https://www.google.com/s2/favicons?domain={icon}&sz=128",
    "entryClass": entry,
    "permissions": ["INTERNET", "DOWNLOAD", "WEBVIEW"],
    "capabilities": {"search": True, "servers": True, "subtitles": True, "downloads": True, "auth": False},
    "sourceUrl": "https://github.com/j97970293-lang/endlesssea-plugins-fr",
    "nsfw": nsfw == "true",
}
with open(out, "w", encoding="utf-8") as f:
    json.dump(manifest, f, ensure_ascii=False, indent=2)
    f.write("\n")
PY

echo "→ $DIR"
