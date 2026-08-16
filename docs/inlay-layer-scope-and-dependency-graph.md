# Inlay layer scope and the mod dependency graph

Verified against `~/Development/lucent-mc/inlay` source on 2026-08-16.

Originally written as a companion to `benchmark-harness-plan.md` and
`benchmark-harness-handoff.md`. Both were deleted after an audit found 26 of their
149 claims wrong; see [plan-and-handoff-audit.md](./plan-and-handoff-audit.md).
The current design record is the wayfinder map at
<https://github.com/lucent-mc/laymark/issues/1> and its closed tickets.

Two problems are settled here:

1. **Scope.** Laymark must benchmark what the *current* Layer decides, not what it inherited. A child
   Layer that adds three mods on top of Lucent Vanilla has three candidates, not sixty.
2. **Graph.** Neither Minecraft nor Inlay owns a mod dependency graph. Laymark has to build one, and
   the sources it can build one from disagree with each other.

Laymark must also work with **no Inlay manifest at all**. Absent `inlay.index.json`, treat the
instance as a root Layer: every installed mod is current-Layer-owned and the scoping rules below
degenerate to "everything is a candidate." Layer awareness is a narrowing, never a prerequisite.

## What Inlay actually exposes

`lay list` has two shapes, both available as `--json` (`inlay/src/operations/list.ts`,
`inlay/src/cli.ts:187`):

| Invocation | Returns |
| --- | --- |
| `lay list --json` | `{ lineage: LayerIdentity[] }` where each Layer carries its own `content`, grouped by category |
| `lay list --resolved --json` | `{ lineage: LayerIdentity[], content: <grouped>, dependencies }` — the effective post-composition set |

Both wrap the standard `CommandResult` envelope (`schemaVersion`, `command`, `ok`, `changed`,
`diagnostics`, `data`). Every content item is a `ContentMetadata` (`inlay/src/inventory.ts:7`):

```ts
{ path, owner, projectId?, versionId?, version?, name?, kind?, license?, dependencies }
```

`owner` is the string `"<layer name>@<layer versionId>"`. `dependencies` is Modrinth's raw
`ModrinthDependency[]` — `{ version_id, project_id, file_name, dependency_type }` where
`dependency_type ∈ required | optional | incompatible | embedded`.

`ResolvedPack.dependencies` is **not** a mod graph. It is the Modrinth pack runtime target
(`minecraft`, `fabric-loader` / `neoforge`, …) and Inlay requires it to be byte-identical across the
whole lineage (`compose.ts:69`). That is a useful cross-check for Laymark's compatibility tuple and
nothing more.

### Composition semantics that change what a candidate means

From `inlay/src/resolution/compose.ts`:

- `lineage` is ordered root → current. The current Layer is `lineage.at(-1)`.
- Content occupies a slot keyed by **case-normalized path + environment**. A later Layer declaring
  the same path replaces the earlier one and records the displaced owners in `replacementHistory`.
- Replacement is by path, not by project. A current-Layer mod that bumps a parent's version under a
  *different filename* does not replace it — both files land in the pack unless the current Layer
  also declares an `exclusions` entry for the parent path.
- `exclusions` are applied per Layer before that Layer's own content, and only against slots already
  present. An exclusion that matches nothing is a warning, not an error.

## Ownership classes and their A/B arms

Every mod in the resolved set falls into exactly one class. The class determines whether it is a
candidate and, critically, **what the "off" arm actually is** — this is where naive layer filtering
gets the experiment wrong.

| Class | Detection | Candidate? | "Off" arm |
| --- | --- | --- | --- |
| **Inherited** | `owner` ≠ current Layer, empty `replacementHistory` | No — protected baseline | n/a |
| **Current-added** | `owner` = current Layer, empty `replacementHistory` | Yes | Mod absent |
| **Current-override** | `owner` = current Layer, non-empty `replacementHistory` | Yes | **Parent's file restored**, not absence |
| **Current-exclusion** | Parent path present in ancestor content but missing from resolved slots, and the current Layer declares a matching `exclusions` entry | Yes | **Parent's file restored** |
| **Current-replacement pair** | Current Layer excludes a parent path *and* adds a different path for the same project | Yes, as one atomic bundle | Parent's file restored |

