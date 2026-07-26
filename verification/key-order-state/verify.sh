#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
KEY_JAVA="${KEY_JAVA:-java}"
KEY_JAR="${KEY_JAR:-}"
KEY_HOME_DIR="$SCRIPT_DIR/target/key-home"
LOG_DIR="$SCRIPT_DIR/target/logs"

if [[ -z "$KEY_JAR" ]]; then
    echo "KEY_JAR must point to the KeY executable jar." >&2
    exit 2
fi

if [[ ! -f "$KEY_JAR" ]]; then
    echo "KeY executable jar not found: $KEY_JAR" >&2
    exit 2
fi

mkdir -p "$KEY_HOME_DIR" "$LOG_DIR"

proofs=(constructor apply-fill modify cancel)

for proof in "${proofs[@]}"; do
    proof_file="$SCRIPT_DIR/proofs/$proof.key"
    log_file="$LOG_DIR/$proof.log"

    echo "Verifying $proof..."
    if ! "$KEY_JAVA" \
        "-Dkey.home=$KEY_HOME_DIR" \
        -Dkey.disregardSettings=true \
        -jar "$KEY_JAR" \
        --auto \
        "$proof_file" >"$log_file" 2>&1; then
        tail -n 40 "$log_file" >&2
        exit 1
    fi

    # KeY 3.1.0-dev currently exits with zero even when auto mode leaves goals
    # open, so the textual proof result is part of the verification gate.
    if ! grep -Fq "Number of goals remaining open: 0" "$log_file" \
        || ! grep -Fq "Proved" "$log_file"; then
        tail -n 40 "$log_file" >&2
        exit 1
    fi

    echo "PASS: $proof"
done

echo "All ${#proofs[@]} KeY proof obligations passed."
