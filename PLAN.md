# Snap (Scala) — implementation plan

Scope: implement `SPEC.md` in Scala 2.13.6 under `./scala`, verified against the
language-neutral suite in `tests/` via `./verify --lang scala`. `./scala` is
currently empty, so unit 1 bootstraps the whole project.

Each unit below is a cohesive slice with a clear internal boundary (per
AGENTS.md: versions, text/diff and OT, repository validation and replay,
filesystem materialization, working-tree changes, HTTP, commands, CLI
dispatch). Work the units in order, commit after each one lands, and run the
acceptance suite (full or filtered) after every commit per AGENTS.md's
development workflow. Where a unit's behavior isn't fully observable through
the CLI yet, use local ScalaTest unit tests as a stopgap and confirm full
acceptance coverage once the commands that expose it exist.

Commands: `./verify --lang scala --filter <substr>` runs a subset by
file/test-name substring; `./verify --lang scala` runs everything.

---

## 1. Project scaffolding and CLI/config skeleton

Bootstrap `./scala`: `build.sbt` pinned to Scala 2.13.6, `sbt-assembly` for the
fat jar `run` expects (`target/scala-*/*-assembly-*.jar`), directory layout
(`src/main/scala/...`), and an `AGENTS.md` scaffold note mirroring `ts/AGENTS.md`.
Implement:

- `Main.scala` argument dispatch shell, stable exit codes (0/1/2), plain error
  line format `snap: <detail>` (§10).
- `SNAP_COLOR`/`NO_COLOR` validation (§7.11) — validate and select a
  presentation mode value up front (plain-only rendering for now; real ANSI
  output is unit 10), including the pre-execution `SNAP_COLOR must be auto,
  always, or never` error.
- `snap --version` (§7.10).
- `snap config [--global] contributor.id <id>` and the config read/write
  format (§8): local `.snap/config.json` vs global `$HOME/.snapconfig.json`,
  local-over-global precedence, missing/malformed/unknown-field handling.
- A minimal hand-rolled JSON reader/writer (not a third-party codec) that
  preserves object key order, rejects duplicate keys and non-integer numbers,
  and errors on unknown fields — every later unit depends on this for strict
  schema validation (§4.5, §4.1).

Verify: `./verify --lang scala --filter 03-configuration`, plus the `--version`
and `config` cases inside `14-cli-errors` / `24-cli-grammar-matrix`.

## 2. Version algebra (vector clocks)

Pure module, no I/O: contributor ID validation (§3.1), canonical version
syntax parsing/printing (§3.2), the four-way comparison and `join` (§3.3), and
Snap order (§3.4). No CLI command exposes this in isolation, so drive it with
local ScalaTest unit tests covering the comparison/join laws and canonical
syntax edge cases (empty version, duplicate IDs, leading zeros, overflow,
noncanonical ordering). Full acceptance coverage (`21-version-algebra`,
`19-version-boundaries`) lands once `diff`/`revert`/`merge` wire this module in
later units — track it as pending until then.

## 3. Repository/patch model and strict schema validation

The typed repository value (§4.1–§4.2), change variants (§4.3), and the
structural validation pipeline: exact schema, ID/path/message/edit validation,
patch sort order and one-value-per-dot, contiguous per-contributor revisions,
complete base closure, acyclicity, and dot-collision detection as corruption
(§4.5 steps 1–4, §3.5). Replay-dependent validation (§4.5 steps 5–6) is
deferred to unit 7. Text-token/edit-script structural rules (§4.4, without the
diff algorithm itself) belong here too, since they're schema-level.

Verify: `./verify --lang scala --filter 15-repository-validation`,
`--filter 16-dot-collision`, `--filter 23-strict-validation-matrix`,
`--filter 27-history-canonicality`.

## 4. Canonical text diff

The tokenizer (LF-retaining split) and the exact `D(i,j)` recurrence with its
deletion-on-tie rule and coalescing (§5). Keep this as a standalone pure
function (`diff(oldTokens, newTokens): EditScript`) so units 5, 6, and 7 can
all call it. Validate with local golden-case unit tests (repeated lines,
deletion ties, no-newline-at-end) mirroring `05-diff-goldens`; full CLI
coverage of that file lands in unit 6 once `snap diff` exists.

## 5. Working tree + `init`/`status`/`commit`/`log`

Working-tree scanning (regular files only, symlink/special-file rejection
without mutation, §2 and §10), clean/dirty comparison against the current
tree, and the four base commands:

