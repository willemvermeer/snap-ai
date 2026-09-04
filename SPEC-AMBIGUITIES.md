# SPEC.md ambiguity review

Scope: close read of `SPEC.md` (786 lines), cross-checked against `tests/*.yaml`
where feasible. Findings are grouped by SPEC.md section and ranked within each
section by implementation-divergence risk (high risk first).

---

## §1 Product model

### 1.1 "report" vs "fail" on symlinks (see also §2, §10)
- **Text:** §1 itself says nothing about symlink handling directly, but §2
  says Snap "MUST report them and MUST NOT follow them," while §10 says
  "Any command that scans the working tree fails on a symlink or other
  unsupported entry rather than following or silently ignoring it."
- **Why ambiguous:** "report" (§2) could mean a non-fatal warning while the
  command otherwise proceeds (e.g., `status` still shows other files), vs.
  §10's unambiguous "fails" (whole command aborts, exit 1). A reader of §2 in
  isolation could reasonably implement partial/non-fatal reporting.
- **Test resolution:** Resolved by tests. `tests/08-unsupported-entries.yaml`
  shows `status`, `commit`, and `diff` all exit 1 with
  `snap: unsupported working tree entry: <path>` and no output/mutation —
  i.e. "report" means "fail with this exact message," not "warn and
  continue."
- **Suggested clarification:** In §2, replace "report" with "fail with
  `snap: unsupported working tree entry: <path>`" or explicitly cross-reference
  §10's failure semantics.

---

## §2 Repository and working tree

### 2.1 Read-only commands and dirty-tree inspection vs. hard failure on unsupported entries
- **Text:** "Read-only commands may inspect one [a dirty tree]." elsewhere
  (§10) "Any command that scans the working tree fails on a symlink..."
- **Why ambiguous:** The permissive "read-only commands may inspect a dirty
  tree" sentence, placed right before the symlink rule discussion, could be
  misread as granting read-only commands an exemption from the unsupported-
  entry failure rule. It does not (confirmed by tests, see §1.1 above), but
  the two statements sit close enough in the document to invite confusion.
- **Test resolution:** Resolved by tests (same evidence as §1.1).
- **Suggested clarification:** Add a short note that the dirty-tree
  permission is orthogonal to, and does not override, the unsupported-entry
  failure rule.

### 2.2 Path relativity across subdirectory invocation
- **Text:** "Snap locates the nearest repository by walking from the current
  directory to the filesystem root" (§7 intro); tracked-path rules are stated
  relative to "the repository root" (§2).
- **Why ambiguous:** It is never stated explicitly that all path output
  (`status`, `log`, `diff`, warning pairs) is always relative to the
  repository root regardless of the cwd used to invoke the command from a
  subdirectory. This is a reasonable inference but not stated as a rule.
- **Test resolution:** Implicitly consistent with `tests/19-version-boundaries.yaml`
  (runs `status` from `repo/sub/deep`), but that test has no dirty files, so
  it does not exercise root-relative *path printing* from a subdirectory.
- **Suggested clarification:** State explicitly that all printed/warned paths
  are always root-relative, independent of invocation directory.

---

## §3 Versions

### 3.1 "overflow" is used for two different things without disambiguation (high risk)
- **Text:** §3.2: "Duplicate IDs, explicit zeroes, leading zeroes, overflow,
  invalid IDs, whitespace, and noncanonical ordering are errors" (CLI parsing
  context — revision exceeding `9007199254740991`). §7.5: "A clean tree,
  invalid message, overflow, or dot collision is an error," listed
  *separately* from the preceding bullet "Rejects a message longer than 4096
  UTF-8 bytes."
- **Why ambiguous:** In §7.5, "overflow" is listed as a distinct commit-time
  failure from "invalid message" (which already covers the 4096-byte case).
  This strongly implies "overflow" here means something else — most likely a
  contributor's revision counter exceeding `9007199254740991` on commit — but
  this scenario, and its error text, is never spelled out anywhere in the
  document. Two implementers could reasonably read "overflow" in §7.5 as
  (a) a redundant restatement of the message-length rule, or (b) revision
  counter overflow, and would pick different trigger conditions and error
  text.
