"""
Web UI for unfuckdoc: upload any messy CSV -> see how it's classified/cleaned/enriched,
how columns unify into a canonical structure, the extracted tags, and search the fields.

Reuses the exact pipeline from clean_and_enrich.process_dataframe (no duplication of logic).
Search runs in-memory (numpy cosine + keyword scan) so the UI works with or without OpenSearch;
if a local OpenSearch is reachable we ALSO index the docs so you can see them land "in the DB".

Run:  python3 src/webapp.py   then open http://localhost:5001
"""
import os, re, json, collections
from difflib import SequenceMatcher
from flask import Flask, request, jsonify, send_from_directory
import numpy as np, pandas as pd

# import the pipeline (this file lives in src/, alongside clean_and_enrich.py)
import sys; sys.path.insert(0, os.path.dirname(__file__))
from clean_and_enrich import process_dataframe, LSA  # noqa: E402

app = Flask(__name__)
WEBUI = os.path.join(os.path.dirname(__file__), "webui")
MAX_ROWS = 5000            # cap for responsiveness on huge uploads
STATE = {}                 # latest processed dataset (single-tenant demo)
REGISTRY = {}              # persistent canonical -> {"file.column": {kind}} across ALL uploads
                           # (this is the "unify our data structure" view across differently-named CSVs)


# ----------------------------- helpers -----------------------------
def _slug(name):
    return "up_" + re.sub(r"[^a-z0-9]+", "_", os.path.splitext(name)[0].lower()).strip("_")[:40]

def _doc_no_vec(d):
    return {k: v for k, v in d.items() if not k.endswith("_vector")}

def _index_opensearch(res, index):
    """Best-effort: push docs into a local OpenSearch so they're visible in the real store.
       Never blocks the UI — returns a status dict, swallows all connection errors."""
    try:
        from opensearchpy import OpenSearch, helpers
    except Exception:
        return {"status": "no-client", "detail": "opensearch-py not installed"}
    try:
        c = OpenSearch(hosts=[{"host": "localhost", "port": 9200}], use_ssl=False,
                       timeout=2, max_retries=0)
        if not c.ping():
            return {"status": "unavailable", "detail": "no cluster on :9200"}
        if c.indices.exists(index=index):
            c.indices.delete(index=index)
        c.indices.create(index=index, body=res["mapping"])
        # drop zero-magnitude vectors (empty free-text cells) — Lucene cosine kNN rejects them
        def _clean(d):
            out = {k: v for k, v in d.items()
                   if not (k.endswith("_vector") and not any(v))}
            return out
        acts = ({"_index": index, "_id": i, "_source": _clean(d)} for i, d in enumerate(res["docs"]))
        helpers.bulk(c, acts)
        c.indices.refresh(index=index)
        return {"status": "indexed", "index": index, "count": int(c.count(index=index)["count"])}
    except Exception as e:
        return {"status": "error", "detail": str(e)[:200]}

def _update_registry(res, filename):
    """Accumulate each column's canonical mapping into the cross-file registry."""
    for c, i in res["catalog"].items():
        if not i.get("searchable"):
            continue
        entry = REGISTRY.setdefault(i["canonical"], {})
        entry[f"{filename} · {c}"] = dict(kind=i.get("kind"), os_type=i.get("os_type"),
                                          method=i.get("canonical_method"))

def _registry_view():
    """Registry sorted so genuinely-unified fields (seen in >1 file) come first."""
    out = []
    for canon, srcs in REGISTRY.items():
        files = {s.split(" · ")[0] for s in srcs}
        out.append(dict(canonical=canon, sources=[dict(ref=k, **v) for k, v in srcs.items()],
                        n_files=len(files), n_columns=len(srcs), unified=len(files) > 1))
    out.sort(key=lambda g: (not g["unified"], -g["n_files"], -g["n_columns"], g["canonical"]))
    return out

def _flatten(v):
    """Render a consolidated value (scalar / array / labeled-object array) to searchable text."""
    if v is None: return ""
    if isinstance(v, list): return " ".join(_flatten(x) for x in v)
    if isinstance(v, dict): return str(v.get("value", ""))
    return str(v)