- `snap init [path]` (§7.1)
- `snap status` (§7.3)
- `snap commit <message>` (§7.5) — diff current vs. working tree, choose
  `text`/`put`/`delete` per path, author one patch against the current
  frontier, atomic `repository.json` replace (§10)
- `snap log` (§7.4), including message escaping

Verify: `--filter 01-init`, `--filter 02-init-paths`,
`--filter 04-commit-status-log`, `--filter 06-binary-and-empty`,
`--filter 08-unsupported-entries`, and the init/status/commit/log slices of
`14-cli-errors` / `24-cli-grammar-matrix`.

## 6. `diff` and `revert`

Wire unit 2's version resolution ("known version", §4.1) and unit 4's diff
engine into:

- `snap diff` (§7.6) — no-arg working-vs-current form, `<old> <new>` form,
  `--repo` cross-repository form with dot-value corruption checks, unified-
  style text blocks, `/dev/null` headers, binary-file line, no-output-on-no-
  diff.
- `snap revert <version>` (§7.7) — clean-tree and known-version
  preconditions, additive patch authoring, `revert to <version>` message,
  already-current error.

Verify: `--filter 05-diff-goldens` (full), `--filter 07-revert`,
`--filter 19-version-boundaries`, `--filter 25-config-version-path-boundaries`.

## 7. Deterministic replay engine (materialization + OT + conflict rules)

The core algorithm, kept independent of any single CLI command since merge,
revert-target reconstruction, and validation step 6 all need it:

- Patch selection and canonical integration order (§6.1)
- Per-patch integration: namespace-conflict resolution first, then per-path
  evaluation against `B`/`C`/`T` (§6.2)
- Text OT transform against the aggregate context edit (§6.3)
- Path-level winner rules and warning generation (§6.4)
- Replay-dependent repository validation (§4.5 steps 5–6): every change
  against its materialized base, deterministic full replay of the frontier

This is implementation work with no dedicated CLI surface yet — cover it with
local unit tests against the spec's worked cases (pairwise OT, namespace
collisions, each path-level winner) as a stopgap; full acceptance coverage
(`22-ot-matrix`, `17-concurrent-creates`, `11-namespace-conflicts`,
`10-merge-conflicts`, `18-three-way-convergence`) completes once `merge` lands
in unit 8.

## 8. `snap merge` (local) and filesystem installation

- Repository import as set union of patches, frontier join (§7.8)
- Filesystem installation from a target path/byte map: removing blocked
  files, creating directories, writing files, pruning newly-empty
  directories (§6.2 tail)
- Dirty-tree and unsupported-entry refusal before any mutation (§10)
- Validate-before-write ordering for merge (§10)
- New-warnings-only stderr reporting (joined replay minus pre-merge local
  replay), sorted by path then reason (§6.4)
- No-op merge (equal/contained history) behavior

Verify: `--filter 09-merge-text`, `--filter 10-merge-conflicts`,
`--filter 11-namespace-conflicts`, `--filter 17-concurrent-creates`,
`--filter 18-three-way-convergence`, `--filter 20-dirty-merge`, and the local
portions of `26-portability-and-failure-safety`.

## 9. HTTP server and HTTP repository client

- `snap --serve [port]` (§7.9, §9): validate/snapshot at startup, bind
  `127.0.0.1` only, port `0` OS-assigned, print-and-flush the startup URL,
  `GET`/`HEAD /repository.json`, `404`/`405` + `Allow` header for everything
  else, exit 0 on SIGINT/SIGTERM
- HTTP repository operand support for `diff --repo` and `merge`: one GET,
  require status 200, parse and validate normally, no redirects/auth/caching

Verify: `--filter 12-http-server`, `--filter 13-http-client`,
`--filter 16-dot-collision` (HTTP side), remaining `26-portability-and-
failure-safety` cases.

## 10. Terminal presentation and final hardening pass

- Full ANSI SGR rendering for every command per §7.11's exact byte rules
  (init/commit/revert/merge success lines, status, log, diff, `--version`,
  warning/error lines), `SNAP_COLOR`/`NO_COLOR` precedence including TTY
  `auto` selection, and the invariant that presentation never changes
  execution/effects/exit status
- Sweep every command for CLI grammar strictness: unknown options, duplicate
  options, extra operands, missing option values, fixed option positions
- Full-suite regression pass

Verify: `--filter 28-terminal-presentation`, `--filter 24-cli-grammar-matrix`
(full), `--filter 14-cli-errors` (full), then `./verify --lang scala` with no
filter for the complete 28-file suite.
