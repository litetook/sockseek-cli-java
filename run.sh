#!/usr/bin/env bash
# macOS / Linux launcher. Everything lives in the jar; this only finds Java and hands over.
set -euo pipefail
cd "$(dirname "$0")"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA=java
else
    echo "Java 21 is not installed."
    echo "https://adoptium.net/temurin/releases/?version=21  or  brew install --cask temurin@21"
    exit 2
fi

if [ ! -f build/libs/sockseek-cli.jar ]; then
    echo "Building..."
    ./gradlew --quiet fatJar
fi

exec "$JAVA" -jar build/libs/sockseek-cli.jar "$@"
