# Curve benchmarks

Numbers recorded at each milestone. Machine: Apple Silicon laptop, JDK 21.
Bundle sizes are shadow-cljs `release` (advanced compilation), gzip -9.

## Bundle size

| Date | Milestone | App | Raw | gzip | Budget |
|---|---|---|---|---|---|
| 2026-10-03 | M6 | examples/hello (Curve runtime + cljs.core + app) | 217.9 KB | 49.3 KB | Hello World ≤ 80 KB |

## Wire

| Date | Milestone | Scenario | Bytes | Target |
|---|---|---|---|---|
| 2026-10-03 | M5 | edit one field of one row in a 50-row table (server → client payload) | ≤ 20 | ≤ 20 |
