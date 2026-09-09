#!/usr/bin/env bash
# Put the hooks in this checkout's .git/hooks, which is not a thing a repository can do for
# itself and not a thing it should do behind anyone's back.
set -euo pipefail
root=$(git rev-parse --show-toplevel)
for hook in "$root"/scripts/hooks/*; do
  name=$(basename "$hook")
  [[ "$name" == install.sh ]] && continue
  ln -sf "../../scripts/hooks/$name" "$root/.git/hooks/$name"
  echo "installed $name"
done
