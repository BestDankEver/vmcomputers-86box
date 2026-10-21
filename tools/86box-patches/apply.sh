#!/usr/bin/env bash
# Applies the mod's patches to an 86Box checkout (run from inside it).
# A patch that no longer applies is skipped with a warning instead of failing
# the build: the mod checks at runtime which features the 86Box it got has.
set -u
command -v git > /dev/null || { echo "::error::git is needed to apply the patches"; exit 1; }
here=$(dirname "$0")
for p in "$here"/*.patch; do
    [ -e "$p" ] || continue
    if git apply --check "$p" 2>/dev/null; then
        git apply "$p" && echo "Applied $(basename "$p")"
    else
        echo "::warning::$(basename "$p") no longer applies to this 86Box; building without it."
        git apply --check "$p" || true
    fi
done
