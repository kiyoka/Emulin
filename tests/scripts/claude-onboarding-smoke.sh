#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/claude-onboarding-smoke.sh
#
#  issue #876/#935: claude の初回 onboarding を済み扱いにする判定 (Egress.
#  claudeCredentialConfigured) が、現行のブラウザ OAuth 方式 (CLAUDE_ACCESS_TOKEN)
#  でも発動することを確かめる。
#
#  守る実害: この判定が古い方式 (CLAUDE_CODE_OAUTH_TOKEN) と Console API key
#  (ANTHROPIC_API_KEY) しか見ていないと、#935 以降の主経路 (ブラウザ OAuth) を使う
#  誰にとっても onboarding seed が発動せず、guest 内の claude 初回起動が
#  対話なら危険なログイン選択に、非対話なら永久ハングになる (実機 2026-08-30 で発覚)。
#
#  ネットワークも guest も要らない (純 Java)。
#  終了コード: 0=PASS / 1=FAIL / 2=SKIP (未 build)
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes

if [ ! -f "$CLASSES/emulin/ClaudeOnboardingSmoke.class" ]; then
    echo "SKIP claude-onboarding-smoke : not built ($CLASSES/emulin/ClaudeOnboardingSmoke.class)"
    echo "  run 'mvn compile' first"
    exit 2
fi

OUT=$(java -Xmx256m -cp "$CLASSES" emulin.ClaudeOnboardingSmoke </dev/null 2>&1); RC=$?
printf '%s\n' "$OUT" | sed 's/^/  /'

if [ "$RC" = 0 ] && printf '%s' "$OUT" | grep -q 'ClaudeOnboarding smoke OK'; then
    echo "PASS    claude-onboarding-smoke (onboarding seed が現行の OAuth 方式でも発動する #876/#935)"
    exit 0
fi
echo "FAIL    claude-onboarding-smoke (exit=$RC)"
exit 1
