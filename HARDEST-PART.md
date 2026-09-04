# What's actually hard about implementing Snap

My candidate for the single hardest part is §6.2's replay/integration step —
specifically, getting the *order of operations* right when a patch is
integrated: whole-patch namespace-conflict resolution first, then per-path
evaluation against `B`/`C`/`T`, with the text branch of that per-path
evaluation (case 3) delegating to §6.3's OT transform against one aggregate
context edit `Q = diff(B, C)`. Individually, each piece — the namespace
pre-pass, the four-case per-path dispatch, the six-row OT table — is fairly
mechanical and well-specified (the ambiguity review even flags §6.3's table
and the aggregate-edit definition as "verified tight"). The danger is
architectural, not textual: it's easy to write code where these three
mechanisms exist as separate functions that get *composed in the wrong
order*, or where OT gets applied per historical patch instead of once against
a freshly recomputed `Q` at each integration step, and the result still looks
right on two-patch fixtures.

`tests/22-ot-matrix.yaml` and `tests/18-three-way-convergence.yaml` are the
tests that will actually catch this. The three-way convergence file merges
three independently-edited copies of one repository in different pairing
orders (a+b then +c, vs a+c then +b, etc.) and asserts they converge to the
same tree — that only holds if `Q` is recomputed fresh against the *current*
canonical tree `C` at each step rather than accumulated/reused across
patches, since replay is defined as strictly sequential integration (§6.1),
not a batch three-way merge. A naive "diff each patch against its own base
and merge the diffs" approach will produce association-order-dependent
results and fail exactly this file, while still passing simpler two-patch
merge tests. Similarly, §6.2's rule that identical concurrent changes
collapse (`C == T` ⇒ keep `C` unchanged) *before* OT runs matters for
`tests/11-namespace-conflicts.yaml`- and `10-merge-conflicts.yaml`-style
fixtures where getting this backwards means running OT on cases that were
supposed to short-circuit, which can produce a technically-plausible but
spec-divergent result.

A close second is the interaction the ambiguity review calls out in §10.1:
validation-before-mutation and precondition ordering for `merge`/`revert`.
The spec lists parsing → validation → replay → dirty-tree checks →
target-tree construction, all required to complete before any write, but
doesn't pin whether that's an execution order or an unordered set of gates.
Since no test combines two simultaneous faults, this is a place where the
implementation itself becomes the de facto spec — worth deciding deliberately
and documenting rather than let the code's incidental check order decide it.

Third: cross-language patch-byte determinism for `revert` (§7.4 in the
ambiguity doc). `commit`'s text/put/delete selection rule is spelled out;
`revert`'s isn't. Nothing in `tests/07-revert.yaml` inspects the produced
patch's JSON, so a Scala implementation could pick either heuristic and still
pass every current acceptance test — but silently diverge from the TS/Rust
siblings at the byte level, which only bites during §11's cross-implementation
exchange testing, far downstream of when the bug was introduced.

Runner-up, lower-stakes: the strict JSON/schema validation layer (duplicate
keys, unknown fields, canonical version-array ordering, integer-only revision
literals) is *mechanically* easy but has a lot of small, easy-to-miss rules
scattered across §3.2, §4.1, and §4.5 — the risk there is completeness and
exact error-message text, not algorithmic difficulty.
