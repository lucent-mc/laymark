# Benchmark mod name research

Checked: 2026-08-16.

## Method and limitations

Each candidate was checked against Modrinth's public API in two ways:

1. `GET /search?query=<name>` for indexed title and discovery collisions
   ([search API](https://docs.modrinth.com/api/operations/searchprojects/)); and
2. `GET /project/<slug>` for an exact public slug resolution
   ([project API](https://docs.modrinth.com/api/operations/getproject/)).

Every candidate slug below returned HTTP 404 at the time of checking. This
means no **publicly resolvable** project currently uses that exact slug; it is
not a reservation, trademark check, or guarantee that Modrinth will accept the
slug. Modrinth documents 404 as either not found or unavailable to the current
authorization context
([slug/ID check response](https://docs.modrinth.com/api/operations/checkprojectvalidity/)).
Search likewise covers indexed public projects, so a draft, private, reserved,
or newly submitted project may not be visible.

## Candidate scan

| Candidate | Slug checked | Exact indexed title/search hit | Exact slug | Similar or confusing public projects |
| --- | --- | --- | --- | --- |
| **Benchy** | `benchy` | No exact title; [search returns one near-hit](https://api.modrinth.com/v2/search?query=Benchy&limit=20) | 404 | `Benchy boats`, an unrelated resource pack |
| **PackBench** | `packbench` | [0 results](https://api.modrinth.com/v2/search?query=Packbench&limit=20) | 404 | None under the exact phrase; the name is generic |
| **Laymark** | `laymark` | [0 results](https://api.modrinth.com/v2/search?query=Laymark&limit=20) | 404 | None found |
| **Lucent Bench** | `lucent-bench` | [0 results](https://api.modrinth.com/v2/search?query=Lucent%20Bench&limit=20) | 404 | [`Lucent`](https://api.modrinth.com/v2/project/lucent), an established unrelated dynamic-lighting API mod |
| **Lucent Benchmark** | `lucent-benchmark` | [0 results](https://api.modrinth.com/v2/search?query=Lucent%20Benchmark&limit=20) | 404 | Same `Lucent` collision; also a comparatively long name |
| **Inlay Bench** | `inlay-bench` | [0 results](https://api.modrinth.com/v2/search?query=Inlay%20Bench&limit=20) | 404 | None found |
| **Benchcraft** | `benchcraft` | [0 results](https://api.modrinth.com/v2/search?query=Benchcraft&limit=20) | 404 | Generic `bench`/`craft` construction |
| **Modbench** | `modbench` | [0 results](https://api.modrinth.com/v2/search?query=Modbench&limit=20) | 404 | Generic among existing benchmark mods |
| **Packmark** | `packmark` | [0 results](https://api.modrinth.com/v2/search?query=Packmark&limit=20) | 404 | Short, but does not clearly communicate benchmarking |
| **InlayMark** | `inlaymark` | [0 results](https://api.modrinth.com/v2/search?query=InlayMark&limit=20) | 404 | None found; the meaning is less immediate than Inlay Bench |
| **Layerbench** | `layerbench` | [0 results](https://api.modrinth.com/v2/search?query=Layerbench&limit=20) | 404 | None found |
| **Stackbench** | `stackbench` | [0 results](https://api.modrinth.com/v2/search?query=Stackbench&limit=20) | 404 | None found |
| **PackProbe** | `packprobe` | [0 results](https://api.modrinth.com/v2/search?query=PackProbe&limit=20) | 404 | None found; sounds broader than a benchmark harness |
| **ModGauge** | `modgauge` | [0 results](https://api.modrinth.com/v2/search?query=ModGauge&limit=20) | 404 | None found |

The functional namespace is already somewhat crowded: Modrinth's
[`benchmark` search](https://api.modrinth.com/v2/search?query=benchmark&limit=20)
returns projects including
[`FPS Benchmark`](https://api.modrinth.com/v2/project/fps-benchmark),
`HostBenchmark`, `ServerBenchmark`, and `MCBenchmark`. `FPS Benchmark` is the
closest functional neighbour because it is also an automated client benchmark
that emits structured results. A distinctive product name will therefore be
easier to find than another generic “Minecraft/FPS Benchmark” variation.

## Decision

**Laymark** was selected. This is the standalone tool's name; Lucent
Optimisations keeps its existing Lucent branding. The public `Lucent` mod is
only a search-disambiguation note, not a precedence concern or a reason to
change the established Lucent pack family; Lucent Vanilla predates that mod's
first release according to the pack author.

## Considered ranking

1. **Laymark** — best overall. It is short, memorable, has no public Modrinth
   collision, and subtly connects **Inlay** with bench**mark** without tying the
   project to one modpack.
2. **Inlay Bench** — clearest ownership and purpose. Prefer this if immediate
   comprehension matters more than compact branding.
3. **Layerbench** — directly evokes the layered/forkable pack model while
   remaining usable outside Lucent.
4. **Stackbench** — fits the planned incremental mod-combination benchmarking
   strategy particularly well.
5. **PackBench** — the most literal name, but also the least distinctive.

Use **Laymark** as the display name and `laymark` for the repository, Modrinth
slug, artifact ID, and mod ID if final creation confirms that each remains
available. **Lucent Bench** was not selected because Laymark is more distinct
as the standalone tool's identity, not because the Lucent name is unavailable;
Lucent remains the pack's branding. Avoid **Benchy** because `Benchy boats`
already occupies that word in Modrinth search.
