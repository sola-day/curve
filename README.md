# Curve

A full-stack reactive Clojure web framework. One program spans browser and
server; the compiler decides, per expression, what runs where, and only
structural diffs cross the network.

```clojure
(r/defn App []
  (let [!draft (atom "")
        draft (r/watch !draft)
        post! (r/server (fn [text] (swap! !messages conj text)))]
    [:main
     [:input {:value draft :on-input (fn [e] (reset! !draft (r/event-value e)))}]
     [:button {:on-click (fn [_] (post! draft))} "post"]
     [:ul (r/for [m (r/server (r/watch !messages))] [:li m])]]))
```

Status: all milestones of the plan done (docs/milestones.md); pre-1.0.
Design: docs/design.md. Benchmarks: docs/benchmarks.md. Deploying: docs/deploy.md.

## What you get

- **Expression-level sites**: `r/server`, `r/client`, or inherit the caller's.
  Server closures are callable from the client; values sent as deltas
  (a field edit in a 1000-row table: 17 bytes).
- **Templates** extracted at compile time, keyed lists, error and suspense
  boundaries, effects, dynamic scope across sites, routing, forms, virtual
  scrolling, foreign JS components, canvas rendering.
- **Server**: one task per session on a per-core pool, `r/shared` values
  computed and encoded once for all sessions, budgets, hibernation,
  reconnect with exactly-once delivery, migration, data sources (SQLite
  update hook, polling, table events, Datomic, Postgres CDC), batching.
- **SSR** with resume (no query runs twice), streaming, static builds that ship
  no JS when a page needs none, forms that work without JS.
- **Security**: taint analysis of secrets with literal-key precision, a
  boundary report, server-side validation, slot authorization.
- **Tools**: inspect, "why did this update", replay, tests from logs, hot
  reload keeping local state, a two-peer test harness with wire assertions.

## Development

```
clojure -M:test                         # JVM test suite
scripts/ci.sh                           # tests, builds, budgets, ratios
RUN_BROWSER=1 scripts/ci.sh             # plus real-browser smoke tests
clojure -M:bench -m curve.bench         # phase-0 benchmarks
```

Examples: `examples/hello`, `examples/todomvc`, `examples/sqlite-table`,
`examples/local` (server site in a Web Worker).

Clean-room implementation, MIT licensed. Contributors must not copy or adapt
code from Electric Clojure (BSL); see design §0.1.
