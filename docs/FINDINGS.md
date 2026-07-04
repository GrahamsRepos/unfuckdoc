# Findings & Design Summary — Adaptive Profile Data Platform

A complete summary of the design worked out across this engagement: a platform where customers
upload **arbitrary tabular data** about people/companies (from CRMs or CSV) and the system
**automatically** makes it searchable and segmentable — no schemas, no manual indexing —
with per-source reliability scoring and semantic association.

This is the executive synthesis. Two companion documents go deeper:
`ingestion_plan.md` (the pipeline) and `interoperability_reliability_layer.md`
(sources, classification, merge, reliability). A runnable POC lives in `src/`.

---

## The one-paragraph answer

Upload any columns → take a **random statistically-significant sample** → classify each column
deterministically into **numerical / enumerated / free-text-vectorise / identifier**, escalating
to an **LLM only for the ambiguous residual** → tag each column with a semantic **theme** →
validate every row, quarantine junk, and load into **OpenSearch**. Small tenants **share a
pooled index**; large tenants get **dedicated** (optionally **partitioned**) indices. Data from
multiple sources is reconciled onto a **canonical model**, merged on **type ∩ theme**, and each
source is scored for reliability at **(source × field)** granularity. Free-text fields become
**vectors** for semantic search and record association. The **catalog** — the derived registry of
every column's type, theme, stats, and flags — is the actual IP; the storage underneath is
deliberately swappable.

**Governing principle:** *deterministic for correctness · sample for classification · LLM for
meaning (sparingly) · human for trust · full-scan for validation · never force a confident answer.*

---

# PART I — Storage & Architecture

**1 · Engine choice → OpenSearch.** For schema-flexible entity data needing arbitrary-attribute
search + semantic search, read-optimised, AP-leaning, and cheap, the contenders were search
engines (OpenSearch/Elasticsearch), columnar OLAP (ClickHouse — now with GA native JSON +
HNSW), and document stores (MongoDB). Chose **OpenSearch** for "throw arbitrary JSON at it and
filter on any new tag instantly," native hybrid keyword+vector search (BM25 + kNN, RRF fusion),
and a Lucene core that solves mutability for free.

**2 · The catalog is the product.** The real system is a **field catalog** (like Datadog
"facets"): store data + maintain a tenant-scoped registry of every key seen with inferred type,
theme, cardinality, samples, and flags. The catalog powers the self-configuring query builder,
drives tier migrations, and is **engine-agnostic** — the one asset that stays constant while
storage changes. This is the IP.

**3 · Multi-tenancy → pool the many, dedicate the few.** Cost tracks **shard count, not data
volume** (each shard is a full Lucene index with fixed overhead; per-tenant indices/aliases bloat
cluster state). Therefore: **many small tenants → one pooled shared index** (`tenant_id` + custom
routing + document-level security); **few large tenants → dedicated index each**. Never one index
per tenant. For pooled arbitrary schemas, store attributes as **nested typed key/value pairs**
(fixed ~6 slots: str/num/date/bool/txt/geo) so field-count and type-collisions never explode.
At ~50 keys/tenant, per-tenant field explosion is a non-issue; **tenant count** is the real axis.

**4 · Mutability → free, via Lucene.** "How does Google do it?" — LSM trees / immutable SSTables
+ background compaction; Google's Caffeine indexes *incrementally* instead of rebuilding.
OpenSearch/Lucene is the same: segments are immutable, an update = tombstone + a new tiny segment,
background merge cleans up. So **a tag change reindexes one document, never the index** — no index
rebuilds, ever. (In the nested layout, the unit of update is the profile block.)

**5 · Infrastructure → OVH Managed OpenSearch, not VPS.** A production cluster is stateful and
quorum-based; plain VPS lacks vRack private networking and caps at 400 GB/node. **OVH offers
managed OpenSearch** (turnkey, HA up to 99.99% on 3-AZ, backups, vRack, index ACLs) — the right
default. Self-hosting needs Public Cloud instances (for vRack), 3+ nodes, heap = 50% RAM capped
~31 GB, `vm.max_map_count=262144`, TLS, S3-compatible snapshots. **RAM is the binding resource
and is in a price crisis (2026, +250–300% projected)** — which is why the RAM-hungry vector index
is the thing to gate behind paid.

**6 · Free/paid tiering.** How observability vendors afford free: **hard caps at ingest + short
retention + shared substrate + gate expensive features**. We can't use retention (profiles
persist), so free-tier economics rest on: the **entry cap (5,000, enforced by an atomic
counter)** + the **shared pool** (near-zero marginal cost per tenant) + a **dormancy/eviction**
policy (cold-archive idle tenants as the retention substitute). **Free** = full structured
faceted segmentation (cheap, impressive at 5k). **Paid** = semantic/vector search (RAM-costly),
higher caps, dedicated index. Upgrade is seamless because **the catalog is the migration
blueprint** — it already knows every field's type.

---

# PART II — The Ingestion & Classification Pipeline

