#!/usr/bin/env bash

set -euo pipefail

project_dir=$(cd -- "$(dirname -- "$0")" && pwd -P)
build_dir=$project_dir/build
dist_dir=$project_dir/dist

if [[ -n ${JAVA_HOME:-} && -x $JAVA_HOME/bin/javac && $($JAVA_HOME/bin/javac --version) == "javac 25"* ]]; then
    java_home=$JAVA_HOME
elif [[ $(uname -s) == Darwin ]]; then
    java_home=$(/usr/libexec/java_home -v 25)
else
    printf 'JAVA_HOME muss auf ein JDK 25 zeigen.\n' >&2
    exit 1
fi

rm -rf -- "$build_dir" "$dist_dir/QuickDiskScan.app" "$dist_dir/QuickDiskScan"
mkdir -p "$build_dir/classes" "$build_dir/test-classes" "$build_dir/package" \
    "$build_dir/javafx" "$build_dir/native" "$dist_dir"

find_javafx_jar() {
    local module=$1 jar=
    if [[ -n ${JAVAFX_HOME:-} ]]; then
        jar=$(find "$JAVAFX_HOME" -type f -name "$module*.jar" ! -name '*sources*' | head -1)
    fi
    if [[ -z $jar && -d ${HOME}/.gradle/caches/modules-2/files-2.1/org.openjfx/$module/25 ]]; then
        jar=$(find "${HOME}/.gradle/caches/modules-2/files-2.1/org.openjfx/$module/25" \
            -type f -name "$module-25-*.jar" ! -name '*sources*' | head -1)
    fi
    [[ -n $jar ]] || { printf 'JavaFX-25-Modul fehlt: %s\nJAVAFX_HOME setzen oder JavaFX 25 bereitstellen.\n' "$module" >&2; exit 1; }
    cp "$jar" "$build_dir/javafx/"
}

find_javafx_jar javafx-base
find_javafx_jar javafx-graphics
find_javafx_jar javafx-controls

case $(uname -s) in
    Darwin)
        native_name=libquickdiskscanmetrics.dylib
        cc -O3 -fPIC -dynamiclib \
            -I"$java_home/include" -I"$java_home/include/darwin" \
            "$project_dir/src/main/native/diskmetrics.c" -o "$build_dir/native/$native_name"
        icon_png=$project_dir/src/main/resources/de/schrell/quickdiskscan/icon.png
        icon_icns=$build_dir/QuickDiskScan.icns
        png_size=$(stat -f %z "$icon_png")
        {
            printf icns
            printf '%08x' "$((png_size + 16))" | xxd -r -p
            printf ic10
            printf '%08x' "$((png_size + 8))" | xxd -r -p
            command cat "$icon_png"
        } > "$icon_icns"
        package_options=(--mac-package-identifier de.schrell.quickdiskscan --icon "$build_dir/QuickDiskScan.icns")
        app_target=$dist_dir/QuickDiskScan.app
        ;;
    Linux)
        native_name=libquickdiskscanmetrics.so
        cc -O3 -fPIC -shared \
            -I"$java_home/include" -I"$java_home/include/linux" \
            "$project_dir/src/main/native/diskmetrics.c" -o "$build_dir/native/$native_name"
        package_options=(--icon "$project_dir/src/main/resources/de/schrell/quickdiskscan/icon.png")
        app_target=$dist_dir/QuickDiskScan
        ;;
    *)
        printf 'Dieses Skript unterstützt macOS und Linux; unter Windows build.ps1 verwenden.\n' >&2
        exit 1
        ;;
esac

mkdir -p "$build_dir/classes/de/schrell/quickdiskscan/native"
cp "$build_dir/native/$native_name" "$build_dir/classes/de/schrell/quickdiskscan/native/"

main_sources=()
while IFS= read -r -d '' source; do main_sources+=("$source"); done \
    < <(find "$project_dir/src/main/java" -name '*.java' -print0)
test_sources=()
while IFS= read -r -d '' source; do test_sources+=("$source"); done \
    < <(find "$project_dir/src/test/java" -name '*.java' -print0)

"$java_home/bin/javac" --release 25 -Xlint:all -Werror --module-path "$build_dir/javafx" --add-modules javafx.controls \
    -d "$build_dir/classes" "${main_sources[@]}"
cp -R "$project_dir/src/main/resources/." "$build_dir/classes/"
"$java_home/bin/javac" --release 25 -Xlint:all -Werror -cp "$build_dir/classes" \
    -d "$build_dir/test-classes" "${test_sources[@]}"
"$java_home/bin/java" --enable-native-access=ALL-UNNAMED -ea \
    -cp "$build_dir/classes:$build_dir/test-classes" de.schrell.quickdiskscan.DiskScannerTest
"$java_home/bin/java" -Duser.language=de -cp "$build_dir/classes:$build_dir/test-classes" \
    de.schrell.quickdiskscan.I18nTest Deutsch
"$java_home/bin/java" -Duser.language=en -cp "$build_dir/classes:$build_dir/test-classes" \
    de.schrell.quickdiskscan.I18nTest English
"$java_home/bin/java" -Duser.language=de -cp "$build_dir/classes:$build_dir/test-classes" \
    de.schrell.quickdiskscan.ByteFormatTest , .
"$java_home/bin/java" -Duser.language=en -cp "$build_dir/classes:$build_dir/test-classes" \
    de.schrell.quickdiskscan.ByteFormatTest . ,

"$java_home/bin/jar" --create --file "$build_dir/package/quickdiskscan.jar" \
    --main-class de.schrell.quickdiskscan.QuickDiskScanApp -C "$build_dir/classes" .

package_module_path=$build_dir/package/quickdiskscan.jar:$build_dir/javafx
if [[ -d $java_home/jmods ]]; then
    package_module_path=$java_home/jmods:$package_module_path
fi
"$java_home/bin/jpackage" --type app-image --name QuickDiskScan --dest "$dist_dir" \
    --module-path "$package_module_path" \
    --module de.schrell.quickdiskscan/de.schrell.quickdiskscan.QuickDiskScanApp \
    --java-options -Dfile.encoding=UTF-8 \
    --java-options --enable-native-access=javafx.graphics,de.schrell.quickdiskscan \
    --app-version 1.0.0 "${package_options[@]}"

printf 'Erstellt: %s\n' "$app_target"
