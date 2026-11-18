#!/usr/bin/env bash
# Build first with bench/manual/build.sh; this entry point never patches Cargo.
set -euo pipefail
exec python3 -I "$(dirname "$0")/nightly.py" "$@"
