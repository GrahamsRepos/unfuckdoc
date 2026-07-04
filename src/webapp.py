"""
Web UI for unfuckdoc: upload any messy CSV -> see how it's classified/cleaned/enriched,
how columns unify into a canonical structure, the extracted tags, and search the fields.

Reuses the exact pipeline from clean_and_enrich.process_dataframe (no duplication of logic).
Search runs in-memory (numpy cosine + keyword scan) so the UI works with or without OpenSearch;
if a local OpenSearch is reachable we ALSO index the docs so you can see them land "in the DB".

Run:  python3 src/webapp.py   then open http://localhost:5001
"""
import os, re, json, collections
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

def _build_search_index(res):
    """Precompute per-doc lowercased text blobs for the keyword search."""
    str_cols = [c for c, i in res["catalog"].items()
                if i.get("searchable") and i["os_type"] in ("keyword", "text")]
    blobs = []
    for d in res["docs"]:
        parts = [str(d.get(c, "")) for c in str_cols]
        for c in res["fuzzy"]:
            parts.append(str(d.get(f"{c}_summary", "")))
            parts.append(" ".join(d.get(f"{c}_keywords", [])))
        blobs.append(" • ".join(parts).lower())
    return blobs

def _display_columns(res):
    """A compact, sensibly-ordered set of columns for result tables."""
    order = ["full_name", "first_name", "last_name", "company", "job_title", "title",
             "email", "phone", "country", "region", "city", "amount", "rating", "date"]
    cat = res["catalog"]
    by_canon = {}  # representative column per canonical = the best-covered one
    for c, i in cat.items():
        if i.get("searchable") and i["kind"] != "free_text":
            cur = by_canon.get(i["canonical"])
            if cur is None or (i.get("fill_rate", 0) > cat[cur].get("fill_rate", 0)):
                by_canon[i["canonical"]] = c
    cols = [by_canon[k] for k in order if k in by_canon]
    # fill up to 8 with any remaining primitive (non free-text) searchable columns
    for c, i in cat.items():
        if len(cols) >= 8: break
        if i.get("searchable") and c not in cols and i["kind"] != "free_text":
            cols.append(c)
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
    for c in res["fuzzy"]:
        counter = collections.Counter()
        for d in res["docs"]:
            counter.update(d.get(f"{c}_keywords", []))
        tags[c] = counter.most_common(25)

    samples = [_doc_no_vec(d) for d in res["docs"][:5]]
    return dict(loaded=True, filename=STATE.get("filename"),
                n_rows=res["n_rows"], n_cols=res["n_cols"],
                llm_calls=res["llm_calls"], coerced=res["coerced"], quarantine=res["quarantine"],
                columns=columns, kind_counts=dict(kind_counts),
                merge_groups=merge, fuzzy=res["fuzzy"], tags=tags,
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
    field = body.get("field") or (res["fuzzy"][0] if res["fuzzy"] else None)
    if not q:
        return jsonify(error="empty query"), 400

    hits = []
    if mode == "semantic":
        if not field or field not in res["vectors"]:
            return jsonify(error="no free-text column to search semantically"), 400
        qv = res["embedders"][field].tf([q])[0]
        sims = res["vectors"][field] @ qv
        idx = np.argsort(-sims)[:size]
        for i in idx:
            hits.append(dict(score=round(float(sims[i]), 3), doc_id=int(i)))
    else:  # keyword
        terms = [t for t in re.split(r"\s+", q.lower()) if t]
        blobs = STATE["blobs"]
        scored = []
        for i, b in enumerate(blobs):
            s = sum(b.count(t) for t in terms)
            if s > 0:
                scored.append((s, i))
        scored.sort(key=lambda x: -x[0])
        for s, i in scored[:size]:
            hits.append(dict(score=int(s), doc_id=i))

    disp = res["display_columns"] = _display_columns(res)
    out = []
    for h in hits:
        d = res["docs"][h["doc_id"]]
        row = {c: d.get(c) for c in disp if c in d}
        # include primary keywords so tags are visible in results
        kw = d.get(f"{field}_keywords") if field else None
        out.append(dict(score=h["score"], row=row, keywords=(kw or [])[:5]))
    return jsonify(mode=mode, field=field, count=len(out),
                   display_columns=disp, results=out)


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "5001"))
    print(f"unfuckdoc web UI -> http://localhost:{port}")
    app.run(host="127.0.0.1", port=port, debug=False)