def _field_values(v):
    """Explode a consolidated value into its individual primitive members (for facets/filters)."""
    if v is None: return []
    if isinstance(v, list):
        out = []
        for x in v: out.extend(_field_values(x))
        return out
    if isinstance(v, dict):
        return [v["value"]] if v.get("value") is not None else []
    return [v]

def _filter_match(raw_vals, needle):
    """Exact (case-insensitive, trimmed) match of a filter value against a field's members.
    For an array field, matching ANY member counts. Numeric-tolerant so 12000 == 12000.0."""
    n = str(needle).strip().lower()
    for v in raw_vals:
        if str(v).strip().lower() == n:
            return True
        try:
            if float(v) == float(needle):
                return True
        except (TypeError, ValueError):
            pass
    return False

def _facets(res):
    """Per searchable (non free-text) canonical field: type + distinct count, and the value list
    when it's low-cardinality enough to offer as a dropdown. This is what makes fields filterable."""
    facets = []
    for u in res["unified"]:
        if u["kind"] == "free_text":
            continue
        canon = u["canonical"]
        counter = collections.Counter()
        for d in res["docs"]:
            counter.update(str(x) for x in _field_values(d.get(canon)))
        facet = dict(field=canon, kind=u["kind"], os_type=u["os_type"],
                     cardinality=u["cardinality"], distinct=len(counter))
        if u["os_type"] == "keyword" and 0 < len(counter) <= 40:
            facet["values"] = counter.most_common(40)     # small enum -> offer a value dropdown
        facets.append(facet)
    return facets

def _build_search_index(res):
    """Precompute per-doc lowercased text blobs for the keyword search (over canonical fields)."""
    str_fields = [u["canonical"] for u in res["unified"] if u["os_type"] in ("keyword", "text")]
    blobs = []
    for d in res["docs"]:
        parts = [_flatten(d.get(f)) for f in str_fields]
        for c in res["fuzzy"]:
            parts.append(str(d.get(f"{c}_summary", "")))
            parts.append(" ".join(d.get(f"{c}_keywords", [])))
        blobs.append(" • ".join(parts).lower())
    return blobs

def _display_columns(res):
    """A compact, sensibly-ordered set of canonical fields for result tables."""
    order = ["full_name", "first_name", "last_name", "company", "job_title", "title",
             "email", "phone", "country", "region", "city", "amount", "rating", "date", "interests"]
    prim = {u["canonical"] for u in res["unified"] if u["kind"] != "free_text"}
    cols = [k for k in order if k in prim]
    for u in res["unified"]:                       # top up to 8 with any remaining primitive fields
        if len(cols) >= 8: break
        if u["kind"] != "free_text" and u["canonical"] not in cols:
            cols.append(u["canonical"])
    fuzzy_summ = [f"{c}_summary" for c in res["fuzzy"][:1]]
    return cols[:8] + fuzzy_summ


