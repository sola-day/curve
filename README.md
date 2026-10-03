# Curve

**One reactive Clojure program for the browser and the server.**
You write a single function tree. The compiler decides, expression by
expression, what runs on the server and what runs in the browser, and only
structural diffs of changed values travel between them.

```clojure
(ns app.chat
  (:require [curve.core :as r]))

#?(:clj (defonce !messages (atom [])))

(r/defn Chat []
  (let [!draft (atom "")                                     ; browser state
        draft  (r/watch !draft)
        post!  (r/server (fn [text] (swap! !messages conj text)))]   ; server fn
    [:main
     [:ul (r/for [m (r/server (r/watch !messages))] [:li m])]    ; live server data
     [:input {:value draft :on-input (fn [e] (reset! !draft (r/event-value e)))}]
     [:button {:on-click (fn [_] (post! draft) (reset! !draft ""))} "send"]]))
```

There is no API layer, no fetch code, and no cache to invalidate. When any
user posts, every open tab updates, and only the new message crosses the
wire.

> **Status:** pre-1.0. Every milestone of the [plan](docs/milestones.md) is
> implemented, tested on the JVM and checked in a real browser, but the API
> may still change. MIT licensed.

---

## Contents

- [Why Curve](#why-curve)
- [Getting started](#getting-started)
- [The language](#the-language)
- [Server, sessions and data](#server-sessions-and-data)
- [Rendering on the server](#rendering-on-the-server)
- [Security](#security)
- [Testing and tooling](#testing-and-tooling)
- [Performance](#performance)
- [How it works](#how-it-works)
- [Limitations](#limitations)
- [Development](#development)
- [License and clean-room policy](#license-and-clean-room-policy)

---

## Why Curve

Curve builds on the idea behind Electric Clojure that the network boundary
can be inferred by a compiler. It then adds what a production app needs
around that idea:

| | |
|---|---|
| **Small updates** | Values cross as structural deltas in a binary protocol. Editing one field of one row in a 1000-row table sends **17 bytes**. |
| **Cheap shared data** | `r/shared` computes a query once per process and encodes each change once for every session. 1000 sessions viewing one 1000-row table cost **~4 KB each** instead of ~80 KB. |
| **Server rendering that resumes** | Pages render on the server with real data. The browser continues the *same* session from a snapshot, so no query runs twice. Pages that need no JavaScript ship none. |
| **Secrets stay on the server** | A compile-time taint analysis rejects code that would send a secret value (a `^:secret` var, or a schema field marked secret) to the browser. |
| **Resilient sessions** | Reconnects resend exactly what was lost. Idle sessions hibernate. Deploys migrate clients while keeping their local state. |
| **Fast enough** | The program is data interpreted at runtime, at 20% over closure-compiled code. Hello World is 64 KB gzip including cljs.core. |

## Getting started

### Install

Curve is not on Clojars yet. Depend on it from git:

```clojure
;; deps.edn
{:deps {io.github.sola-day/curve {:git/sha "53ddca3b8ea7ba25b7f36be73ce8f3702cd092e1"}
        ring/ring-jetty-adapter  {:mvn/version "1.15.3"}}
 :aliases
 {:cljs {:extra-deps {org.clojure/clojurescript {:mvn/version "1.11.132"}
                      thheller/shadow-cljs      {:mvn/version "2.28.23"}}}}}
```

### Three files

**The app** is a `.cljc` file, so both builds compile the same source:

```clojure
(ns app.main
  (:require [curve.core :as r]))

#?(:clj (defonce !clicks (atom 0)))

(r/defn App []
  [:main
   [:p "Clicks: " (r/server (r/watch !clicks))]
   [:button {:on-click (r/server (fn [_] (swap! !clicks inc)))} "click"]])
```

**The server** renders the page and serves the websocket:

```clojure
(ns app.server
  (:require [app.main :as app]
            [curve.server :as cs]
            [curve.ssr :as ssr]
            [ring.adapter.jetty :as jetty]
            [ring.util.response :as resp]))

(def ws (cs/websocket-handler app/App))

(defn handler [req]
  (case (:uri req)
    "/curve" (ws req)
    "/"      (-> (resp/response (ssr/page (ssr/render app/App [] :url "/")
                                          {:title "My app" :script "/js/main.js"}))
                 (resp/content-type "text/html; charset=utf-8"))
    (or (resp/resource-response (:uri req) {:root "public"})
        (resp/not-found "not found"))))

(defn -main [] (jetty/run-jetty #'handler {:port 8080 :join? false}))
```

**The client** starts the app:

```clojure
(ns app.client
  (:require [app.main :as app]
            [curve.client :as client]))

(defn ^:export init [] (client/start! app/App))
```

```clojure
;; shadow-cljs.edn
{:builds {:app {:target :browser
                :output-dir "resources/public/js"
                :asset-path "/js"
                :modules {:main {:init-fn app.client/init}}}}}
```

```
clojure -M:cljs -m shadow.cljs.devtools.cli release app
clojure -M -m app.server
```

Server-only namespaces go behind reader conditionals, for example
`#?(:clj [app.db :as db])`. Code inside `r/server` is never emitted into the
browser bundle, so the browser never needs those namespaces.

## The language

`r/defn` defines a **reactive function**. Every expression in its body
recomputes when its inputs change. Calls to ordinary functions are just
calls. Hiccup in render position becomes a static template with dynamic
holes.

### Sites

| Form | Meaning |
|---|---|
| `(r/server expr)` | Evaluate on the server. The value is sent to the client only if the client reads it. |
| `(r/client expr)` | Evaluate in the browser. |
| *(no annotation)* | Runs on the caller's site. A whole function can default with `(r/defn ^:server F ...)`. |
| `(r/server (fn [x] ...))` | A server closure. The client receives a callable proxy that returns a watchable result. |

### Control flow and state

| Form | Purpose |
|---|---|
| `if` `when` `case` `cond` `let` | Ordinary Clojure. A branch switch mounts or unmounts a subtree, and everything inside it is cleaned up automatically. |
| `(r/for [x coll :by :id] body)` | Keyed list. `:recycle true` keys by position, which suits virtual windows. |
| `(r/watch ref)` | The current value of any atom or watchable, tracked. |
| `(r/effect body)` | A side effect that re-runs when its inputs change. A returned fn is its cleanup. |
| `(r/binding [*v* x] body)` | Dynamic scope that follows reactive calls across sites. |
| `(r/call F args)` | Call a reactive fn held in a value, for example one loaded lazily. |
| `(r/flow (fn [emit!] ... cleanup))` | Adapt any push source. |
| `(r/offload body)` | Run blocking server code on a virtual thread. The value is pending until done. |

### Pending, errors and loading

```clojure
(r/suspense [:p "Loading…"]
  (r/boundary (fn [err retry] [:div.error (ex-message err) [:button {:on-click (fn [_] (retry))} "retry"]])
    (Products)))

(r/defer {:when :visible :margin 200 :placeholder [:p "…"]}   ; or :idle / :interaction
  (Comments id))                                             ; mounts and queries when scrolled into view
```

Errors from the server keep their message and record where they happened
(`(ex-data e)` → `{:at "server app.main/Products:12"}`).

### Writes and optimistic updates

```clojure
(let [!view (r/projection (r/server (r/watch !todos)))      ; client view of a server value
      add!  (r/mutation (fn [t] (swap! !todos conj t))      ; server fn
                        {:optimistic [!view #(conj % "…")]})] ; shown at once, replaced by the server's answer
  [:ul (r/for [t (r/watch !view)] [:li t])])
```

`(r/mutation f)` also unwraps DOM events, so the server fn receives the
input's value.

### Routing

```clojure
(def routes [["/" :home] ["/products/:id" :product]])

(r/defn App []
  (let [{:keys [page params]} (r/route routes)]   ; reactive: computed in the browser, readable on the server
    (case page
      :home    [:a {:href (router/href routes :product {:id 3})} "a product"]   ; client-side navigation
      :product (Product (:id params))
      [:h1 "not found"])))
```

### Templates

```clojure
[:div.card#main {:class (when selected "on")      ; static and dynamic attributes
                 :style {:color color}
                 :on-click handler                 ; delegated events
                 :& extra-attrs}                   ; spread a map of attributes and handlers
 "Hello " name "!"]                                ; static text merged with dynamic text
(r/foreign chart/mount {:points data})             ; a JS component with reactive props
```

### Batteries

| Namespace | What it gives you |
|---|---|
| `curve.forms` | Form state, field binding, client validators, and submission to a server fn whose returned errors show next to the fields. |
| `curve.virtual` | Virtual scrolling with fixed or measured variable row heights. Only rows in view are rendered or sent. |
| `curve.select` | O(1) selection, so only the rows whose "selected?" changes update. |
| `curve.canvas` | Render the same hiccup to a `<canvas>` scene graph, with hit-tested events. |
| `curve.agg` | Time-bucket aggregation that, for appended data, costs O(new points). |
| `curve.presence`, `curve.shared/shared-atom` | Collaboration: who is here, and state shared by all sessions. |
| `curve.local` | Run the server site in a Web Worker: same program, same protocol, no network. |
| `curve.dynamic` | Compile reactive code at run time (notebooks, plugins) into data. An allow-list interpreter runs it, never `eval`. |

## Server, sessions and data

Each browser tab has a **session**. A session is a task, not a thread: it is
a serial queue on a per-core worker pool. Everything that touches it is
posted to that queue, and it sends one batch per turn.

```clojure
(cs/websocket-handler app/App
  :args-fn      (fn [req] [(current-user req)])   ; root args come from the request, never from the client
  :grace-ms     30000                             ; keep a session while a tab reconnects
  :hibernate-ms 60000)                            ; snapshot and unload idle sessions
```

- **Shared values.** `(r/shared key body)` computes body once per process for
  each key and its captured values. Each session keeps only a version cursor
  into a log of recent values. A change is encoded once and the same bytes
  go to every session.
- **Data sources.** `curve.source` provides live queries that subscribe only
  while something watches them. Adapters:

  | Adapter | Cost per write |
  |---|---|
  | `mem-source`, `poll-ref`, `jdbc-source` | One re-query per write. |
  | `curve.source.sqlite` | Uses the update hook. Keyed queries re-read only the changed row. |
  | `table-source` + `changed!` | Re-runs only queries reading the changed tables. Fits CDC, Redis or Kafka events. |
  | `curve.source.postgres` | Feeds wal2json logical replication into table events. |
  | `curve.source.datomic` | Uses the transaction report queue, filtered by the attributes each query reads. |

  `curve.batch/loader` merges concurrent loads from many sessions into one
  batch query, in the style of DataLoader.
- **Budgets.** Each session has limits on nodes, bytes per second and turn
  time. Over the bandwidth limit a session first slows down and coalesces;
  if the excess persists, the session is closed, never the process.
- **Resilience.** Frames are numbered and acknowledged. After a dropped
  connection, both sides resend what the other missed, applied exactly once.
  Sessions hibernate when idle and wake on the next message.
  `session/migrate!` moves clients to another node with their local state.
  A client running old code reloads once and keeps its local state.
- **Metrics.** `(curve.session/metrics-snapshot)`

See [docs/deploy.md](docs/deploy.md) for load balancing, rolling deploys and
a capacity checklist.

## Rendering on the server

```clojure
(ssr/render App [] :url "/products")          ; => {:html :state :token :tier}
(ssr/render-stream App [] {:title "…" :script "/js/main.js"})   ; lazy seq of HTML chunks
(curve.static/build! {:ctor App :urls ["/" "/about"] :out "dist" :script "/js/main.js"})
```

- **Resume.** The server render runs a real session. The browser restores a
  snapshot and attaches to that same session, so data is not fetched again.
- **Streaming.** The page is sent with suspense fallbacks first. Each region
  is sent as it resolves.
- **Tiers.** Each page is classified as `:html`, `:client` or `:live`.
  `:html` pages have no client logic and get **no script at all**. `:client`
  pages run without a server. `:live` pages need one.
- **No-JS forms.** A form whose submit handler is a server fn renders with
  `method=post`, so it works without JavaScript.
- **Lazy connection.** `(client/start! App :connect :lazy)` opens the
  websocket only when the page first needs the server.
- **Build-time constants.** `(r/static expr)` evaluates expr at build time.

## Security

The network boundary is inferred, so Curve makes it visible and checks it:

- **Taint analysis.** Mark sources with `(def ^:secret api-key ...)`,
  `(let [^:secret t ...])` or a schema key such as
  `[:map [:password_hash {:secret true} :string]]` used via
  `^{:schema User}`. Any path that brings a secret to the browser fails
  compilation, with the location and a suggested fix. Access by literal key
  (`(:name user)`), `select-keys` and `dissoc` are tracked precisely; anything
  else taints the whole value.
  `(r/declassify expr "reason")` is the only escape hatch, and it is recorded.
- **Boundary report.** `(curve.compiler/write-info! "curve-info.edn")` lists
  every value that crosses the network, for code review.
- **Server-side checks.** The server only accepts values for client-owned
  cells in mounted frames. It only runs closures it handed out.
  `^{:validate pred}` on a server fn or a client value is checked on entry.
  The decoder accepts a fixed set of types and caps message size.
- **State changes belong in handlers.** A `swap!` or `reset!` inside a
  reactive value is a compile error. Use an event handler or `r/effect`.

## Testing and tooling

```clojure
(require '[curve.test :as ct])

(let [p (-> (ct/pair) (ct/mount! App) ct/flush!)]   ; client + server in one JVM, real binary protocol
  (reset! !clicks 1)
  (ct/flush! p)
  (is (<= (ct/bytes-sent p :s->c) 20))              ; assert on what crossed the wire
  (is (= #{[:s->c 1]} (ct/slots-changed p))))
```

- **Headless DOM.** `curve.headless` provides `query`, `fire!`, `input!` and
  `html` for UI tests without a browser.
- **Inspection.** `curve.dev/inspect` returns the live frame tree as data.
  After `curve.dev/trace!`, `(curve.dev/why frame node)` explains why a value
  changed.
- **Replay.** `curve.dev/replay` replays a message log to any point.
  `curve.dev/log->test` turns a session into a regression test.
  `curve.dev/wire-stats` shows what uses bandwidth.
- **Hot reload.** Edits swap in place, and local state is kept by stable node
  id. Use `curve.client/run!` with `reload!`, plus the `curve.dev/reload-clj`
  shadow hook.
- **Linting.** A clj-kondo config ships in `resources/clj-kondo.exports`.

## Performance

Measured on an Apple Silicon laptop with JDK 21. Details and history are in
[docs/benchmarks.md](docs/benchmarks.md).

| Measure | Result |
|---|---|
| Hello World bundle | 64 KB gzip (budget 80 KB) |
| Interpreter overhead vs closure-compiled code with the same semantics | 20% |
| Edit one field, 1000-row table | 17 bytes |
| 1000 sessions on one 1000-row table | ~4 KB/session shared vs ~80 KB per-session queries |
| 100,000-row virtual table, one scroll jump | 827 bytes |
| Move one of 1000 whiteboard shapes | 18 bytes |
| Append to a 100,000-point series (bucketed) | 0.002 ms vs 33 ms full recompute |

CI also tracks js-framework-benchmark style operations as ratios against
direct DOM manipulation, so the check holds on any machine.

## How it works

```
 source (r/defn …)
   │  compile (macro, on the JVM): data-flow graph, site inference, template
   │  extraction, taint analysis, stable node ids
   ▼
 program table: data — nodes, edges, sites, templates
   ├─► browser build: client and shared nodes      ─┐
   └─► JVM build: everything (server, SSR, tests)  ─┤ same table, two peers
                                                    ▼
 each peer runs the same table: frames = arrays of values + dirty bits,
 propagated in topological order; it computes its own nodes and receives the
 others' as structural deltas (binary, keyword- and shape-interned)
```

- A frame's identity is its path from the root (parent, node, key), so both
  peers agree on structure without coordinating.
- The server does not instantiate subtrees it neither computes nor reads.
  This is demand-driven evaluation.
- On first use, each table is turned into one specialised step function per
  node and cached. That is how interpretation stays within 20% of closures.

The full design, including trade-offs, is in [docs/design.md](docs/design.md)
(in Chinese).

## Limitations

- Pre-1.0: the API can change, and Curve is not published to Clojars yet.
- The server is stateful by design: there is no edge or serverless
  deployment. A session needs sticky routing within a node.
- Hydration re-renders from the snapshot in the same task, instead of
  claiming the server's DOM. An input focused before JS loads loses focus.
- The Datomic adapter is tested against an in-memory stand-in. The Postgres
  replication stream has not been run against a live database here. Its
  wal2json parsing is tested.
- Platform-specific code inside reactive fns (`#?`) works, but those nodes get
  different stable ids per build, so their state is recomputed on resume
  instead of restored.

## Development

```
clojure -M:test                       # JVM test suite (104 tests)
scripts/ci.sh                         # tests, release builds, bundle budget, perf ratios
RUN_BROWSER=1 scripts/ci.sh           # + headless-Chrome smoke tests of every example
clojure -M:bench -m curve.bench       # phase-0 benchmarks
clojure -M:bench -m curve.jfb         # js-framework-benchmark style ratios
```

| Example | Shows |
|---|---|
| `examples/hello` | Server rendering with resume, server clicks, client state, a server fn called with a client value. |
| `examples/todomvc` | TodoMVC with client-only state. |
| `examples/sqlite-table` | Login, a live shared SQLite query, and editing, adding and deleting rows. |
| `examples/local` | The server site running in a Web Worker. |
| `examples/showcase` | A 100,000-row variable-height virtual list and a section deferred until visible. |

Docs: [design](docs/design.md) · [milestones and deviations from the design](docs/milestones.md) · [benchmarks](docs/benchmarks.md) · [deploying](docs/deploy.md)

## License and clean-room policy

MIT. See [LICENSE](LICENSE).

Curve is a clean-room implementation. It is inspired by the published ideas
of Electric Clojure, React, Svelte, SolidJS, Meteor and Phoenix LiveView,
but contains no code copied or adapted from Electric Clojure, which is
licensed under the Business Source License. Contributors must not read
Electric's source while working on Curve's compiler or runtime.