- **Test resolution:** Not found in `tests/*.yaml` (no test constructs a
  contributor already at max safe integer, and `grep -rn overflow tests/`
  returns nothing).
- **Suggested clarification:** Explicitly define revision-counter overflow at
  commit time (base revision already at `9007199254740991`) as a distinct
  error with its own message, separate from message-length rejection.

### 3.2 Canonical order requirement for JSON-encoded versions is never stated
- **Text:** §3.2 states the CLI textual canonical form must be sorted by
  unsigned UTF-8 bytes with no duplicates/leading zeroes/etc. For the JSON
  form it only says "a version is an ordered array of `[id, revision]`
  pairs," without restating that this array must also be sorted/canonical/
  duplicate-free.
- **Why ambiguous:** Read in isolation, "ordered array" could simply mean
  "an array" (JSON arrays are inherently ordered), not "sorted in canonical
  order." A reader could conclude that `frontier`/`base` arrays in
  `repository.json` may list contributors in any order, as long as they are
  internally consistent.
- **Test resolution:** Resolved by tests — `tests/23-strict-validation-matrix.yaml`
  rejects a repository with `"frontier": [["b@x", 1], ["a@x", 1]]` (out of
  canonical order) with a "canonical" error message. So the requirement is
  real but the prose never states it for the JSON form specifically.
- **Suggested clarification:** In §3.2 or §4.5, state explicitly that JSON
  version arrays (`frontier`, `base`) must obey the same canonical
  ordering/uniqueness rules as the CLI textual form.

### 3.3 "different current ancestor or descendant" wording (see §6.2 for full discussion)
Not repeated here; see §6.2 finding.

---

## §4 Repository and patch format

### 4.1 JSON number-format edge cases underspecified
- **Text:** "Unknown fields, non-integer numbers, and invalid typed values
  are errors" (§4.1).
- **Why ambiguous:** JSON allows numeric literals like `1e3`, `1E3`,
  `2323.0`, or a bare `-0` for revisions. "Non-integer numbers" plausibly
  rejects `2323.0` (fractional-looking even though mathematically integral)
  and exponent forms, but this is not spelled out, and JSON itself already
  forbids leading zeros syntactically, so it's unclear exactly which numeric
  literal shapes are rejected as "non-integer" versus simply "not the exact
  literal Snap writes."
- **Test resolution:** Partially resolved — `tests/23-strict-validation-matrix.yaml`
  rejects `"revision": 1.5` with a "positive safe integer" error, confirming
  fractional values are rejected, but does not test `2323.0`, `1e3`, or `-0`.
- **Suggested clarification:** State that only bare non-negative integer
  JSON number literals (no fraction, no exponent, no negative sign) are
  accepted for revision fields.

### 4.2 Base text-ness is never required for a `text`-typed edit (moderate risk)
- **Text:** §4.3: "An edit, replacement, or delete requires it to be
  present" (presence only). §4.4 defines text tokens/edit scripts assuming
  the base is already valid UTF-8 with no NUL.
- **Why ambiguous:** Nothing in §4.3/§4.4/§4.5 explicitly requires that, for
  a `type: "text"` change, the *base* content at that path must itself be
  text (valid UTF-8, no NUL) before an edit script can be validated/applied.
  If the existing content is binary (e.g., previously written via `put`),
  tokenizing it per §4.4 is undefined. §6.4 rule 6 implicitly assumes this
  case is caught upstream ("P is text and C is non-text" only makes sense if
  a text edit against a non-text *current* path is otherwise rejected/
  redirected), but the validation rule that would reject (or well-define) a
  text edit against a non-text *base* is never stated.
- **Test resolution:** Not found in `tests/*.yaml`.
- **Suggested clarification:** Add to §4.5's per-change validation: "A `text`
  change's base path content, when present, MUST itself be text; otherwise
  the change is invalid."

