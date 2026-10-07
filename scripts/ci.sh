#!/usr/bin/env bash
# Hypercurve CI: tests, release builds, bundle budget, performance ratios,
# and (with RUN_BROWSER=1 and Chrome available) real-browser smoke tests.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== JVM tests"
clojure -M:test

echo "== release builds"
clojure -M:cljs:hello:todomvc:sqlite:local:showcase -m shadow.cljs.devtools.cli release hello todomvc sqlite local showcase

echo "== bundle budget (design §12.2: Hello World <= 80 KB gzip)"
size=$(gzip -9 -c examples/hello/resources/public/js/main.js | wc -c | tr -d ' ')
echo "hello: ${size} bytes gzip"
if [ "$size" -gt 81920 ]; then echo "over budget"; exit 1; fi
if grep -q 'products-ref\|password_hash' examples/sqlite-table/resources/public/js/main.js; then
  echo "server code leaked into the browser bundle"; exit 1
fi

echo "== performance ratios (design §17.6)"
clojure -M:bench -m hypercurve.jfb --check bench/baseline.edn

if [ "${RUN_BROWSER:-0}" = "1" ]; then
  echo "== browser smoke tests"
  tmp=$(mktemp -d)
  clojure -M:server:hello -e "(require 'hello.server) (hello.server/-main \"8091\") @(promise)" > "$tmp/hello.log" 2>&1 &
  clojure -M:server:todomvc -e "(require 'todomvc.server) (todomvc.server/-main \"8092\") @(promise)" > "$tmp/todo.log" 2>&1 &
  clojure -M:server:sqlite -e "(require 'sqlite-table.server) (sqlite-table.server/-main \"8093\" \"$tmp/smoke.db\") @(promise)" > "$tmp/sqlite.log" 2>&1 &
  clojure -M:server:showcase -e "(require 'showcase.server) (showcase.server/-main \"8095\") @(promise)" > "$tmp/showcase.log" 2>&1 &
  (cd examples/local/resources/public && python3 -m http.server 8094 > /dev/null 2>&1 &)
  trap 'pkill -f hello.server; pkill -f todomvc.server; pkill -f sqlite-table.server; pkill -f showcase.server; pkill -f "http.server 8094"' EXIT
  for i in $(seq 1 120); do
    curl -s localhost:8091/ >/dev/null && curl -s localhost:8092/ >/dev/null && curl -s localhost:8093/ >/dev/null && curl -s localhost:8095/ >/dev/null && break
    sleep 1
  done
  node scripts/browser-smoke.mjs http://localhost:8091/
  node scripts/browser-smoke.mjs http://localhost:8092/ ./smoke-todomvc.mjs
  node scripts/browser-smoke.mjs http://localhost:8093/ ./smoke-sqlite.mjs
  node scripts/browser-smoke.mjs http://localhost:8094/ ./smoke-local.mjs
  node scripts/browser-smoke.mjs http://localhost:8095/ ./smoke-showcase.mjs
fi
echo "== all checks passed"
