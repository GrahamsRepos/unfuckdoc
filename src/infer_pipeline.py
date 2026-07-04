"""
POC: CSV/Excel -> type inference on a 5% sample -> catalog -> OpenSearch mapping + bulk docs.

Decision rules (matching the spec):
  enumerable / low-cardinality  -> keyword facet   (flagged, enumerated)
  free text                     -> text + vector   (vectorised for semantic search)
  numeric                       -> long / double    (stored as numbers)
  + detected date / boolean / email as bonus typed slots
"""
import sys, json, math, hashlib, re
import pandas as pd
import numpy as np

# ---- tunables -------------------------------------------------------------
SAMPLE_FRAC       = 0.05    # take a 5% sample for inference
SAMPLE_FLOOR      = 50      # ...but never fewer than this many rows
TYPE_CONFIDENCE   = 0.90    # >=90% of non-null cells must fit for a clean type
ENUM_MAX_UNIQUE   = 25      # <= this many distinct values => enumerable (facet)
ENUM_MAX_RATIO    = 0.30    # ...and distinct/total below this
TEXT_MIN_AVG_LEN  = 25      # avg string length above this => likely free text
EMBED_DIM         = 384     # embedding size (stub)

# ---- 1. type detectors (vote per cell) ------------------------------------
BOOL_SET = {"true","false","yes","no","0","1","t","f","y","n"}
EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")

def is_int(v):
    try: int(str(v).replace(",","").strip()); return True
    except: return False
def is_float(v):
    try: float(str(v).replace(",","").strip()); return True
    except: return False
def is_date(v):
    try: pd.to_datetime(str(v), errors="raise"); return True
    except: return False
def is_bool(v):  return str(v).strip().lower() in BOOL_SET
def is_email(v): return bool(EMAIL_RE.match(str(v).strip()))

# ---- 2. infer one column from its sampled, non-null values ----------------
def infer_column(name, values):
    vals = [v for v in values if str(v).strip() != "" and not (isinstance(v,float) and math.isnan(v))]
    n = len(vals)
    if n == 0:
        return dict(type="empty", searchable=False)

    votes = {
        "boolean": sum(is_bool(v)  for v in vals) / n,
        "integer": sum(is_int(v)   for v in vals) / n,
        "float":   sum(is_float(v) for v in vals) / n,
        "date":    sum(is_date(v)  for v in vals) / n,
        "email":   sum(is_email(v) for v in vals) / n,
    }
    uniq  = len(set(map(str, vals)))
    ratio = uniq / n
    avglen = float(np.mean([len(str(v)) for v in vals]))
    samples = list(dict.fromkeys(map(str, vals)))[:5]
    base = dict(cardinality=uniq, distinct_ratio=round(ratio,3),
                avg_len=round(avglen,1), samples=samples)

    # order matters: most specific -> most general
    if votes["boolean"] >= TYPE_CONFIDENCE and uniq <= 3:
        return dict(base, type="boolean", slot="bool", os_type="boolean",
                    decision="store boolean", facet_ui="toggle",
                    searchable=True, vectorize=False, confidence=round(votes["boolean"],2))

    if votes["integer"] >= TYPE_CONFIDENCE:
        return dict(base, type="integer", slot="num", os_type="long",
                    decision="store number", facet_ui="range_slider",
                    searchable=True, vectorize=False, confidence=round(votes["integer"],2))

    if votes["float"] >= TYPE_CONFIDENCE:
        return dict(base, type="float", slot="num", os_type="double",
                    decision="store number", facet_ui="range_slider",
                    searchable=True, vectorize=False, confidence=round(votes["float"],2))

    if votes["date"] >= TYPE_CONFIDENCE:
        return dict(base, type="date", slot="date", os_type="date",
                    decision="store date", facet_ui="date_range",
                    searchable=True, vectorize=False, confidence=round(votes["date"],2))

    if votes["email"] >= TYPE_CONFIDENCE:
        return dict(base, type="email", slot="str", os_type="keyword",
                    decision="keyword (identifier)", facet_ui="typeahead",
                    searchable=True, vectorize=False, confidence=round(votes["email"],2))

    # strings: FREE TEXT wins first — long average length means prose, vectorise it,
    # regardless of cardinality (a repeated-but-long field is still prose).
    if avglen >= TEXT_MIN_AVG_LEN:
        return dict(base, type="free_text", slot="txt", os_type="text",
                    decision="FREE TEXT -> vectorise (semantic)", facet_ui="search_box",
                    searchable=True, vectorize=True, confidence=1.0)

    # short + low-cardinality => enumerable facet
    if uniq <= ENUM_MAX_UNIQUE and ratio <= ENUM_MAX_RATIO:
        return dict(base, type="categorical", slot="str", os_type="keyword",
                    decision="ENUMERABLE -> keyword facet", facet_ui="checkbox_list",
                    searchable=True, vectorize=False, confidence=1.0)

    return dict(base, type="keyword", slot="str", os_type="keyword",
                decision="keyword (high-card id)", facet_ui="typeahead",
                searchable=True, vectorize=False, confidence=1.0)

