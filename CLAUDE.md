# CLAUDE.md — project context for `unfuckdoc`

Context for continuing this project in Claude Code. Read this first; deeper design rationale is in
`docs/FINDINGS.md` and the two design docs alongside it.

## What this is

A pipeline + POC that turns **any messy CSV** into a searchable **OpenSearch** index, with
**minimal LLM usage**. It infers each column's type from a statistical sample, cleans it
(null-aware), routes primitives to typed fields, and for **free-text** columns it **summarises,
extracts keywords, and vectorises** them — then serves **semantic + keyword + hybrid** search.

The larger vision (see `docs/`) is a multi-tenant SaaS where customers upload arbitrary
profile/CRM data and it becomes searchable/segmentable with no schema work. This repo is the
ingestion + classification + enrichment core of that.

## Guiding principles (keep these when extending)

- **Deterministic first, LLM only on the ambiguous residual.** The classifier emits a class + a
  confidence *margin*; only below-margin columns escalate to `llm_classify()`, and escalations are
  **counted**. On the bundled real data this is **0 LLM calls**. Keep the LLM a rare escalation,
  never a per-row/per-column cost. Watch the escalation counter as the cost meter.
- **Nulls are coverage, not signal.** Sparse columns stay first-class; record `fill_rate`, type on
  populated cells only, never force a value. (`region_2` in the demo is 61% null and still useful.)
- **Never force a confident answer.** Degrade gracefully (typed-but-untagged, quarantine) rather
  than guess. A `null` beats a confident lie.
- **Tag by theme, merge on `type ∩ tag`.** (Design layer, see docs — not yet in code.)
- **Embeddings: self-host, small, quantized.** The decision to self-host is about **PII**, not cost
  (SaaS embedding generation is ¢-cheap; the real cost is HNSW RAM). See `docs/` §10.

## Repo layout

```
src/
  clean_and_enrich.py   MAIN. any CSV -> classify (minimal-LLM) -> clean -> enrich free text
                        -> writes {name}_catalog/mapping/bulk.json + embedder.pkl -> live search demo
  load_and_search.py    load artifacts into Docker OpenSearch; 3 search modes (semantic/keyword/hybrid)
  fetch_wine.py         download the real messy demo dataset (130k wine reviews)
  infer_pipeline.py     simpler inference-only variant (earlier iteration)
  partition_planner.py  balanced enum-sharding planner (for large dedicated tenants)
  make_bios.py          synthetic demo data generator
data/wine.csv           bundled 2000-row messy sample (runs offline)
docs/                   FINDINGS.md + ingestion_plan + interoperability_reliability_layer (md + pdf)
docker-compose.yml      single-node OpenSearch, security disabled for local dev
```

## How to run (dev workflow)

```bash
pip install -r requirements.txt
python3 src/clean_and_enrich.py data/wine.csv   # classify+clean+enrich; prints catalog + live search
docker compose up -d                            # OpenSearch on :9200 (no TLS/auth in dev)
python3 src/load_and_search.py                  # create index, bulk load, run the 3 search modes
```

Run commands **from the repo root**. `load_and_search.py` imports `LSA` from `clean_and_enrich`
(both in `src/`, so `python3 src/load_and_search.py` works) and reads the `*_mapping/bulk.json` +
`embedder.pkl` that `clean_and_enrich.py` writes to the cwd.

## Current state (what works)

- Classifier handles numeric / date / boolean / enum / identifier / free_text with a confidence
  gate. Verified on real wine data: **all 14 columns deterministic, 0 LLM escalations**.
- Junk column detection (`Unnamed: 0` sequential int -> flagged not-searchable).
- Free-text enrichment: extractive summary, YAKE keywords, 64-dim LSA vector.
- Generates OpenSearch mapping (typed + `knn_vector` HNSW/cosine) + bulk NDJSON + a catalog.
- Live **offline** semantic search demo runs (numpy cosine over the LSA vectors).
- `load_and_search.py` is complete, runnable OpenSearch code (not yet executed end-to-end here —
  needs a running cluster).

## Stubs / next steps (good tasks to pick up)

1. **Real embeddings** — replace `LSA` (TF-IDF+SVD) with `sentence-transformers` (`all-MiniLM-L6-v2`,
   384-dim) or a SaaS API. Add int8 quantization to the OpenSearch mapping. Needs network for model
   download. Keep the `LSA` fallback for offline.
2. **`llm_classify()`** — currently returns the deterministic best guess. Wire a real constrained
   call (masked samples + stats -> one of the fixed classes). Keep it gated + counted.
3. **Multiple fuzzy-text columns** — the enrichment loop supports it; extend the search demo to
   per-column embedders and let the user pick which text field to search.
4. **Run the Docker path end-to-end** and verify the 3 search modes against a live cluster.
5. **Theme extraction** (design in docs §11) — derive discrete, mergeable theme tags from the
   vectors (closed-vocab label-embedding + open-vocab HDBSCAN) so free text becomes facetable.
6. **Tagging + field-merge layer** (docs §4–5) — not yet coded: theme tags + `type ∩ tag` merge
   across sources, canonical registry.

## Environment notes

- Offline deps only: pandas, numpy, scikit-learn, yake, joblib. `opensearch-py` for the loader.
- `docker-compose.yml` disables the security plugin for easy local dev — **do not** use that config
  in production (enable TLS + auth; see OVH managed OpenSearch in the design docs).
- `.gitignore` excludes generated artifacts (`*_bulk.ndjson`, `*_mapping.json`, `*_catalog.json`,
  `embedder.pkl`, `wine_raw.csv`) — regenerate them by running the pipeline.