The last three classes are the reason a plain `owner == current` filter over
`lay list --resolved --json` is insufficient. Overrides and exclusions are *decisions of the current
Layer* and belong in the candidate pool, but their counterfactual is the inherited artifact, not an
empty slot. Benchmarking them against absence measures the wrong difference and can hard-fail the
run when an inherited mod required the parent's file.

### Gaps in the resolved view

`ContentMetadata` drops `replacementHistory`, and exclusions are invisible in the resolved output by
construction — excluded slots are deleted before serialization. So:

- Call **both** `lay list --json` and `lay list --resolved --json`. The unresolved per-Layer view
  supplies ownership and lets Laymark reconstruct override and exclusion classes by diffing ancestor
  content against the resolved set; the resolved view supplies the effective stack and its Modrinth
  identity.
- Read `exclusions` from the current Layer's `inlay.index.json` directly. It is a documented,
  schema-validated field (`inlay/schema/inlay-1.0.0.schema.json`, `$defs.exclusion`) and reading it
  is not the same as importing Inlay internals — which stays forbidden.
- If the two views disagree about the runtime target, or a lineage revision changes mid-experiment,
  abort. Results are only comparable within one lineage revision.

### Classification still wins

`candidates.jsonc` remains authoritative. Layer scope *narrows the pool*; explicit classification
still assigns each project to `candidate`, `instrumentation`, `always-on`, `library`, or
`out-of-scope`. Two derived rules:

- A current-Layer mod that is a required dependency of any **inherited** mod cannot be toggled.
  Auto-promote it to `always-on` and emit a diagnostic naming the inherited dependent. Silently
  leaving it in the candidate pool produces runs that fail to launch.
- A current-Layer library required only by current-Layer candidates joins those candidates' bundles
  and is never a candidate itself.

Laymark should be able to emit a starting `candidates.jsonc` from the layer diff, but must refuse to
run an experiment until a human has classified every entry. Never infer a role from a filename,
Modrinth category, or the Layer boundary alone.

## Building the dependency graph

Inlay's `dependencies` field is a starting point and not a graph:

- It is populated **only** when a declaration's download URL is a `cdn.modrinth.com` URL
  (`modrinthIdentityFromUrl`, `inlay/src/adapters/modrinth.ts:43`). Content delivered from GitHub
  releases or any other HTTPS host gets `dependencies: []`.
- The JAR fallback parses `fabric.mod.json` but keeps only `name`; its `depends` map is read and then
  discarded (`inlay/src/inventory.ts:36-51`, `96-104`). Nothing probes
  `META-INF/neoforge.mods.toml`, so NeoForge-only artifacts contribute no edges at all.
- Modrinth version dependencies are publisher-declared metadata about a *project*. They are
  routinely incomplete, occasionally wrong, and may carry a `version_id` with a null `project_id`.

Laymark therefore builds its own graph from three sources, in this precedence order.

### 1. JAR metadata probe — authoritative for the artifact

The jar is ground truth about what the loader will actually demand at startup:

- **Fabric:** `fabric.mod.json` → `id`, `provides`, `depends`, `recommends`, `suggests`, `conflicts`,
  `breaks`, and `jars[].file` for jar-in-jar payloads.
- **NeoForge:** `META-INF/neoforge.mods.toml` → `[[mods]].modId` plus `[[dependencies.<modid>]]`
  entries with `modId`, `versionRange`, `ordering`, `side`, and the requirement marker. NeoForge has
  moved between a `mandatory` boolean and a `type` enum
  (`required`/`optional`/`incompatible`/`discouraged`); pin the parse to the exact target in the
  version descriptor and **fail closed** on an unrecognized shape rather than defaulting to optional.
- **Nested jars:** recurse into Fabric `jars[]` and NeoForge `META-INF/jarjar/metadata.json`. An
  embedded library satisfies a dependency with no separate file present. Skipping this manufactures
  false "missing dependency" verdicts and, worse, false *satisfied* verdicts when a candidate is
  removed and takes an embedded library with it.

Probe once per file, keyed by SHA-512, and cache. The probe is offline and deterministic; it is the
only source that still works when Modrinth is unreachable.

### 2. Modrinth API — project identity and publisher intent

