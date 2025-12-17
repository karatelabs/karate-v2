#!/bin/bash
# Karate CLI Testing Script
# Usage: ./etc/test-cli.sh [args...]
# Example: ./etc/test-cli.sh --help
# Example: ./etc/test-cli.sh -w home/test-project features

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_ROOT"

# Build if needed
if [ ! -f "karate-core/target/karate-core-2.0.0.RC1.jar" ]; then
    echo "Building project..."
    mvn install -DskipTests -q
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

    echo "Running: java -cp ... io.karatelabs.Main $@"
    echo "---"

    java -cp "$JAR:$CP" io.karatelabs.Main "$@"
fi
