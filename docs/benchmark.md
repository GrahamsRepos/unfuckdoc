# Canonical-inference benchmark

A measurable test basis for the ingest→canonicalize→merge engine, so every model change
(reranker, multilingual embedder, LLM escalation) can be scored for real improvement, not vibes.

## Generate & score

```bash
python3 data/make_benchmark.py        # adversarial patterns  -> data/benchmark/*.csv + benchmark_answers.json
python3 data/make_sales_sources.py    # sales multi-source    -> data/benchmark/sales/*.csv + sales_answers.json
# with the Kotlin server up on :8080:
python3 data/score_canonical.py                       # adversarial suite
python3 data/score_canonical.py sales_answers.json    # sales suite
```

The scorer loads each CSV through `/api/load_sample` and grades every column against the answer
key. Verdicts: `✓ correct`, `✗ false-positive` (stayed-identity expected, canonicalized anyway — the
costly failure), `· miss` (expected a canonical, got identity), `≠ wrong-canonical`.

## Two suites

**1. Adversarial** (`benchmark/`) — one hard pattern per file: cryptic abbreviations, multilingual
headers (es/fr/de), open-vocab synonyms, **traps** (false friends that must stay identity, e.g.
`carrier` ≠ phone), type-ambiguous values, novel domain identifiers (vin/isbn/iban).

**2. Sales multi-source** (`benchmark/sales/`) — the real reconciliation a rep does by hand: the same
people scattered across a **Salesforce export, HubSpot dump, Mailchimp list, hand-made rep
spreadsheet, LinkedIn Sales Navigator, and an Apollo/ZoomInfo lead CSV**, each naming columns
differently (`Mailing City` / `Loc` / `city`; `work_email` / `Email Address` / `e-mail`). Tests
canonical inference **and** the cross-source merge (load all six into a collection keyed on email).

## Baseline (current engine: dict + MiniLM semantic, no reranker/LLM)

| Suite | Correct | False-positives | Misses |
|---|---|---|---|
| Adversarial (42 cols) | 28 (67%) | 6 | 8 |
| Sales sources (71 cols) | 65 (92%) | 2 | 3 |

_(after the date-naming + `title` + `*_ref` alias fixes; was 27/64% and 53/84%.)_

Merge on the sales pack: **6 sources · 171 raw rows → 132 merged → 39 unique contacts**; `company`
and `email` unify across all 6.

## Failure classes this surfaced (the build backlog)

- **False positives (semantic over-reach):** `carrier→phone`, `rate→rating`, `tail_number→phone`,
  `industry→company`. → the **cross-encoder reranker** tier is aimed exactly here. _(open)_
- ~~**Alias over-breadth:** `account_ref→company`~~ **FIXED** — unambiguous `*_ref/uuid/guid`
  suffixes now resolve to `identifier` before the business-noun aliases. (`sku/hs_code→identifier`
  and `employee_count→quantity` remain — reranker/context territory.)
- ~~**Date-naming recall gap**~~ **FIXED** — date alias set now includes temporal participles/prepositions
  (`...ed on/at`, `signup`, `optin`, `dob`, `contacted`, `connected`, …), type-gated to date columns,
  so `Last Contacted / Connected On / OPTIN_TIME` unify to `date` across sources.
- ~~**Fragmented `title` vs `job_title`**~~ **FIXED** — `title` added to `job_title` aliases; job titles
  now unify across Salesforce/Apollo and HubSpot/LinkedIn.
- **Multilingual recall:** es/fr headers (`nombre/correo/pais/edad`) mostly stay identity — the
  English MiniLM can't bridge them. → **multilingual embedder** (paraphrase-multilingual / bge-m3). _(open)_
- **Multi-value consolidation stem-gate bug** (`multichannel_crm.csv`): `Mobile`/`Work Phone`/
  `Home Phone` all map to `phone` but stay **scalar** (only 1 kept) because they share no column-name
  stem, while `Email`/`Work Email`/`Personal Email` correctly array (they share `Email`). Fix: when
  N columns resolve to the *same canonical* and co-occupy rows, array-consolidate regardless of
  name stem — that's the "file 1 has 1 phone, file 2 has 3 phones → one multi-valued phone" case.

## Real public datasets to fold in later

Known-messy, representative of the sales/RevOps domain (download separately; not vendored):

- **Kaggle** — "CRM sales opportunities", "US company datasets", "customer personality analysis",
  "lead scoring" — real CRM column sprawl.
- **data.gov / EU open data** — business registries (company-name variants, addresses).
- **OpenCorporates** — company-name canonicalization at scale (Inc/Ltd/GmbH suffixes).
- **Real exports** — anonymised Salesforce/HubSpot report exports carry the truest column naming;
  add them under `data/benchmark/sales/` with an answer-key entry.
