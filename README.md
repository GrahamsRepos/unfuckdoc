# unfuckdoc

Turn **any messy CSV** into a searchable OpenSearch index — with **minimal LLM**.


> **Continuing in Claude Code?** Read `CLAUDE.md` first — it's the project context, current state, and next steps.

It infers each field's type from a statistical sample, cleans it (null-aware), routes primitives
to typed OpenSearch fields, and for **free-text** columns it **summarises, extracts keywords, and
vectorises** them — then serves **semantic + keyword + hybrid** search. An LLM is only ever called
on the *ambiguous residual*, and every escalation is counted so the cost stays visible (on the
bundled real-world dataset: **0 LLM calls**).

## Quickstart

```bash
pip install -r requirements.txt

# 1. (optional) grab the real messy demo dataset — 130k wine reviews
python3 src/fetch_wine.py                       # a 2000-row sample is already in data/wine.csv

# 2. classify + clean + enrich -> OpenSearch-ready artifacts + a LIVE offline search demo
python3 src/clean_and_enrich.py data/wine.csv   # writes wine_catalog/mapping/bulk.json + embedder.pkl

# 3. real OpenSearch in Docker, then load + search
docker compose up -d                            # single-node OpenSearch on :9200 (security off, dev)
python3 src/load_and_search.py                  # create index, bulk load, run 3 search modes
```

## What it does

| Field kind | OpenSearch | Handling |
|---|---|---|
| numeric / date / boolean | double / date / boolean | typed, coerced (`$`,`,` stripped) |
| enum (low-card) / identifier | keyword | facet / exact-match |
| **free_text** (many words) | text | **`_summary` + `_keywords[]` + `_vector` (kNN/HNSW)** |
| junk (row index, empty) | — | flagged not-searchable |

- **Minimal LLM** — deterministic classifier with a confidence-margin gate; `llm_classify()` fires
  only below the margin and is counted. Swap the stub for a real constrained call.
- **Null-aware** — sparse columns stay first-class (`fill_rate` recorded); nulls are coverage, not junk.
- **Offline stand-ins, swappable** — vectorise = TF-IDF+LSA (→ sentence-transformers / OpenAI /
  Voyage); summarise = extractive TextRank (→ LLM); keywords = YAKE.

## Layout

```
src/   clean_and_enrich.py   any CSV -> clean -> enrich -> OpenSearch-ready + live search demo
       load_and_search.py    load into Docker OpenSearch, run semantic/keyword/hybrid
       fetch_wine.py         download the real dirty demo dataset
       infer_pipeline.py     simpler inference-only variant
       partition_planner.py  balanced enum-sharding planner (for large tenants)
       make_bios.py          synthetic demo data generator
data/  wine.csv              bundled 2000-row messy sample (runs offline)
docs/  design notes (pipeline, interoperability & reliability, findings) in md + pdf
docker-compose.yml           single-node OpenSearch for local dev
```

## The three search modes (`src/load_and_search.py`)

1. **Semantic** — kNN over `description_vector` ("dark chocolate and bold tannins" → Cabernets).
2. **Keyword-tag + primitive filter** — `country=Italy AND points>=92 AND keyword="black cherry"`.
3. **Hybrid** — BM25 on `description_summary` + kNN, RRF-fused.

## License

MIT.
