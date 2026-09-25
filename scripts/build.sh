#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./scripts/mod-gradle.sh setupCIWorkspace build
mkdir -p bin
(cd companion && go vet ./... && go build -trimpath -o ../bin/companion ./cmd/companion)
python3 -m unittest discover -s scripts -p 'test_*.py'
./bin/companion doctor
