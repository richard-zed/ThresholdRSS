#!/data/data/com.termux/files/usr/bin/bash
# Builds and runs the command line front end. Separate from build.sh because it
# produces a jar rather than a test run, and the two fail for different reasons.
#
#   ./cli.sh discover https://www.youtube.com/@someone
#
# The jar is written to $OUT, which defaults to ~/Android/Projects/daisy -- the
# same tree the project lives in on the phone, so a built jar survives being
# reopened in an editor. Sets OUT_DIR to override.
set -e

# kotlinc 2.x ships a native Jansi for console colour, built against glibc.
# Termux cannot dlopen it, and the refusal prints an UnsatisfiedLinkError stack
# before any of our output. The error is SPURIOUS -- compilation succeeds either
# way -- but it is noise on every single invocation.
#
# Three switches were tried. Only the third works:
#   TERM=dumb              -- inert. Jansi loads its native lib at class-init,
#                             long before anything reads TERM.
#   -Djansi.passthrough    -- inert. Jansi's static initialiser calls
#                             LibraryLoader.loadLibrary unconditionally; the
#                             property is consulted AFTER the load is attempted,
#                             so it can only change what happens on success.
#   -Dlibrary.jansi.path   -- WORKS. Points Jansi at Termux's own libjansi
#                             build, so the bundled glibc one is never unpacked.
# It only works once `pkg install libjansi` has run; the path is checked below
# so a fresh machine gets a clear message instead of the same stack back.
JANSI=""
if [ -f "$PREFIX/lib/jansi/libjansi.so" ]; then
    JANSI="-Dlibrary.jansi.path=$PREFIX/lib/jansi"
fi

# Where daisy.jar lands. Defaults to the project's Android tree; the build
# product is separate from the source tree so ./cli.sh and ./build.sh never
# leave junk in the repo.
OUT=${OUT_DIR:-"$HOME/Android/Projects/daisy"}

CORE=app/src/main/java/dev/daisy/core
CLI=app/src/main/java/dev/daisy/cli
LIB=lib

CP_CORO="$LIB/kotlinx-coroutines-core-jvm-1.9.0.jar"
# $PREFIX is set in Termux; fall back to wherever kotlinc put it elsewhere.
if [ -n "$PREFIX" ]; then
    STDLIB=$(find "$PREFIX" -name 'kotlin-stdlib.jar' | head -1)
else
    STDLIB=$(find "$(dirname "$(readlink -f "$(command -v kotlinc)")")/.." -name 'kotlin-stdlib.jar' | head -1)
fi

if [ -z "$STDLIB" ]; then
    echo "could not locate kotlin-stdlib.jar; set PREFIX or add it to lib/"
    exit 1
fi

if [ ! -f "$CP_CORO" ]; then
    echo "missing $CP_CORO -- run ./build.sh once to fetch it"
    exit 1
fi

# Everything except the Android-only pieces. OkHttpFetcher needs OkHttp, which
# is an Android dependency; HttpFetcher is the JDK one the CLI uses instead.
rm -rf out-cli
mkdir -p "$OUT"
# kotlinc is a java launcher underneath, so JANSI has to reach it too -- that is
# where the UnsatisfiedLinkError stack is actually printed.
# JANSI is empty when libjansi is not installed, and `-J` with nothing after it
# makes kotlinc fail on an unknown option, so the flag is only passed when set.
JFLAG=()
[ -n "$JANSI" ] && JFLAG=("-J$JANSI")

kotlinc \
    "$CORE"/{Model,Identity,Merge,DayBuckets,Discover,Fetch,Discoverer,YoutubeChannel,FeedParse,Dates,Json,Store,Opml,HttpFetcher,Sync}.kt \
    "$CLI"/Main.kt \
    -classpath "$CP_CORO" \
    "${JFLAG[@]}" \
    -d out-cli

# A manifest is a text file ending in a newline. Built with a here-doc rather
# than printf so the escape cannot be swallowed: a literal backslash-n leaves
# "Main-Class" unterminated and jar reports no main manifest attribute.
cat > daisy.manifest <<'MANIFEST'
Main-Class: dev.daisy.cli.Main
MANIFEST
jar cfm "$OUT/daisy.jar" daisy.manifest -C out-cli .
rm -f daisy.manifest

echo "wrote $OUT/daisy.jar"

# The CLI needs the coroutines jar and the stdlib at runtime; a fat jar would be
# tidier but would need the Android build to agree on its contents.
exec java $JANSI -cp "$OUT/daisy.jar:$CP_CORO:$STDLIB" dev.daisy.cli.Main "$@"
