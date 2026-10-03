# Deploying Curve

Curve is a stateful server: each browser tab has a session in a server JVM.

## Process

- Run on JDK 21+ (virtual threads for `r/offload`). Use
  `-Dclojure.compiler.direct-linking=true`.
- Serve the websocket endpoint (`curve.server/websocket-handler`) at `/curve`
  and, for forms without JS, `curve.server/action-handler` at `/curve/action`.
- Render pages with `curve.ssr/render` (or `render-stream`) and
  `curve.ssr/page`; static pages with `curve.static/build!`.

## Load balancing

- Sessions live in one JVM: use sticky routing (a cookie) so a tab's
  reconnects reach the same node within the grace period (default 30 s).
- Shared values (`r/shared`) and data-source subscriptions are per JVM. With
  several nodes, each subscribes to its sources once; feed change events to
  every node (e.g. a Postgres CDC consumer broadcasting table names via Redis
  to `curve.source/changed!`).

## Rolling deploys

1. Start the new version beside the old one.
2. On the old nodes call `curve.session/migrate!` for each session: clients
   reconnect (to a new node) and keep their local state.
3. Clients loaded with the old code that reach a node with a different program
   structure park local state in sessionStorage and reload once.

## Capacity

- Watch `curve.session/metrics-snapshot`: sessions, bytes, errors, budget
  trips, degradations, hibernations, shared instances.
- Set budgets per session (`:budget` in `session/start!` options; defaults in
  `curve.session/default-budget`).
- Enable `:hibernate-ms` for apps with many background tabs.
- Share hot queries with `r/shared`: 1000 sessions on one 1000-row table cost
  ~4 KB each instead of ~80 KB (docs/benchmarks.md).
- Leave websocket permessage-deflate off where your server allows it; the
  binary protocol is already compact (design §8.6 item 7).

## Security checklist

- Compile with the taint analysis on (always on for the JVM build) and review
  `curve-info.edn` (`curve.compiler/write-info!`) in code review.
- Derive the root args (the user) from the authenticated request in
  `:args-fn`, never from client input.
- Add `^{:validate pred}` to server fns called from the client.