# ----------------------------- overview payload -----------------------------
def _overview():
    res = STATE.get("res")
    if not res:
        return {"loaded": False}
    cat = res["catalog"]
    columns = []
    for c, i in cat.items():
        columns.append(dict(name=c, kind=i.get("kind"), os_type=i.get("os_type"),
                            fill_rate=i.get("fill_rate"), margin=i.get("margin"),
                            source=i.get("source"), searchable=bool(i.get("searchable")),
                            canonical=i.get("canonical"), canonical_method=i.get("canonical_method"),
                            cardinality=i.get("cardinality"), distinct_ratio=i.get("distinct_ratio"),
                            avg_words=i.get("avg_words"), note=i.get("note")))
    kind_counts = collections.Counter(i.get("kind") for i in cat.values())

    merge = []
    for canon, cols in res["merge_groups"].items():
        merge.append(dict(canonical=canon, columns=cols, unified=len(cols) > 1))
    merge.sort(key=lambda g: (not g["unified"], -len(g["columns"]), g["canonical"]))

    # aggregate extracted tags/keywords per free-text column
    tags = {}
    all_tags = collections.Counter()
    for c in res["fuzzy"]:
        counter = collections.Counter()
        for d in res["docs"]:
            counter.update(d.get(f"{c}_keywords", []))
        tags[c] = counter.most_common(25)
        all_tags.update(counter)

    unified = sorted(res["unified"],
                     key=lambda u: (u["cardinality"] != "array", u["kind"] == "free_text", u["canonical"]))
    samples = [_doc_no_vec(d) for d in res["docs"][:5]]
    return dict(loaded=True, filename=STATE.get("filename"),
                n_rows=res["n_rows"], n_cols=res["n_cols"],
                llm_calls=res["llm_calls"], coerced=res["coerced"], quarantine=res["quarantine"],
                columns=columns, kind_counts=dict(kind_counts),
                merge_groups=merge, fuzzy=res["fuzzy"], tags=tags,
                all_tags=[t for t, _ in all_tags.most_common(40)],
                unified=unified, facets=_facets(res), mapping=res["mapping"],
                sample_docs=samples, display_columns=_display_columns(res),
                registry=_registry_view(),
                opensearch=STATE.get("opensearch", {"status": "unknown"}))


# ----------------------------- routes -----------------------------
@app.route("/")
def home():
    return send_from_directory(WEBUI, "index.html")

@app.route("/api/upload", methods=["POST"])
def upload():
    f = request.files.get("file")
    if not f or not f.filename:
        return jsonify(error="no file uploaded"), 400
    try:
        df = pd.read_csv(f, nrows=MAX_ROWS)
    except Exception as e:
        return jsonify(error=f"could not parse CSV: {e}"), 400
    if df.empty:
        return jsonify(error="CSV has no rows"), 400
    res = process_dataframe(df)
    STATE.clear()
    STATE["res"] = res
    STATE["filename"] = f.filename
    STATE["blobs"] = _build_search_index(res)
    STATE["opensearch"] = _index_opensearch(res, _slug(f.filename))
    _update_registry(res, f.filename)
    return jsonify(_overview())

DATA_DIR = os.path.join(os.path.dirname(__file__), "..", "data")

@app.route("/api/samples")
def samples():
    """List bundled CSVs so the UI can offer one-click test datasets."""
    found = []
    for root in (DATA_DIR, os.path.join(DATA_DIR, "samples")):
        if os.path.isdir(root):
            for fn in sorted(os.listdir(root)):
                if fn.lower().endswith(".csv"):
                    found.append(os.path.relpath(os.path.join(root, fn), DATA_DIR))
    return jsonify(samples=found)

@app.route("/api/load_sample", methods=["POST"])
def load_sample():
    name = (request.get_json(force=True) or {}).get("name", "")
    path = os.path.normpath(os.path.join(DATA_DIR, name))
    if not path.startswith(os.path.normpath(DATA_DIR)) or not os.path.isfile(path):
        return jsonify(error="unknown sample"), 400
    df = pd.read_csv(path, nrows=MAX_ROWS)
    res = process_dataframe(df)
    STATE.clear()
    STATE["res"] = res
    STATE["filename"] = os.path.basename(name)
    STATE["blobs"] = _build_search_index(res)
    STATE["opensearch"] = _index_opensearch(res, _slug(os.path.basename(name)))
    _update_registry(res, os.path.basename(name))
    return jsonify(_overview())

@app.route("/api/reset_registry", methods=["POST"])
def reset_registry():
    REGISTRY.clear()
    return jsonify(ok=True)

@app.route("/api/overview")
def overview():
    return jsonify(_overview())