def slugify(name):
    return re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")

# ---- 3. embedding stub (swap for a real model in production) --------------
def embed(text: str):
    # POC STUB: deterministic pseudo-vector so the pipeline runs offline.
    # Production: sentence-transformers, OpenAI, Cohere, etc. -> real embeddings.
    h = hashlib.sha256(text.encode()).digest()
    rng = np.random.default_rng(int.from_bytes(h[:8], "little"))
    v = rng.standard_normal(EMBED_DIM)
    return (v / np.linalg.norm(v)).round(5).tolist()

# ---- 4. pipeline ----------------------------------------------------------
def run(path, tenant_id="t_demo", index="profiles_pool"):
    df = pd.read_excel(path) if path.lower().endswith((".xlsx",".xls")) else pd.read_csv(path)
    n_total = len(df)
    n_sample = max(SAMPLE_FLOOR, int(n_total * SAMPLE_FRAC))
    sample = df.sample(n=min(n_sample, n_total), random_state=1)
    print(f"loaded {n_total} rows x {len(df.columns)} cols | inferring on {len(sample)}-row sample ({SAMPLE_FRAC:.0%}, floor {SAMPLE_FLOOR})\n")

    catalog = []
    for col in df.columns:
        info = infer_column(col, sample[col].tolist())
        info["source_column"] = col
        info["key"] = slugify(col)
        catalog.append(info)

    # ---- print the inferred catalog -------------------------------------
    print(f"{'COLUMN':<16}{'TYPE':<12}{'CARD':>5}  DECISION")
    print("-"*72)
    for c in catalog:
        print(f"{c['source_column']:<16}{c.get('type',''):<12}{c.get('cardinality',0):>5}  {c.get('decision','')}")

    # ---- 5. generate OpenSearch mapping from the catalog ----------------
    props = {"tenant_id": {"type": "keyword"}}
    for c in catalog:
        if not c.get("searchable"): continue
        props[c["key"]] = {"type": c["os_type"]}
        if c.get("vectorize"):
            props[f"{c['key']}_vector"] = {"type": "knn_vector", "dimension": EMBED_DIM,
                                           "method": {"name":"hnsw","engine":"lucene","space_type":"cosinesimil"}}
    mapping = {"settings": {"index.knn": True}, "mappings": {"properties": props}}

    # ---- 6. transform rows -> bulk docs (embed the free-text fields) -----
    vector_cols = [c for c in catalog if c.get("vectorize")]
    with open("bulk.ndjson", "w") as bf:
        for rid, row in df.iterrows():
            doc = {"tenant_id": tenant_id}
            for c in catalog:
                if not c.get("searchable"): continue
                raw = row[c["source_column"]]
                if pd.isna(raw) or str(raw).strip()=="":   # junk / missing cell -> skip field
                    continue
                key, t = c["key"], c["type"]
                try:
                    if t in ("integer",): doc[key] = int(str(raw).replace(",",""))
                    elif t in ("float",): doc[key] = float(str(raw).replace(",",""))
                    elif t == "boolean":  doc[key] = str(raw).strip().lower() in {"true","yes","1","t","y"}
                    elif t == "date":     doc[key] = pd.to_datetime(raw).strftime("%Y-%m-%d")
                    else:                 doc[key] = str(raw)
                except Exception:
                    continue  # cell doesn't fit the column's trend -> drop it
            for c in vector_cols:
                val = row[c["source_column"]]
                if pd.notna(val) and str(val).strip():
                    doc[f"{c['key']}_vector"] = embed(str(val))
            bf.write(json.dumps({"index": {"_index": index, "_id": f"{tenant_id}_{rid}"}}) + "\n")
            bf.write(json.dumps(doc) + "\n")

    json.dump(catalog, open("catalog.json","w"), indent=2)
    json.dump(mapping, open("opensearch_mapping.json","w"), indent=2)

    n_vec = sum(1 for c in vector_cols)
    print(f"\nwrote catalog.json ({len(catalog)} points) | opensearch_mapping.json "
          f"({len(props)} fields, {n_vec} vector) | bulk.ndjson ({n_total} docs)")

if __name__ == "__main__":
    run(sys.argv[1] if len(sys.argv) > 1 else "people.csv")