Used for what the jar cannot tell you: the project this artifact belongs to, and the publisher's
declared relationships.

- `GET /v2/version_file/{sha512}?algorithm=sha512` maps any installed file to a version and project,
  including files Inlay could not identify from a non-Modrinth URL.
- `GET /v2/version/{id}` supplies `dependencies`; `GET /v2/projects?ids=[…]` batches project
  metadata.
- Send a descriptive `User-Agent`, honour `429`/`Retry-After`, and cache by hash. Inlay's adapter is
  the shape to mirror, not to import.

Treat Modrinth edges as **claims**. When a Modrinth claim and a jar probe disagree, the jar wins and
the disagreement is recorded in the run's environment metadata.

### 3. Explicit overrides

`candidates.jsonc` may assert or delete edges. This is the escape hatch for unpublished mods,
mis-declared metadata, and known-good substitutions. Every override is recorded with a reason.

### Identity reconciliation

The two automatic sources speak different languages: jars key on **mod ID** (`sodium`), Modrinth on
**project ID** (`AANobbMI`). Build the map from the jars themselves — each probed file yields its own
mod IDs *and*, via URL or hash lookup, its Modrinth project ID. Then:

- Resolve `provides` aliases into the same node as the providing mod.
- Model loader-supplied IDs as satisfied-by-environment nodes, never as installable candidates:
  `minecraft`, `java`, `fabricloader`, `neoforge`.
- Handle Fabric API's module fan-out: `fabric-api` provides a large set of `fabric-*` module IDs, and
  mods depend on individual modules. Resolve those to the single Fabric API node.
- Carry Inlay's substitution rule across: with Sinytra Connector and Forgified Fabric API installed,
  a `fabric-api` requirement is satisfied by FFAPI, with Connector Extras as an optional extension
  (`inlay/src/lib/dependency-adapters.ts`). Represent this as an explicit adapted edge with its
  adapter ID attached, so a report can explain why the requirement resolved.
- An unresolvable required edge is a hard planning failure for any stack containing that mod — not a
  warning. Inlay is right to warn rather than block during authoring; Laymark is benchmarking, and a
  stack that will not boot must never be scheduled.

### Provenance

Every edge in the resolved graph records `source ∈ jar | modrinth | override`, the adapter that
resolved it if any, and the SHA-512 of the file it came from. The graph is serialized into the
experiment record. A comparison whose graph cannot be reproduced from archived inputs is not
auditable, and auditability is the point.

## Effect on the experiment strategy

Amends the forward-selection procedure in the plan:

1. **Baseline** = loader + protected instrumentation (Laymark, Spark, Chunky, their closure) + the
   complete inherited closure from ancestor Layers + everything classified `always-on`. The baseline
   is *not* vanilla. On a deep lineage it is a large, fixed stack.
2. **Candidate pool** = current-Layer-owned mods classified `candidate`, expanded into
   dependency-safe atomic bundles using the graph above.
3. **Override and exclusion candidates** toggle between the current-Layer artifact and the inherited
   artifact. The runner materializes both arms from archived declarations; neither arm is "empty."
4. Every result records the lineage: each Layer's `name@versionId`, `source`, and `revision`, plus
   the current Layer's manifest hash. Results from different lineage revisions are never pooled —
   the same rule already applied to the loader tuple.
5. A candidate that shows no marginal gain over a rich inherited baseline is a legitimate and
   expected outcome. It means the parent already covers it, which is exactly the question a child
   Layer needs answered.

## Requested Inlay additions

None blocking. In rough value order:

1. `lay graph --resolved --json` — nodes, resolved edges, adapter resolutions, unresolved claims, and
   per-edge provenance behind one stable interface. This is the single change that would let Laymark
   delete most of its graph builder.
2. Expose `replacementHistory` and applied `exclusions` in `lay list --resolved --json`. Cheap, and
   it removes Laymark's need to diff two CLI invocations to recover override and exclusion classes.
3. Surface the already-parsed `fabric.mod.json` `depends` map, and add a
   `META-INF/neoforge.mods.toml` probe, so non-Modrinth-hosted content is not dependency-blind.

Until then Laymark owns this entirely and does not import Inlay's `dist` modules.
