#!/usr/bin/env bash
# The one command, for macOS and Linux. Everything it needs it fetches itself, says what it
# is doing, and writes the same lines into logs/install.log:
#
#   Java     -> a private copy in jdk/, pinned and sha256-checked (dependencies.yaml)
#   the jar  -> from the latest GitHub release, if it is not here already
#   sockseek -> the jar does that itself, the first time a download needs it
#
# Nothing is installed system-wide, no sudo, no Homebrew needed. Delete the folder and it is
# gone. If you would rather have Java in the system, `brew install --cask temurin@21` also
# works and this will use it: the search below prefers whatever Java is already on PATH.
set -uo pipefail
cd "$(dirname "$0")"

LOG="$PWD/logs/install.log"
mkdir -p logs

say() {
    echo "$@"
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" >> "$LOG"
}

# ---------------------------------------------------------------- which Java, if any
JAVA=""
java_is_new_enough() {
    local candidate="$1"
    local said
    said=$("$candidate" -version 2>&1 | head -1) || return 1
    # "openjdk version "21.0.12.1" ..." for 21, "1.8.0_421" for 8
    local version
    version=$(printf '%s\n' "$said" | sed -n 's/.*version "\([0-9][0-9.]*\).*/\1/p')
    local major=${version%%.*}
    [ "$major" = "1" ] && return 1
    [ -n "$major" ] && [ "$major" -ge 21 ] 2>/dev/null
}

if [ -x "jdk/bin/java" ] && java_is_new_enough "jdk/bin/java"; then
    JAVA="$PWD/jdk/bin/java"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ] \
        && java_is_new_enough "$JAVA_HOME/bin/java"; then
    JAVA="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1 && java_is_new_enough java; then
    JAVA=java
fi

# ---------------------------------------------------------------- the pins, read by hand
pin() {
    local key="$1"
    local value
    value=$(sed -n "s|^${key}: *||p" dependencies.yaml | head -1)
    if [ -z "$value" ]; then
        say "ERROR dependencies.yaml has no '$key'"
        exit 2
    fi
    printf '%s\n' "$value"
}

platform_key() {
    local os arch
    os=$(uname -s)
    arch=$(uname -m)
    case "$os" in
        Darwin) case "$arch" in arm64|aarch64) echo macos-arm64 ;; *) echo macos-x64 ;; esac ;;
        *) echo linux-x64 ;;
    esac
}

sha256_of() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | cut -d' ' -f1
    else
        sha256sum "$1" | cut -d' ' -f1
    fi
}

# ---------------------------------------------------------------- Java, if there was none
if [ -z "$JAVA" ]; then
    platform=$(platform_key)
    version=$(pin java.version)
    asset=$(pin "java.${platform}.asset")
    want=$(pin "java.${platform}.sha256")
    url=$(pin java.url | sed "s|{asset}|${asset}|")

    say "Java 21 was not found, so this fetches a private copy into $PWD/jdk."
    say "About 47 MB, pinned and sha256-checked, nothing installed system-wide, no sudo."
    say "downloading Java $version"
    say "  $url"
    tarball="${TMPDIR:-/tmp}/$asset"
    if ! curl -fsSL "$url" -o "$tarball"; then
        say "ERROR the download failed. The whole attempt is in $LOG"
        exit 2
    fi
    got=$(sha256_of "$tarball")
    if [ "$got" != "$want" ]; then
        rm -f "$tarball"
        say "ERROR sha256 mismatch for $asset. expected $want, got $got"
        exit 2
    fi
    say "sha256 ok (Java $version)"

    # unpacked beside the target and renamed, so an interrupted unpack leaves no half a Java
    rm -rf jdk.part && mkdir -p jdk.part
    if ! tar -xzf "$tarball" -C jdk.part; then
        rm -rf jdk.part && rm -f "$tarball"
        say "ERROR the archive could not be unpacked"
        exit 2
    fi
    rm -f "$tarball"
    # macOS wraps it in <dir>/Contents/Home, Linux does not
    home=""
    for candidate in jdk.part/*/Contents/Home jdk.part/*; do
        if [ -x "$candidate/bin/java" ]; then
            home="$candidate"
            break
        fi
    done
    if [ -z "$home" ]; then
        rm -rf jdk.part
        say "ERROR the archive did not hold bin/java where it was expected"
        exit 2
    fi
    rm -rf jdk && mv "$home" jdk && rm -rf jdk.part
    JAVA="$PWD/jdk/bin/java"
    "$JAVA" -version 2>&1 | while IFS= read -r line; do say "$line"; done
    say "installed Java $version -> $PWD/jdk"
fi

# ---------------------------------------------------------------- the jar
JAR=""
if [ -f sockseek-cli.jar ]; then
    JAR="$PWD/sockseek-cli.jar"
elif [ -f build/libs/sockseek-cli.jar ]; then
    JAR="$PWD/build/libs/sockseek-cli.jar"
elif [ -x ./gradlew ]; then
    say "Building the jar from this checkout..."
    ./gradlew --quiet fatJar && JAR="$PWD/build/libs/sockseek-cli.jar"
fi

if [ -z "$JAR" ]; then
    say "sockseek-cli.jar is not here yet; taking it from the latest release."
    api="https://api.github.com/repos/litetook/sockseek-cli-java/releases/latest"
    # the jar cannot be pinned by a sha256 that ships inside it; this is the same trust as
    # downloading the release by hand, over HTTPS, from the repository this launcher came from
    link=$(curl -fsSL -H 'User-Agent: sockseek-cli-launcher' "$api" \
        | sed -n 's/.*"browser_download_url": *"\([^"]*sockseek-cli\.jar\)".*/\1/p' | head -1)
    if [ -z "$link" ]; then
        say "ERROR could not find sockseek-cli.jar in the latest release"
        say "https://github.com/litetook/sockseek-cli-java/releases also has it."
        exit 2
    fi
    say "  $link"
    if ! curl -fsSL "$link" -o sockseek-cli.jar.part; then
        say "ERROR the jar download failed. The whole attempt is in $LOG"
        exit 2
    fi
    mv sockseek-cli.jar.part sockseek-cli.jar
    JAR="$PWD/sockseek-cli.jar"
    say "installed sockseek-cli.jar -> $JAR"
fi

# ---------------------------------------------------------------- and over to the tool
exec "$JAVA" -jar "$JAR" "$@"
