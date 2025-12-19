#!/bin/bash
# Karate CLI Testing Script
# Usage: ./etc/test-cli.sh [args...]
# Example: ./etc/test-cli.sh --help
# Example: ./etc/test-cli.sh -w home/test-project features

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_ROOT"

# Check if rebuild is needed
# Rebuild if: jar doesn't exist, or any source file is newer than the jar
JAR_FILE="karate-core/target/karate-core-2.0.0.RC1.jar"
NEEDS_BUILD=false

if [ ! -f "$JAR_FILE" ]; then
    NEEDS_BUILD=true
elif [ -n "$(find karate-core/src karate-js/src -name '*.java' -newer "$JAR_FILE" 2>/dev/null | head -1)" ]; then
    NEEDS_BUILD=true
fi

if [ "$NEEDS_BUILD" = true ]; then
    echo "Building project..."
    mvn install -DskipTests -q -pl karate-js,karate-core
fi

# Check for fatjar
FATJAR="karate-core/target/karate.jar"
if [ -f "$FATJAR" ]; then
    echo "Using fatjar: $FATJAR"
    java -jar "$FATJAR" "$@"
else
    # Build classpath
    echo "Building classpath..."
    CP=$(cd karate-core && mvn -q dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile=/dev/stdout)
    JAR="karate-core/target/karate-core-2.0.0.RC1.jar"
    TEST_CLASSES="karate-core/target/test-classes"

    echo "Running: java -cp ... io.karatelabs.Main $@"
    echo "---"

    # Include test-classes for Java.type() access to test POJOs
    java -cp "$JAR:$TEST_CLASSES:$CP" io.karatelabs.Main "$@"
fi
