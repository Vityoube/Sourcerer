#!/usr/bin/env bash
# End-to-end smoke test against a running instance: API + real `git` over HTTP.
# usage: scripts/smoke.sh [base-url]
set -euo pipefail
export GIT_TERMINAL_PROMPT=0
BASE=${1:-http://localhost:8080}
SUFFIX=$RANDOM
ALICE="alice$SUFFIX"; BOB="bob$SUFFIX"
PY=$(command -v python3 || command -v python)
json() { "$PY" -c "import sys,json; d=json.load(sys.stdin); print(eval(sys.argv[1]))" "$1"; }
req() { # method path [body] [token]
  local m=$1 p=$2 b=${3:-} t=${4:-}
  curl -s -X "$m" "$BASE$p" -H 'Content-Type: application/json' ${t:+-H "Authorization: Bearer $t"} ${b:+-d "$b"}
}
ok() { echo "  ok: $1"; }
fail() { echo "  FAIL: $1"; exit 1; }
expect() { [[ "$2" == *"$3"* ]] && ok "$1" || fail "$1 (got: $2)"; }

echo "== health";  expect health "$(req GET /api/health)" '"status":"ok"'
echo "== auth"
req POST /api/auth/register "{\"username\":\"$ALICE\",\"email\":\"$ALICE@x.io\",\"password\":\"password-1\"}" >/dev/null
req POST /api/auth/register "{\"username\":\"$BOB\",\"email\":\"$BOB@x.io\",\"password\":\"password-2\"}" >/dev/null
AT=$(req POST /api/auth/login "{\"username\":\"$ALICE\",\"password\":\"password-1\"}" | json "d['token']")
BT=$(req POST /api/auth/login "{\"username\":\"$BOB\",\"password\":\"password-2\"}" | json "d['token']")
expect me "$(req GET /api/user '' "$AT")" "\"username\":\"$ALICE\""
expect "bad login rejected" "$(req POST /api/auth/login "{\"username\":\"$ALICE\",\"password\":\"nope\"}")" 'invalid credentials'

echo "== create repository + details"
expect create "$(req POST /api/repos '{"name":"demo.js","description":"hello","initReadme":true}' "$AT")" '"name":"demo.js"'
expect "create needs auth" "$(req POST /api/repos '{"name":"x"}')" 'authentication required'
D=$(req GET "/api/repos/$ALICE/demo.js")
expect details "$D" '"defaultBranch":"main"'
expect "details: head commit" "$D" 'Initial commit'

echo "== browse files"
expect tree "$(req GET "/api/repos/$ALICE/demo.js/tree")" 'README.md'
expect blob "$(req GET "/api/repos/$ALICE/demo.js/blob?path=README.md")" '# demo.js'
expect "raw" "$(curl -s "$BASE/api/repos/$ALICE/demo.js/raw?path=README.md")" '# demo.js'
ETAG=$(curl -si "$BASE/api/repos/$ALICE/demo.js/tree" | tr -d '\r' | awk -F': ' 'tolower($1)=="etag"{print $2}')
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "If-None-Match: $ETAG" "$BASE/api/repos/$ALICE/demo.js/tree")
[[ "$CODE" == 304 ]] && ok "etag -> 304" || fail "etag expected 304 got $CODE"
expect "missing path -> 404" "$(req GET "/api/repos/$ALICE/demo.js/blob?path=nope")" 'path not found'
expect "traversal rejected" "$(req GET "/api/repos/$ALICE/demo.js/blob?path=../x")" 'invalid path'
req POST /api/repos '{"name":"hidden","private":true}' "$AT" >/dev/null
expect "private repo hidden from others" "$(req GET "/api/repos/$ALICE/hidden" '' "$BT")" 'repository not found'

echo "== git over HTTP (clone, branch, push)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
PAT=$(req POST /api/auth/token '{"days":1}' "$AT" | json "d['token']")
git -c credential.helper= clone -q "$BASE/git/$ALICE/demo.js.git" "$W/c" && ok "anonymous clone of public repo"
cd "$W/c"; git config user.email a@x.io; git config user.name Alice
git checkout -q -b feature; echo "hi" > a.txt; git add a.txt; git commit -q -m "add a"
git -c credential.helper= push -q "http://$ALICE:$PAT@${BASE#http://}/git/$ALICE/demo.js.git" feature 2>&1 && ok "push with access token"
if git -c credential.helper= push -q "$BASE/git/$ALICE/demo.js.git" feature:other 2>/dev/null </dev/null; then fail "anonymous push must fail"; else ok "anonymous push rejected"; fi
cd - >/dev/null
expect "branches" "$(req GET "/api/repos/$ALICE/demo.js/branches")" '"name":"feature"'
expect "pushed file visible" "$(req GET "/api/repos/$ALICE/demo.js/tree?ref=feature")" 'a.txt'

echo "== issues"
expect "issue" "$(req POST "/api/repos/$ALICE/demo.js/issues" '{"title":"Broken","body":"it is"}' "$BT")" '"number":1'
expect "comment" "$(req POST "/api/repos/$ALICE/demo.js/issues/1/comments" '{"body":"on it"}' "$AT")" '"body":"on it"'
expect "close" "$(req PATCH "/api/repos/$ALICE/demo.js/issues/1" '{"state":"closed"}' "$AT")" '"state":"closed"'

echo "== merge requests"
MR=$(req POST "/api/repos/$ALICE/demo.js/merge-requests" '{"title":"Add a","sourceBranch":"feature","targetBranch":"main"}' "$BT")
expect "create MR" "$MR" '"number":1'
expect "MR mergeable" "$(req GET "/api/repos/$ALICE/demo.js/merge-requests/1?check=true")" '"success":true'
expect "MR diff" "$(req GET "/api/repos/$ALICE/demo.js/merge-requests/1/diff")" '+hi'
expect "non-owner cannot merge" "$(req POST "/api/repos/$ALICE/demo.js/merge-requests/1/merge" '' "$BT")" 'only the owner'
expect "owner merges" "$(req POST "/api/repos/$ALICE/demo.js/merge-requests/1/merge" '' "$AT")" '"state":"merged"'
sleep 0.2
expect "merged file now on main" "$(req GET "/api/repos/$ALICE/demo.js/tree?ref=main")" 'a.txt'

echo "ALL OK"