@app.route("/api/search", methods=["POST"])
def search():
    res = STATE.get("res")
    if not res:
        return jsonify(error="upload a CSV first"), 400
    body = request.get_json(force=True) or {}
    q = (body.get("q") or "").strip()
    mode = body.get("mode", "semantic")
    size = min(int(body.get("size", 10)), 50)
    tag = (body.get("tag") or "").strip()
    filters = [f for f in (body.get("filters") or []) if f.get("field") and f.get("value") != ""]
    field = body.get("field") or (res["fuzzy"][0] if res["fuzzy"] else None)
    # empty query with no tag/filters -> match any (browse all docs)

    # a doc passes if it carries the tag AND matches every field filter (case-insensitive contains)
    def keep(i):
        d = res["docs"][i]
        if tag and not any(tag in d.get(f"{c}_keywords", []) for c in res["fuzzy"]):
            return False
        for f in filters:
            if not _filter_match(_field_values(d.get(f["field"])), f["value"]):
                return False
        return True

    hits = []
    if not q:                                          # facet/tag-only: list docs that pass filters
        for i in range(len(res["docs"])):
            if keep(i):
                hits.append(dict(score="●", doc_id=i))
            if len(hits) >= size:
                break
    elif mode == "semantic":
        if not field or field not in res["vectors"]:
            return jsonify(error="no free-text column to search semantically"), 400
        qv = res["embedders"][field].tf([q])[0]
        sims = res["vectors"][field] @ qv
        EPS = 1e-6                                       # ignore zero/negative similarity
        for i in np.argsort(-sims):                     # apply filters before truncating to size
            i = int(i)
            if sims[i] <= EPS:
                break                                   # sorted desc: once ~0, the rest are too
            if keep(i):
                hits.append(dict(score=round(float(sims[i]), 3), doc_id=i))
            if len(hits) >= size:
                break
    else:  # keyword
        terms = [t for t in re.split(r"\s+", q.lower()) if t]
        blobs = STATE["blobs"]
        scored = []
        for i, b in enumerate(blobs):
            s = sum(b.count(t) for t in terms)
            if s > 0 and keep(i):
                scored.append((s, i))
        scored.sort(key=lambda x: -x[0])
        for s, i in scored[:size]:
            hits.append(dict(score=int(s), doc_id=i))

    disp = _display_columns(res)
    for f in filters:                                  # always surface filtered fields in the table
        if f["field"] not in disp:
            disp = [f["field"]] + disp
    out = []
    for h in hits:
        d = res["docs"][h["doc_id"]]
        row = {c: d.get(c) for c in disp if c in d}
        # include primary keywords so tags are visible in results
        kw = d.get(f"{field}_keywords") if field else None
        out.append(dict(score=h["score"], row=row, keywords=(kw or [])[:5]))
    return jsonify(mode=mode, field=field, tag=tag, filters=filters, count=len(out),
                   display_columns=disp, results=out)


# ----------------------------- matching two datasets -----------------------------
_PCACHE = {}  # sample-name -> processed result (matching reprocesses bundled files on demand)

def _processed(name):
    if name not in _PCACHE:
        path = os.path.normpath(os.path.join(DATA_DIR, name))
        if not path.startswith(os.path.normpath(DATA_DIR)) or not os.path.isfile(path):
            raise FileNotFoundError(name)
        _PCACHE[name] = process_dataframe(pd.read_csv(path, nrows=MAX_ROWS))
    return _PCACHE[name]

def _norm_key(v, canon):
    """Normalize a key value before comparison (the blocking/standardization step)."""
    s = _flatten(v).strip().lower()
    if canon == "email":  return s.replace(" ", "")
    if canon == "phone":  return re.sub(r"\D", "", s)
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9 ]", "", s)).strip()

def _sim(a, b):
    """Fuzzy similarity in [0,1]: 1.0 only when normalized values are identical; variants
    ('Acme Logistics' vs 'Acme Logistics Inc', 'Harborview Bank' vs 'Harborview Bnk') score high
    but below 1.0. Token-sort ratio makes it robust to word order and extra suffix words."""
    if not a or not b: return 0.0
    if a == b: return 1.0
    base = SequenceMatcher(None, a, b).ratio()
    sa, sb = " ".join(sorted(a.split())), " ".join(sorted(b.split()))
    return round(max(base, SequenceMatcher(None, sa, sb).ratio()), 3)