**7 · Two tracks meeting at the catalog.**
*Track A (sample, fast, has the only LLM call):* stage & parse → **random sample** → deterministic
type voting → rule routing → **LLM enrichment (sample + masked stats only)** → human confirm.
*Track B (deterministic, every row, no LLM):* full-set validate & quarantine → true
distribution & fill-rate → transform + batch-embed → route pool/dedicated → partition-plan →
bulk load. **The LLM sees only a sample and only adds meaning; the machine that ingests millions
of rows is fully deterministic and never blocked by the model.**

**8 · Ingest is decoupled from indexing.** Raw rows land cheaply first (object storage); nothing
is indexed until types are decided. This is what lets you reject junk, infer types, and choose an
index before paying for indexed storage — the Datadog "logging without limits" pattern.

**9 · Classification layer — four classes, confidence-gated.** From a random significant sample,
classify each column: **numerical / enumerated / free_text (vectorise) / identifier**. The fourth
matters — high-cardinality strings that look like prose (emails, UUIDs, SKUs, names) must **not**
be embedded. Genuine ambiguity lives only among string columns (category vs prose vs id). The
classifier emits a **class + confidence margin**; if the margin clears a threshold it resolves
deterministically (the common case), else it **escalates to the LLM**. LLM cost scales with
**ambiguity, not volume**; `MARGIN` is a tunable dial; resolutions feed back as few-shot examples
so the escalation rate shrinks over time.

**10 · Sampling & nulls at scale.** Sample **randomly over the whole set** (never first-N —
ordering bias), sized to a **target number of *populated* values** (not a flat %), because
sampling error depends on sample size, not dataset size; use per-column reservoirs so sparse
columns still get signal. **Nulls are coverage, not signal** — fill-rate is metadata, typing runs
on populated cells only, a 3%-filled column is a first-class field. **Sample to classify,
full-scan to validate/quarantine.**

**11 · Tagging — theme, not type.** Two orthogonal axes: **type** (what it *is*) and **tag** (what
it's *about*). Tags must be themes (`annual_revenue`, `country`, `job_title`) — **never** type
restatements (`number`, `string`) — enforced by a controlled vocabulary + a type-synonym
reject-list. Assignment: dictionary/alias hit (free) → value-signature hit (free) → **LLM only on
the residual**, constrained to the vocabulary.

---

# PART III — Interoperability & Reliability

**12 · Read declared metadata; infer only as fallback.** CRMs declare their field types —
**HubSpot** (`type` + `fieldType`, custom props via API), **Salesforce** (`describeSObject` types;
custom fields suffixed `__c`; picklists carry value sets), **Zoho** (`data_type` + `custom_field`
+ `pick_list_values`). Resolution priority: **declared → inferred → provenance flag**. Picklists
are a gift — they hand you the enumerable classification *and* the full value set/cardinality for
free, feeding both the facet decision and partition-candidacy.

**13 · Two-layer schema — the balance for any new data.** A stable, curated **canonical core**
(common fields, alias map, consistent OpenSearch types across all sources) + an unlimited
**custom/dynamic layer** (everything unmatched → inference pipeline → nested EAV). Small stable
core + unbounded dynamic layer = interoperability without schema work.

**14 · Field merge — on `type ∩ tag`.** Merge two columns only when **both** type and theme match
— the axes fail independently, which makes auto-merge safe (`bill_country`/`ship_country` share
type but not theme → no merge; `annual_rev`/`revenue_band` share theme but not type → no merge).
For unknowns, score a weighted blend (**value-overlap strongest**, semantic embedding of field
*metadata*, name similarity weakest) behind a hard type gate. **Favor precision** — a false merge
silently corrupts data (unrecoverable); a false split is just a duplicate to reconcile.

**15 · Outliers & ambiguity — the tag validates cells.** Once tagged, the tag becomes a per-cell
validator: enumerated → domain set, numerical → plausible range, patterned → signature. Failures
are **coerced / null-and-quarantined / flagged**. When a column **can't** be tagged confidently,
degrade down a ladder — **resolved → typed-but-untagged (searchable, just not mergeable) →
needs_review → quarantine** — never forcing a guess. A high cell-outlier rate **challenges the
column's tag** (40% of `country` failing means it probably isn't country).

**16 · Meta columns — where unresolvable data lives.** Not one blob — reason-coded buckets:
`_untagged` (typed & searchable, no theme), `_unresolved` (couldn't type — raw, preserved),
`_quarantine` (cells failing a confident tag). Every item carries **provenance + reason code**;
it's a **draining queue, not a landfill** (items graduate out as tags are confirmed / aliases
added); its **size is a per-source health metric**.

**17 · Source reliability — a quality metric.** Two separate sub-scores: **resolvability** (did we
understand the schema — tag rate, type confidence, catch-all fraction, canonical hit rate,
declared-metadata availability) and **data quality** (fill, outlier, format-consistency,
coercion, duplicate rates). Combine with a **harmonic mean** (weak axis dominates), **weighted by
field importance**, **normalised per type**, **trended for drift**. Surface the components, not
just the composite; tie bands to actions.

