#!/bin/bash
# Run V1 feature files against V2 engine
# Usage: ./etc/run-v1-compat.sh <feature_path> [--debug] [--update-csv]
#
# Arguments:
#   feature_path  Path relative to V1 core dir (e.g., copy.feature, parallel/parallel.feature)
#   --debug       Copy to home/v1-compat/ if test fails for debugging
#   --update-csv  Update V1_BASELINE.csv with test result
#
# Examples:
#   ./etc/run-v1-compat.sh copy.feature
#   ./etc/run-v1-compat.sh parallel/parallel.feature --debug
#   ./etc/run-v1-compat.sh call-feature.feature --update-csv

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
V1_DIR="/Users/peter/dev/zcode/karate/karate-core/src/test/java/com/intuit/karate/core"
V2_DIR="$PROJECT_ROOT"
COMPAT_DIR="$V2_DIR/home/v1-compat"
RESULTS_DIR="$V2_DIR/target/v1-compat-results"
CSV_FILE="$V2_DIR/docs/V1_BASELINE.csv"

usage() {
    echo "Usage: $0 <feature_path> [--debug] [--update-csv]"
    echo ""
    echo "Arguments:"
    echo "  feature_path   Path relative to V1 core dir"
    echo "  --debug        Copy to home/v1-compat/ on failure"
    echo "  --update-csv   Update V1_BASELINE.csv with result"
    echo ""
    echo "Examples:"
    echo "  $0 copy.feature"
    echo "  $0 parallel/parallel.feature --debug"
    exit 1
}

[ $# -lt 1 ] && usage

FEATURE_PATH="$1"
shift

DEBUG_MODE=false
UPDATE_CSV=false

while [ $# -gt 0 ]; do
    case "$1" in
        --debug) DEBUG_MODE=true ;;
        --update-csv) UPDATE_CSV=true ;;
        *) echo "Unknown option: $1"; usage ;;
    esac
    shift
done

FULL_PATH="$V1_DIR/$FEATURE_PATH"

if [ ! -f "$FULL_PATH" ]; then
    echo "Error: Feature file not found: $FULL_PATH"
    exit 2
fi

# Ensure directories exist
mkdir -p "$COMPAT_DIR" "$RESULTS_DIR"

# Create temp directory for test
TEMP_DIR=$(mktemp -d)
trap "rm -rf $TEMP_DIR" EXIT

echo "========================================"
echo "V1 Compatibility Test"
echo "========================================"
echo "Feature: $FEATURE_PATH"
echo "Source:  $FULL_PATH"
echo "Temp:    $TEMP_DIR"
echo ""

# Copy feature file
cp "$FULL_PATH" "$TEMP_DIR/"

# Copy related files from same directory (dependencies)
FEATURE_DIR=$(dirname "$FULL_PATH")
for ext in js json csv yml yaml xml txt; do
    for file in "$FEATURE_DIR"/*.$ext; do
        [ -f "$file" ] && cp "$file" "$TEMP_DIR/" 2>/dev/null || true
    done
done

# Copy any karate-config.js from feature dir or parent
for config_dir in "$FEATURE_DIR" "$FEATURE_DIR/.." "$V1_DIR"; do
    if [ -f "$config_dir/karate-config.js" ]; then
        cp "$config_dir/karate-config.js" "$TEMP_DIR/"
        echo "Config:  $config_dir/karate-config.js"
        break
    fi
done

# Copy any called features (look for patterns like: read('xxx.feature') or call read('xxx'))
while IFS= read -r called_feature; do
    if [ -f "$FEATURE_DIR/$called_feature" ]; then
        cp "$FEATURE_DIR/$called_feature" "$TEMP_DIR/" 2>/dev/null || true
    elif [ -f "$V1_DIR/$called_feature" ]; then
        cp "$V1_DIR/$called_feature" "$TEMP_DIR/" 2>/dev/null || true
    fi
done < <(grep -oE "read\(['\"]([^'\"]+\.feature)['\"]" "$FULL_PATH" 2>/dev/null | sed "s/read(['\"]//;s/['\"])//" || true)

# Also copy features referenced in same directory
for dep_feature in "$FEATURE_DIR"/*.feature; do
    [ -f "$dep_feature" ] && [ "$dep_feature" != "$FULL_PATH" ] && cp "$dep_feature" "$TEMP_DIR/" 2>/dev/null || true
done

echo ""
echo "Running against V2 engine..."
echo "----------------------------------------"

# Run against V2
cd "$V2_DIR"
LOG_FILE="$RESULTS_DIR/$(basename "$FEATURE_PATH" .feature).log"

set +e
./etc/test-cli.sh "$TEMP_DIR/$(basename "$FEATURE_PATH")" 2>&1 | tee "$LOG_FILE"
EXIT_CODE=${PIPESTATUS[0]}
set -e

echo "----------------------------------------"

if [ $EXIT_CODE -eq 0 ]; then
    echo ""
    echo "PASSED: $FEATURE_PATH"
    RESULT="passed"
else
    echo ""
    echo "FAILED: $FEATURE_PATH (exit code: $EXIT_CODE)"
    RESULT="failed"

    if [ "$DEBUG_MODE" = true ]; then
        # Copy to debug workspace
        DEBUG_DIR="$COMPAT_DIR/$(dirname "$FEATURE_PATH")"
        mkdir -p "$DEBUG_DIR"
        cp -r "$TEMP_DIR"/* "$DEBUG_DIR/"
        echo ""
        echo "Debug files copied to: $DEBUG_DIR"
        echo "You can edit and rerun with: ./etc/test-cli.sh $DEBUG_DIR/$(basename $FEATURE_PATH)"
    fi
fi

# Update CSV if requested
if [ "$UPDATE_CSV" = true ] && [ -f "$CSV_FILE" ]; then
    TODAY=$(date +%Y-%m-%d)
    FEATURE_NAME=$(basename "$FEATURE_PATH")

    # Find and update the matching row
    # Look for the feature file in the CSV
    if grep -q ",$FEATURE_NAME," "$CSV_FILE"; then
        # Use sed to update status and date
        sed -i '' "s/\(,$FEATURE_NAME,[^,]*,[^,]*,[^,]*,[^,]*,\)pending\(,[^,]*,[^,]*,\)/\1$RESULT\2$TODAY/" "$CSV_FILE"
        echo ""
        echo "CSV updated: $FEATURE_NAME -> $RESULT"
    else
        echo ""
        echo "Warning: Could not find $FEATURE_NAME in CSV"
    fi
fi

echo ""
echo "Log file: $LOG_FILE"

exit $EXIT_CODE
