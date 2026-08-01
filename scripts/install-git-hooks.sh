#!/bin/bash
# One-time setup: points git at the repo's tracked hooks directory so
# .githooks/pre-push runs local CI before every push.
set -e

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

chmod +x .githooks/pre-push scripts/local-ci.sh scripts/local-deploy.sh
git config core.hooksPath .githooks

echo "Installed. scripts/local-ci.sh will now run automatically before every 'git push'."
echo "Bypass once with 'git push --no-verify' if you really need to."
