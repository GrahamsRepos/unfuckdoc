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
| Adversarial (42 cols) | 27 (64%) | 7 | 8 |
| Sales sources (63 cols) | 53 (84%) | 2 | 1 (+7 date/loc misses) |

Merge on the sales pack: **6 sources · 171 raw rows → 132 merged → 39 unique contacts**; `company`
and `email` unify across all 6.

## Failure classes this surfaced (the build backlog)

- **False positives (semantic over-reach):** `carrier→phone`, `rate→rating`, `tail_number→phone`,
  `industry→company`. → the **cross-encoder reranker** tier is aimed exactly here.
- **Alias over-breadth:** `account_ref→company` (`account` alias too broad), `sku/hs_code→identifier`,
  `employee_count→quantity`.
- **Date-naming recall gap:** `Last Contacted / Connected On / OPTIN_TIME / when / *_on / *_at` stay
  identity — the date alias set has no verb-participle patterns, so dates don't unify across sources.
- **Multilingual recall:** es/fr headers (`nombre/correo/pais/edad`) mostly stay identity — the
  English MiniLM can't bridge them. → **multilingual embedder** (paraphrase-multilingual / bge-m3).
- **Fragmented synonyms hurt merge:** `title` vs `job_title` stay separate canonicals, so job titles
  don't unify across Salesforce/Apollo vs HubSpot/LinkedIn.
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
