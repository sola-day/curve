# Curve

A full-stack reactive Clojure web framework. One reactive program spans client and
server; the compiler infers the network boundary per expression and ships only diffs.

Status: pre-alpha, phase 0 prototype. Design: [docs/design.md](docs/design.md).
Plan: [docs/milestones.md](docs/milestones.md).

Clean-room implementation, MIT licensed. Contributors must not copy or adapt code
from Electric Clojure (BSL); see design §0.1.

## Development

```
clojure -M:test        # JVM test suite
```
