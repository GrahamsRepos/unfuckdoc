# CLAUDE.md — project context for `unfuckdoc`

Context for continuing this project in Claude Code. Read this first; deeper rationale is in `docs/`.

## What this is

A system that turns a pile of **messy, heterogeneous files** into one **unified, deduped, searchable,
segmentable entity store**. Dump CSVs with different column names / partial fields / duplicate people
→ it standardises columns to **canonical fields**, **merges** records by a key, and makes the result
filterable by tags, ranges, geo, and meaning. The larger vision is a self-serve, self-hostable
mini-CDP: upload arbitrary profile/CRM data, get it segmentable with no schema work.

## Where the code lives (important)

- **`server-kt/`** — the **go-forward backend** (Kotlin · Ktor · Guice · OpenSearch-java · DJL). All
  new backend work goes here.
- **`web/`** — the **frontend** (React Router v7 SSR · TypeScript). Talks to `server-kt` on `:8080`
  (defaults there; `API_URL` overrides).
- **`src/`** — the **original Python POC**, kept as reference only. **Do not extend it.**

## Guiding principles (keep these when extending)

- **Deterministic-first; models only on the ambiguous residual, gated + counted.** Type
  classification, field-name canonicalization (alias dictionary), merge/dedup, transforms, filters —
  all deterministic. Embeddings run only on the field-name residual + free-text vectors; the LLM runs
  only for attribute extraction / reasoning. Never per-row at query time.
- **Nulls are coverage, not signal.** Sparse columns stay first-class; type on populated cells only.
- **Never force a confident answer.** Degrade (identity fallback, `null`) rather than guess.
- **Numbers/geometry by code, meaning by vectors, judgment by the LLM.** Don't make the LLM do
  arithmetic or point-in-polygon; don't make vectors do negation/logic.
- **Self-host, small, quantized models.** The reason is PII/EU-residency, not cost (HNSW RAM is the
  real cost). Both models are OpenAI-compatible + env-swappable (Ollama local → OVH AI Endpoints).

## The collection workflow (the UI + the engine)

`/collections/:name` is a 4-stage stepper, each its own route:
`① Sources` (merge/dedup by key) → `② Canonical` (standardise: transforms, custom canonicals, mapping
fixes, LLM attributes, schema) → `③ Enrich` (join other collections/files) → `④ Explore`
(search/tags/ranges/geo/semantic/segments).

## The four field-production methods (one engine)

1. **Deterministic** — `Classifier` (types), `Canonicalizer`+`SemanticCanonicalizer` (field names),
   `Consolidator` (merge/dedup, scalar vs array), `TransformEngine` (safe expression DSL, OpenRefine-style).
2. **Vectors** — `Embedder` (nomic via `OpenAiEmbedder`, else `MiniLmEmbedder`/DJL); semantic search =
   in-memory cosine over per-entity free-text vectors (relevance bars in the UI).
3. **LLM** — `LlmClient` (`OpenAiChatClient`, qwen2.5); attribute extraction reads free text → typed,
   filterable fields (handles negation: "no garden" → `has_garden=false`). Runs in the background with
   a live progress bar; cached per (text+spec).
4. **Aggregate** — *not built yet* (group one-to-many → counts/sums/ratios → scores).

## How to run (dev)

```bash
docker compose up -d                    # OpenSearch :9200 (dev, no TLS/auth)
brew services start ollama              # + `ollama pull nomic-embed-text qwen2.5:7b`
cd server-kt && PORT=8080 \
  EMBED_BASE_URL=http://localhost:11434/v1 EMBED_MODEL=nomic-embed-text \
  LLM_BASE_URL=http://localhost:11434/v1   LLM_MODEL=qwen2.5:7b ./gradlew run
cd web && npm run dev                    # :3000
```
Without the env vars the backend falls back to in-process MiniLM embeddings + **no LLM** (extraction
shows "no LLM configured"). Tests: `cd server-kt && ./gradlew test` (all green; MiniLM tests use the
cached model).

## Current state (what works, end-to-end)

- Ingest → classify → canonicalize (dict + semantic, benchmarked) → **merge/dedup by key** (exact →
  scalar; conflicts → array + flagged) → OpenSearch index.
- **Enrichment joins** (1:1 / 1:many) from a file **or another collection** (chaining), typed.
- **Transforms** (safe DSL) mutate values before processing.
- **Custom canonicals** (typed, incl. multi-value) + **manual mapping override**.
- **Search**: keyword, two-stage tag picker + enumeration (incl. boolean true/false), numeric/date
  ranges, **geo** (`geo_point` detection + bbox/polygon + Leaflet draw-to-filter), **semantic** (vector
  cosine + relevance bars), **segments** (saved filtered views).
- **LLM attribute extraction** with a background progress bar + ✓/✗ badges in results.
- **Match** (fuzzy record linkage between two datasets).
- Orphan-index cleanup; delimiter auto-detection; JSON-Schema endpoint.

## Known gaps / good next tasks

1. **Persistence (system of record).** Collections/transforms/extractions/enrichments are **in-memory
   only** — a restart loses everything and orphans indexes. Add SQLite/Postgres so state survives and
   OpenSearch becomes a rebuildable derived index. **Highest-leverage gap.**
2. **Aggregate method** — group one-to-many → counts/sums/ratios → typed score fields (completes the
   generic derived-field engine; unlocks lead-score / risk shapes: code computes signals, LLM reasons).
3. **NL → query planner** — LLM turns a plain-text question into the existing filter/range/geo/tag/
   semantic DSL, RAG-grounded on the schema + enum values (see `docs/`).
4. **Named-region gazetteer + geo NOT/exclude** — so "not in Europe" works as a real geo filter.
5. **Instance/value-based canonicalization** — match columns by their *contents*, not just names.
6. **`knn_vector` in OpenSearch + hybrid** — the semantic search is in-memory brute-force; move to a
   real HNSW index for scale.

## Environment notes

- `docker-compose.yml` disables OpenSearch security for local dev — **not** for production.
- Models are free/open (nomic, qwen2.5) via Ollama; on Apple Silicon run Ollama **natively** (Metal),
  containers reach it at `host.docker.internal:11434`.
- Memory `[[kotlin-is-go-forward-backend]]`: new backend work goes in `server-kt/`, not `src/`.
