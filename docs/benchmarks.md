# Hypercurve benchmarks

Numbers recorded at each milestone. Machine: Apple Silicon laptop, JDK 21,
`-Dclojure.compiler.direct-linking=true`. Bundle sizes are shadow-cljs
`release` (advanced compilation), gzip -9. Run: `clojure -M:bench -m hypercurve.bench`.

## Phase 0 exit criteria (design §18)

| Criterion | Target | Result (2026-10-03, M8) | Met |
|---|---|---|---|
| Hello World bundle, gzip | ≤ 80 KB | 49.3 KB | yes |
| Interpreter overhead vs. closure product | ≤ 20% | 20% (1000-node chain, median) | yes, at the limit |

The closure baseline has the same per-node semantics as the evaluator
(three-state inputs, exceptions as values, subscriber and export checks).
Against a minimal closure chain without those semantics the overhead is 63%.
History: the first table interpreter was ~6x slower than closures; specialised
per-node step functions (built from the table at runtime, cached per ctor and
site), dependents as int arrays and a per-frame queued flag brought it down.

## Bundle size

| Date | Milestone | App | Raw | gzip |
|---|---|---|---|---|
| 2026-10-03 | M6 | examples/hello | 217.9 KB | 49.3 KB |
| 2026-10-03 | M8 | examples/todomvc | — | 53.1 KB |
| 2026-10-03 | M8 | examples/sqlite-table | — | 52.6 KB |
| 2026-10-03 | M25 | examples/hello (with SSR resume, reliable transport, router) | — | 64.1 KB |
| 2026-10-07 | M34 | examples/hello (with keyed loops, client cache slots, debounce) | — | 67.9 KB |

Each bundle includes cljs.core; no server code appears in any of them.

## Wire

| Date | Milestone | Scenario | Bytes | Target |
|---|---|---|---|---|
| 2026-10-03 | M5 | edit one field of one row, 50-row table | ≤ 20 | ≤ 20 |
| 2026-10-03 | M8 | edit one field of one row, 1000-row table | 17 | ≤ 20 |

## Rendering (headless DOM, both peers in one JVM, binary codec)

| Date | Milestone | Scenario | Time |
|---|---|---|---|
| 2026-10-03 | M8 | create 1000 rows | 107 ms |
| 2026-10-03 | M8 | update 1 row of 1000 | 1.3 ms |

Server frames for the 1000-row table: 1. Rows contain no server code, so the
server does not instantiate them (demand-driven, design §8.6 item 8).

## Server memory per session (design §8.6)

1000 sessions, each rendering the same 1000-row table:

| Variant | Bytes per session |
|---|---|
| `r/shared` (one value, per-session cursor, encode once) | ~4 KB |
| per-session query (fresh rows per session) | ~80 KB |

2026-10-08: what is derived from a program table alone (step plans,
dependents arrays, `server-free?`) is now kept on the table, once per
process; before, every session derived it for every component it met. In
Hypercanvas a canvas session went from 83 KB to 48 KB private.

## Resume snapshots

2026-10-08: blobs (snapshots, cached values) write a repeated collection or
long string once and refer back to it (the same map passed to a thousand
child frames was written a thousand times). With viewport culling in the app,
the Hypercanvas page for a 1000-element canvas went from 5.17 MB to 619 KB.
The encodability check runs once per shared value, and `tree-version` is
cached (that page's server time 587 → 208 ms).

## Scenarios (design §16), M24

| Scenario | Measure | Result |
|---|---|---|
| 100,000-row table, virtual window | bytes per scroll jump (server → client) | 827 |
| Whiteboard, 1000 shapes | bytes to move one shape | 18 |
| Streaming series, 100,000 points, 1 s buckets | append one point (incremental) | 0.002 ms |
| same | full recomputation | 32.9 ms |
| Notebook | compile + load a cell at run time | 0.7 ms |

## js-framework-benchmark style ratios (M25)

Hypercurve vs direct manipulation of the same headless DOM tree; CI fails when a
ratio exceeds 1.5x `bench/baseline.edn`. The direct baseline does only the
minimal DOM work, so these ratios are the framework's overhead factor.

| Operation | Ratio |
|---|---|
| create 1000 rows | 26.6 |
| update every 10th row | 20.1 |
| select a row (hypercurve.select, O(1)) | 56.2 |
| swap two rows | 18.6 |
| clear 1000 rows | 15.9 |

Before M25 fixes: select 2944 (every row re-read the selection), swap 3400
(full reorder walk).