def _shared_keys(ra, rb):
    """Canonical fields present in BOTH datasets that make sensible join keys (not free text)."""
    def prim(res): return {u["canonical"]: u for u in res["unified"] if u["kind"] != "free_text"}
    A, B = prim(ra), prim(rb)
    keys = []
    for canon in A.keys() & B.keys():
        # uniqueness of the key in A (a good key is close to unique)
        vals = [_norm_key(d.get(canon), canon) for d in ra["docs"] if d.get(canon) is not None]
        distinct = len(set(vals)); fill = len(vals)
        keys.append(dict(field=canon, kind=A[canon]["kind"],
                         uniqueness=round(distinct / fill, 3) if fill else 0,
                         fill_a=fill, fill_b=sum(1 for d in rb["docs"] if d.get(canon) is not None)))
    # string keys (email/phone/id/name) make better join keys than raw numbers/dates
    keys.sort(key=lambda k: (k["kind"] == "numeric", k["kind"] == "date",
                             -k["uniqueness"], -k["fill_a"]))
    return keys

def _match_display(res):
    cols = _display_columns(res)
    return [c for c in cols if not c.endswith("_summary")][:4]

@app.route("/api/match_candidates", methods=["POST"])
def match_candidates():
    body = request.get_json(force=True) or {}
    try:
        ra, rb = _processed(body["a"]), _processed(body["b"])
    except Exception as e:
        return jsonify(error=f"could not load datasets: {e}"), 400
    return jsonify(keys=_shared_keys(ra, rb))

@app.route("/api/match", methods=["POST"])
def match():
    body = request.get_json(force=True) or {}
    a, b = body.get("a"), body.get("b")
    key = body.get("key")
    threshold = float(body.get("threshold", 0.85))
    if a == b:
        return jsonify(error="pick two different datasets"), 400
    try:
        ra, rb = _processed(a), _processed(b)
    except Exception as e:
        return jsonify(error=f"could not load datasets: {e}"), 400
    if not key:
        cands = _shared_keys(ra, rb)
        if not cands:
            return jsonify(error="no shared canonical field to match on"), 400
        key = cands[0]["field"]

    # block dataset B by first char of the normalized key to keep it fast
    blocks = collections.defaultdict(list)
    for j, d in enumerate(rb["docs"]):
        nk = _norm_key(d.get(key), key)
        if nk: blocks[nk[0]].append((j, nk))

    dispA, dispB = _match_display(ra), _match_display(rb)
    pairs, matched_a, exact = [], 0, 0
    matched_b_idx = set()
    for i, d in enumerate(ra["docs"]):
        nk = _norm_key(d.get(key), key)
        if not nk:
            continue
        best = (0.0, -1)
        for j, bk in blocks.get(nk[0], []):        # candidates sharing the first char
            s = _sim(nk, bk)
            if s > best[0]:
                best = (s, j)
        if best[0] >= threshold and best[1] >= 0:
            matched_a += 1; matched_b_idx.add(best[1])
            if best[0] >= 1.0: exact += 1
            if len(pairs) < 500:
                db = rb["docs"][best[1]]
                pairs.append(dict(sim=best[0],
                                  a={c: _flatten(d.get(c)) for c in dispA},
                                  b={c: _flatten(db.get(c)) for c in dispB}))
    # show the interesting fuzzy (sub-1.0) matches first, then exacts
    pairs.sort(key=lambda p: (p["sim"] >= 1.0, -p["sim"]))
    pairs = pairs[:25]
    fill_a = sum(1 for d in ra["docs"] if _norm_key(d.get(key), key))
    return jsonify(key=key, threshold=threshold,
                   rows_a=ra["n_rows"], rows_b=rb["n_rows"],
                   keyed_a=fill_a,
                   matched=matched_a, exact=exact,
                   unmatched_a=fill_a - matched_a,
                   unmatched_b=rb["n_rows"] - len(matched_b_idx),
                   display_a=dispA, display_b=dispB, pairs=pairs)


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "5001"))
    print(f"unfuckdoc web UI -> http://localhost:{port}")
    app.run(host="127.0.0.1", port=port, debug=False)