**18 · Reliability is a (source × field) matrix.** A per-source scalar is too coarse. Score each
canonical field per source from *that field's* quality in *that source* — which is what lets
**set A be reliable while set B scores lower**. **Many-to-one** → **weighted survivorship** (A's
value wins the golden record; B falls back to fill gaps — coverage, never overwrite). **One-to-
many** → derived fields inherit `reliability × extraction_confidence`. **Many-to-many** → cap low,
human review. The **golden record carries per-field provenance and reliability**, not one blend.

---

# PART IV — Segmentation, Sharding & Similarity

**19 · Datadog vs Grafana — the indexing spectrum.** Loki indexes almost nothing (low-card labels
only, brute-force scans the rest via structured metadata); Datadog indexes selectively
(schema-on-read facets ≤1000, gated by quotas/indexes). At ≤5k entries/tenant you're small enough
to **index everything structured like Datadog** on free, and reserve only the semantic index for
paid.

**20 · Sharding across enumerated types.** A **large-tenant, dedicated-index** optimisation only —
never the free tier. **Never one shard per value** (low-cardinality enums are the *worst* shard
keys — few, skewed partitions, hotspots). Instead **bin-pack values into N balanced buckets** from
the real histogram so a hot value gets its own bucket rather than hotspotting. **Only partition on
stable enums** — a mutable shard key turns every value change into a cross-partition migration.
The catalog's own cardinality/skew/churn/query-frequency stats decide partition-worthiness
automatically. *(POC `partition_planner.py` demonstrates this: `Country` 30-value/34%-skew → 4
balanced buckets; `Plan` rejected as low-card + mutable.)*

**21 · Vector similarity & association.** High-entropy free-text fields become vectors; associate
records via **filtered kNN** (cosine + HNSW, always narrowed by structured facets first — pure
vector ignores your cheap precise signals). **Record-level** similarity **composes** rather than
concatenates: per-field vectors weighted by **tag + reliability**, fused with structured signals
via **RRF**, and turned into **clusters** via a kNN similarity graph when you want groups. Keep a
**high threshold for identity/merge** and a **lower band for association** — vector similarity
means *similar*, not *same*. Calibrate by **rank (top-k)**, not absolute cosine score.

---

# PART V — Off-the-Shelf Landscape

- **Closest pre-built match: Apache Unomi** — open-source CDP (persistent profiles + dynamic
  segments + GDPR controls, on Elasticsearch). Study it even if you don't adopt it.
- **Lean faceted-search substrates: Typesense / Meilisearch** — auto-facets, hybrid search,
  built-in multi-tenancy (Meilisearch tenant tokens), InstantSearch adapters; far lighter ops
  than OpenSearch.
- **Segmentation at scale: Druid / Pinot / ClickHouse** — the paid-tier / large-tenant path.
- **The LLM tag-inference + reliability layer stays your differentiator** regardless of substrate.

---

# PART VI — The POC (what was built)

Runnable Python demonstrating the core ideas end to end:

- **`infer_pipeline.py`** — CSV/XLSX → 5%-sample type inference → catalog → OpenSearch mapping +
  bulk docs (with stub embeddings for free-text fields). Caught a real bug live (long-average-
  length must beat low-cardinality → free-text wins), which is *why* that rule ordering exists.
- **`partition_planner.py`** — full-set distribution → partition-candidacy tests
  (cardinality/skew/stability) → **balanced bucket** plan + value→bucket routing map.
- **Artifacts** — `catalog.json`, `opensearch_mapping.json` (incl. `knn_vector`),
  `partition_plan.json`, sample bulk docs; 20k-row demo dataset with a skewed enum.
- **Docs** — `ingestion_plan.md` (+ PDF with flow diagram) and
  `interoperability_reliability_layer.md`.

**Two production stubs to swap:** real embeddings (sentence-transformers / OpenAI / Cohere,
batched) in place of the deterministic placeholder; and an `opensearch-py` load in place of
writing `bulk.ndjson` to disk. Also stubbed: **stability** and **query-frequency** signals (come
from usage/churn telemetry feeding the same catalog).

---

## Principles recap (the whole system in ten lines)

1. **Read declared metadata first; infer only as fallback** — and record provenance.
2. **The catalog is the IP** — engine-agnostic; storage underneath is swappable.
3. **Pool the many, dedicate the few** — cost tracks shard count, not data volume.
4. **Mutations are cheap** — one document reindexed, never the index.
5. **Classify by statistics with a confidence gate** — LLM only on the low-margin residual.
6. **Tag by theme, never by type-word** — merge on `type ∩ tag`, favoring precision.
7. **Nulls are coverage, not signal** — sample to classify, full-scan to validate.
8. **Never force a confident answer** — degrade gracefully; `null` beats a confident lie.
9. **Reliability is a (source × field) matrix** — weighted survivorship: A wins, B fills gaps.
10. **Everything self-improves** — human confirmations, aliases, and escalations feed back;
    escalation, catch-all, and untagged rates shrink over time.
