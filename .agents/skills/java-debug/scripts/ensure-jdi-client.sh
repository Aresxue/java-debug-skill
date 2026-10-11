#!/bin/bash
# Ensure scripts/jdi-client.jar exists, building it from the bundled source on first use
# (the jar itself is not shipped with the skill).
# Build order: Maven (jdi-client/build.sh) first, then plain JDK javac + jar as fallback.
# stdout: the jar path on success; all progress / errors go to stderr.
# Rebuild after a source change: delete scripts/jdi-client.jar and run this script again.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$(dirname "$SCRIPT_DIR")/jdi-client"
JAR="$SCRIPT_DIR/jdi-client.jar"
MAIN_CLASS="cn.ares.jvm.jdi.JdiClient"

log() {
    echo "[ensure-jdi-client] $*" >&2
}

if [ -f "$JAR" ]; then
    echo "$JAR"
    exit 0
fi

if [ ! -d "$SRC_DIR/src/main/java" ]; then
    log "source not found: $SRC_DIR/src/main/java"
    exit 1
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

# Write to a temp file first, then mv, so concurrent runs never see a half-written jar
install_jar() {
    cp "$1" "$JAR.tmp.$$" && mv -f "$JAR.tmp.$$" "$JAR"
}

build_with_maven() {
    command -v mvn >/dev/null 2>&1 || return 1
    log "building with maven..."
    bash "$SRC_DIR/build.sh" >&2
    local status=$?
    if [ $status -eq 0 ]; then
        install_jar "$SRC_DIR/target/jdi-client-1.0.0.jar"
        status=$?
    fi
    # Do not leave build output inside the installed skill directory
    rm -rf "$SRC_DIR/target"
    return $status
}

find_javac() {
    if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
        echo "$JAVA_HOME/bin/javac"
        return 0
    fi
    command -v javac 2>/dev/null
}

build_with_javac() {
    local javac
    javac="$(find_javac)"
    if [ -z "$javac" ]; then
        log "javac not found: a JDK (not just a JRE) is required, set JAVA_HOME or put javac on PATH"
        return 1
    fi
    local jar_tool
    jar_tool="$(dirname "$javac")/jar"
    [ -x "$jar_tool" ] || jar_tool="jar"

    # "javac 1.8.0_472" -> JDK 8; "javac 17.0.17" -> JDK 9+
    local version
    version="$("$javac" -version 2>&1 | awk '/^javac /{print $2; exit}')"
    local classpath=""
    case "$version" in
        1.*)
            # JDK 8: com.sun.jdi lives in tools.jar, which is not on the default classpath
            local java_home
            java_home="$("$javac" -J-XshowSettings:properties -version 2>&1 | awk -F' = ' '/java\.home =/{print $2; exit}')"
            local candidate
            for candidate in "$java_home/../lib/tools.jar" "$java_home/lib/tools.jar"; do
                if [ -f "$candidate" ]; then
                    classpath="$candidate"
                    break
                fi
            done
            if [ -z "$classpath" ]; then
                log "tools.jar not found under $java_home (JDK 8 needs it to compile against com.sun.jdi)"
                return 1
            fi
            ;;
    esac

    log "building with $javac (version $version)..."
    find "$SRC_DIR/src/main/java" -name '*.java' > "$TMP_DIR/sources.txt"
    mkdir -p "$TMP_DIR/classes"
    # Target bytecode 52 (same as the pom) so the jar also runs on JDK 8 machines in --manual mode
    local cp_args=()
    [ -n "$classpath" ] && cp_args=(-cp "$classpath")
    "$javac" -encoding UTF-8 -source 8 -target 8 -nowarn -Xlint:-options "${cp_args[@]}" \
        -d "$TMP_DIR/classes" "@$TMP_DIR/sources.txt" >&2 || return 1

    # Add-Opens lets ClassLoaderResolver fill JDI's private class loader cache; ignored by JDK 8
    printf 'Main-Class: %s\nAdd-Opens: jdk.jdi/com.sun.tools.jdi\n' "$MAIN_CLASS" > "$TMP_DIR/MANIFEST.MF"
    "$jar_tool" cfm "$TMP_DIR/jdi-client.jar" "$TMP_DIR/MANIFEST.MF" -C "$TMP_DIR/classes" . >&2 || return 1
    install_jar "$TMP_DIR/jdi-client.jar"
}

if build_with_maven; then
    log "build successful (maven)"
    echo "$JAR"
    exit 0
fi
command -v mvn >/dev/null 2>&1 && log "maven build failed, falling back to javac"

if build_with_javac; then
    log "build successful (javac)"
    echo "$JAR"
    exit 0
fi
log "build failed"
exit 1
