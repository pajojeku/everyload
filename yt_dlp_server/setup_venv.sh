#!/usr/bin/env bash
set -euo pipefail

# Creates a local virtual environment in .venv and installs requirements
PYTHON=${PYTHON:-python3}

if ! command -v "$PYTHON" >/dev/null 2>&1; then
  echo "Python not found: $PYTHON" >&2
  exit 1
fi

echo "Creating virtual environment in .venv using $PYTHON..."
$PYTHON -m venv .venv
echo "Activating and installing requirements..."
. .venv/bin/activate
pip install --upgrade pip
if [ -f requirements.txt ]; then
  pip install -r requirements.txt
else
  echo "No requirements.txt found; skipping pip install"
fi

echo "Done. Activate the venv with: source .venv/bin/activate"
