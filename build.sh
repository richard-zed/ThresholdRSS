#!/data/data/com.termux/files/usr/bin/bash
# Core-layer test run. No Gradle -- the glue (OkHttpFetcher) needs the Android
# toolchain, so it is excluded here and covered by ./gradlew test.
set -e

CORE=app/src/main/java/dev/daisy/core
TEST=app/src/test/java/dev/daisy/core
LIB=lib

CP_JUNIT="$LIB/junit-4.13.2.jar:$LIB/hamcrest-core-1.3.jar"
CP_CORO="$LIB/kotlinx-coroutines-core-jvm-1.9.0.jar"
STDLIB=$(find "$PREFIX" -name 'kotlin-stdlib.jar' | head -1)

if [ ! -f "$LIB/kotlinx-coroutines-core-jvm-1.9.0.jar" ]; then
    echo "missing $CP_CORO -- fetching"
    mkdir -p "$LIB"
    curl -sSL -o "$LIB/kotlinx-coroutines-core-jvm-1.9.0.jar" \
        https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.9.0/kotlinx-coroutines-core-jvm-1.9.0.jar
fi


# Wipe first: a failed compile leaves the PREVIOUS run's classes in out/, and
# JUnit then reports a green suite for code that never built.
rm -rf out

kotlinc "$CORE"/{Model,Identity,Merge,DayBuckets,Discover,Fetch,Discoverer,YoutubeChannel,FeedParse,Dates}.kt \
        "$TEST"/{MergeTest,DiscoverTest,SniffTest,SuffixRuleTest,DiscoverPlanTest,DiscovererTest,YoutubeChannelTest,FeedParseTest,DatesTest}.kt \
        -classpath "$CP_JUNIT:$CP_CORO" \
        -d out

java -cp "out:$CP_JUNIT:$CP_CORO:$STDLIB" org.junit.runner.JUnitCore \
     dev.daisy.core.MergeTest \
     dev.daisy.core.DiscoverTest \
     dev.daisy.core.SniffTest \
     dev.daisy.core.SuffixRuleTest \
     dev.daisy.core.DiscoverPlanTest \
     dev.daisy.core.DiscovererTest \
     dev.daisy.core.YoutubeChannelTest \
     dev.daisy.core.YoutubeCandidateTest \
     dev.daisy.core.FeedParseTest \
     dev.daisy.core.DatesTest