### 4.3 "revision counter overflow" and message-overflow terminology
Covered under §3.1/§7.5 above.

### 4.4 Edit-script validity looks fully specified
No significant ambiguity found: adjacency, exhaustive-consumption, and
canonical-result constraints are precisely stated and exercised by
`tests/23-strict-validation-matrix.yaml` (adjacent-op rejection, "consumes
beyond old content," empty-insert rejection). Included here only to note the
focus area was checked and is tight.

### 4.5 Validation-step ordering and precedence when multiple faults coexist (moderate risk)
- **Text:** "Before using a repository, Snap validates: 1. its exact schema
  ...; 2. patch sorting...; 3. every patch's complete base closure...; 4.
  acyclic causality; 5. every change against its materialized exact base; and
  6. deterministic replay of the declared frontier."
- **Why ambiguous:** The list is numbered but never says "in this order" (contrast
  with §6.4's explicit "Resolve in this order"). When a repository has more
  than one simultaneous fault (e.g., both a schema error and a cycle), it is
  unspecified which failure — and thus which error message — Snap must
  report. Two implementations could disagree on the reported error for a
  multiply-invalid repository, which is observable in `stderr_equals`/
  `stderr_matches` tests.
- **Test resolution:** Not resolved — every fixture in
  `tests/15-repository-validation.yaml` and `tests/23-strict-validation-matrix.yaml`
  isolates exactly one fault at a time; none exercises a repository with two
  simultaneous validation failures.
- **Suggested clarification:** State that the six steps are applied in the
  listed order and the first failing step's error is reported, or explicitly
  disclaim any guarantee about which error wins when multiple validation
  failures coexist.

---

## §5 Canonical text diff

No material ambiguity found. The recurrence forces `retain` whenever
`A[i] == B[j]` (no min-comparison against insert/delete at that cell), which
is a standard, exchange-argument-safe property of insert/delete-only edit
distance — so equal tokens are always retained, and the only genuine tie is
delete-vs-insert on unequal tokens, resolved by the stated
`D(i+1,j) <= D(i,j+1)` rule. This does fully determine the output, including
for repeated equal lines. Worth confirming this area explicitly rather than
flagging it, since it was a named focus area.

---

## §6 Deterministic replay and OT

### 6.1 §6.2's four per-path cases are not explicitly stated as ordered/first-match (moderate risk)
- **Text:** §6.4 explicitly says "Resolve in this order" for its six rules.
  §6.2's four-case list ("1. If the path is identical in B and C... 2. If the
  path is identical in C and T... 3. If B, C, and T are text... 4.
  Otherwise...") never says "in this order" or "first applicable."
- **Why ambiguous:** Case 1 (`B == C`) and case 2 (`C == T`) can both hold
  simultaneously when `B == C == T` (i.e., the path never actually changed).
  Applying either produces the same result here, so behavior does not
  actually diverge for this particular overlap — but the document leaves it
  to the reader to verify that, rather than stating the resolution order
  explicitly the way §6.4 does. A careless implementer might structure this
  as an unordered case statement and hit an ambiguous or unreachable branch
  if they misjudge exclusivity in a future spec revision or edge case.
- **Test resolution:** Not directly tested (no fixture isolates the
  `B==C==T` triple), but low practical risk since the two cases are
  outcome-equivalent whenever they overlap.
- **Suggested clarification:** Add "Resolve in this order" to §6.2's per-path
  list for consistency with §6.4, and note case 1/2 overlap is
  intentional/benign.

### 6.2 "different current ancestor or descendant" — awkward phrasing
- **Text:** "If a path in `S` has a different current ancestor or descendant
  in `C'`, mark the incoming path for installation..."
- **Why ambiguous:** "different" modifies nothing clearly — it most likely
  means "there exists a distinct current path in `C'` standing in an
  ancestor/descendant (path-segment-prefix) relationship to the `S` path,"
  i.e., a prefix-freeness violation check. But taken literally, "different...
  ancestor or descendant" could be misread as requiring the ancestor/
  descendant's *content* to differ from something, which is not the intended
  check (existence/prefix conflict, not byte content, is what matters).
- **Test resolution:** Behavior is exercised and matches the
  prefix-conflict reading in `tests/11-namespace-conflicts.yaml` (both the
  `a` vs `a/b` and `x` vs `x/y` directions), so the *intended* semantics are
  clear from tests even though the sentence itself is clumsy.
- **Suggested clarification:** Reword to: "If a path in `S` would violate
  prefix-freeness against some other path currently present in `C'`
  (i.e., `C'` contains a proper ancestor or a descendant of that path)..."

### 6.3 §6.3's OT table and §6.2's "aggregate context edit" — verified tight
No material ambiguity found for either named focus sub-area. The six-row
transform table is exhaustive over all 3×3 head-operation combinations once
the "Q insert has priority" and "P insert (Q not insert)" rows are read
together (verified by enumeration). The "aggregate context edit" `Q =
diff(B, C)` is unambiguous for 3+ concurrent patches because replay is
strictly sequential (§6.1): each integration step recomputes a single fresh
`Q` against whatever `C` has accumulated so far, never composing multiple
historical edit scripts. `tests/22-ot-matrix.yaml` exercises P-insert/Q-insert
priority, unequal retain/delete splitting, overlapping deletes, and trailing
inserts, and results match this reading. Included to document that these
named focus areas were checked and found sound.

### 6.4 Namespace-wins precedence over per-path rules — verified consistent
`§6.2`'s statement that namespace decisions "override the per-path rules
below" is unambiguous in isolation, and `tests/11-namespace-conflicts.yaml`
confirms the winner is determined purely by canonical (Snap-order)
integration order of the *patches*, not by which side's *path* was created
first in absolute terms — a subtlety worth flagging as a common
misimplementation trap even though the spec text itself is not ambiguous
here.

---

## §7 Commands

### 7.1 No canonical error text is ever specified for "no repository found" (moderate-high risk)
- **Text:** §7 intro only describes repository *location* ("walking from the
  current directory to the filesystem root"); no section states what happens,
  or what text is printed, when no repository is found for a command that
  requires one (`status`, `log`, `commit`, `diff` with no `--repo`, `revert`,
  `merge`, `config` without `--global`).
- **Why ambiguous:** Exit code (1, "expected error," per §10) is inferable,
  but the exact message is nowhere in the prose.
- **Test resolution:** Resolved by tests only —
  `tests/14-cli-errors.yaml` pins `snap: not a Snap repository\n` for `status`
  run outside any repository. The spec prose itself never states this
  string.
- **Suggested clarification:** Add the exact "not a Snap repository" error
  text to §7 (or §10) as a normative message, the same way §8's
  "contributor.id is required" message is spelled out.

### 7.2 `snap config --global` behavior when `$HOME` is absent (moderate risk)
- **Text:** §7.2: "With `--global`, writes `$HOME/.snapconfig.json` and needs
  no repository." §8 (about *reading* config): "If `$HOME` is absent, global
  configuration is unavailable."
- **Why ambiguous:** §8's "$HOME absent" rule is phrased in the context of
  *reading* configuration for `commit`/`revert`. It is never explicitly
  stated whether `snap config --global contributor.id <id>` (a *write*) also
  fails when `$HOME` is unset, and if so, with what error text/exit code.
- **Test resolution:** Not tested — `tests/19-version-boundaries.yaml` tests
  `HOME: null` only for `revert` (which reads config), not for `config
  --global` (which writes it).
- **Suggested clarification:** State explicitly that `snap config --global`
  fails (and with what message) when `$HOME` is absent.

### 7.2b Ambiguity in partial/empty local or global config JSON
- **Text:** Config shape is `{"contributor":{"id":"alice@example.com"}}`; §8
  says "A missing file means no value... invalid ID in a file that is read is
  an error."
- **Why ambiguous:** It's unclear whether an on-disk `{}` (no `contributor`
  key at all) means "no value, fall through to global" (treated like a
  missing file) versus being itself a schema violation ("unknown field" logic
  doesn't apply, but is a *missing required field* an error?). Similarly for
  `{"contributor": {}}` (present but no `id`). Two implementers could
  reasonably disagree on whether these are valid "no value present" states or
  hard errors.
- **Test resolution:** Not tested in any `tests/*.yaml` file found.
- **Suggested clarification:** State explicitly whether `contributor` and
  `contributor.id` are optional keys (absence = "no value") or whether any
  present-but-incomplete `contributor` object is an error.

### 7.3 `snap diff --repo` positional grammar — explicit but unusual
`snap diff <old> <new> [--repo <repository>]` places the only option *after*
positional operands, unlike typical CLI convention. This is explicit per the
synopsis and "options occur exactly in the positions shown" (§7 intro), and
is exercised by `tests/24-cli-grammar-matrix.yaml` (`--repo` after operands
accepted; a second `--repo` after the first rejected). Not a genuine
ambiguity — noted only because it's easy to misread as a documentation typo
rather than a deliberate grammar choice.

### 7.4 `snap revert`'s produced patch change-types are unspecified (high risk)
- **Text:** §7.5 (`commit`) states an explicit algorithm for choosing
  `text`/`put`/`delete` per changed path: "Uses a text change when the new
  content is text and the old path is absent or text. Otherwise it uses
  `put`; removed paths use `delete`." §7.7 (`revert`) only says "Diffs the
  current tree to the target tree and authors one new patch," without
  restating (or cross-referencing) any type-selection rule.
- **Why ambiguous:** Two competent implementers could diverge here: one
  might reuse §7.5's exact heuristic (text edit when possible, else `put`),
  another might always use `put`/`delete` for revert on the theory that a
  revert is "restoring known bytes" rather than "diffing new content." Both
  produce the same *materialized tree*, but structurally different
  `repository.json` bytes — which matters for `revert`'s own diff output, for
  the `json_equals`-style acceptance checks the harness supports, and for
  cross-implementation patch-exchange comparisons (§11.11, "exact producer/
  consumer pairing").
- **Test resolution:** Not resolved — `tests/07-revert.yaml` checks only the
  materialized tree, `log` message text, and version numbers; it never
  inspects `repository.json`'s patch/change contents for the reverting
  patches (no `json_equals` on `repository.json` in that file).
- **Suggested clarification:** State that `revert` uses the same
  text/put/delete selection rule as §7.5 (or explicitly define a different
  one), so the produced patch bytes are pinned across implementations.

### 7.5 `snap --serve`: bind failure and pre-existing-repository-error text unspecified
- **Text:** §7.9 doesn't describe behavior when the requested port is already
  in use, or the exact error text/exit code if repository validation fails at
  startup before serving.
- **Test resolution:** Partially covered —
  `tests/12-http-server.yaml`'s last step confirms exit code 1 and a
  `snap: ...` stderr line for an invalid repository at startup, but the exact
  message is left as a loose regex match (`^snap: .+\n$`), and no test covers
  a port-already-in-use scenario. Low priority (§12 lists concurrent-process
  behavior as out of scope, which likely covers "someone else has this
  port").

---

## §7.11 Terminal presentation and color

### 7.11.1 Error-line transform: does `<error>` include the `snap:` prefix? (resolved by tests, prose inconsistent — moderate risk)
- **Text:** "A plain warning `warning: <detail>` becomes `S(33,"⚠") + " " +
  S(33,"<detail>") + LF`. A plain error line `<error>` becomes
  `S(31,"✗ " + <error>) + LF`."
- **Why ambiguous:** The warning transform explicitly strips the literal word
  "warning:" and replaces it with the ⚠ symbol, keeping only `<detail>`. The
  error transform, by contrast, uses `<error>` as a placeholder for the whole
  original plain line — but nothing states this explicitly, and by analogy
  with the warning transform a reader could plausibly expect the "snap:"
  prefix to be similarly dropped in favor of the ✗ symbol (producing
  `✗ <detail>` rather than `✗ snap: <detail>`).
- **Test resolution:** Resolved by tests —
  `tests/28-terminal-presentation.yaml` pins
  `"[31m✗ snap: invalid command or arguments[0m\n"`, confirming
  the "snap:" prefix is kept (unlike the warning case, which drops
  "warning:"). This is a real inconsistency between the two output kinds that
  the prose doesn't call out or justify.
- **Suggested clarification:** State explicitly that `<error>` denotes the
  entire plain-mode error line including its `snap:` prefix (unlike
  `<detail>` in the warning rule, which excludes the `warning:` prefix), and
  note this intentional asymmetry.

### 7.11.2 HTTP 404 vs. 405 precedence when both path and method are wrong
- **Text:** §9: "Other paths return `404`; other methods return `405` with
  `Allow: GET, HEAD`."
- **Why ambiguous:** Doesn't say which code wins for e.g. `POST /nonexistent`
  — check method first (405) or path first (404)?
- **Test resolution:** Not tested — `tests/12-http-server.yaml` only tests
  wrong-method-right-path (405) and right-method-wrong-path (404)
  independently, never both wrong simultaneously.
- **Suggested clarification:** State the check order explicitly (e.g., "path
  is checked first; an unsupported method to a valid path returns 405, any
  method to any other path returns 404").

---

## §8 Configuration
Covered above under §7.2/§7.2b (config write with missing `$HOME`; partial
config objects).

---

## §9 HTTP repository
Covered above under §7.11.2 (404/405 precedence). No other significant
findings — the read-only, single-GET, single-resource contract is otherwise
tightly specified and matches `tests/12-http-server.yaml` and
`tests/13-http-client.yaml`.

---

## §10 Mutation and failures

### 10.1 Precondition-check ordering for `merge`/`revert` when multiple preconditions fail (moderate-high risk)
- **Text:** "For `merge` and `revert`, Snap MUST complete parsing, repository
  validation, replay, dirty-tree checks, and target-tree construction before
  writing."
- **Why ambiguous:** This reads as an enumerated list of *gates that must all
  pass*, but its ordering — parsing, then validation, then replay, then
  dirty-tree checks, then target-tree construction — is unusual: dirty-tree
  checking (a cheap, purely local precondition) is listed *after* remote-
  repository validation and replay (comparatively expensive, and dependent on
  a possibly-remote resource). It's not stated whether this list is a
  required execution order (in which case a dirty local tree combined with an
  invalid/corrupt remote repository would surface the remote's validation
  error first) or merely an unordered enumeration of required gates (in which
  case an implementation checking dirty-tree first, for a cheap fail-fast, is
  equally conforming and would surface a different error message for the
  same input).
- **Test resolution:** Not resolved — `tests/20-dirty-merge.yaml` tests a
  dirty local tree merging with a *valid* remote repository only; no fixture
  combines a dirty local tree with an invalid/unreachable/corrupt remote
  repository to observe which error wins.
- **Suggested clarification:** State explicitly whether this list is a
  required check order (and if so, confirm dirty-tree truly is checked after
  full remote validation+replay, which is surprising) or an unordered set of
  preconditions with unspecified relative error precedence.

---

## Cross-section observations

- §1's "Snap records path/reason warning facts when whole-file conflict
  resolution occurs" and §6.4's "Line OT emits no warning" are consistent
  once read together, but a reader of §1 alone might expect *some* signal
  from every automatic OT resolution; worth a one-line cross-reference from
  §1 to §6.4/§6.5's warning-suppression rule for OT.
- §3.1 defines revision as "a positive integer no greater than
  `9007199254740991`," and §3.5's serial-contributor rule guarantees
  contiguous revisions per contributor — together these imply revision-
  counter overflow is reachable only after ~9 quadrillion commits by one
  contributor ID, making the gap in §3.1/§7.5 (finding above) low practical
  impact but still a real spec gap for exhaustive conformance testing.
