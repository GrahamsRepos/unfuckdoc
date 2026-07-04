"""
Real-world CSV -> CLEAN (type inference + coercion + quarantine, null-aware) ->
minimal-LLM classification (only ambiguous columns) -> fuzzy-text enrichment -> OpenSearch-ready.

LLM is used SPARINGLY: the deterministic classifier resolves everything above a confidence
MARGIN; only low-margin columns escalate. We count escalations so the LLM cost is visible.
"""
import sys, json, re
import numpy as np, pandas as pd
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.decomposition import TruncatedSVD
from sklearn.metrics.pairwise import cosine_similarity
import yake

MARGIN = 0.25          # confidence-margin gate; below this -> escalate to LLM
LLM_CALLS = {"n": 0}   # visible counter

BOOL={"true","false","yes","no"}; EMAIL=re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
def _num(v):
    try: float(str(v).replace(",","").replace("$","")); return True
    except: return False
def _date(v):
    try: pd.to_datetime(str(v),errors="raise"); return True
    except: return False

def score_classes(vals):
    v=[str(x) for x in vals]; n=len(v) or 1
    uniq=len(set(v)); ratio=uniq/n
    avglen=float(np.mean([len(x) for x in v])); avgwords=float(np.mean([len(x.split()) for x in v]))
    f=lambda fn: sum(fn(x) for x in v)/n
    numf=f(_num)
    s={}
    s["numeric"]   = numf                                              # numbers win, even low-cardinality
    s["boolean"]   = f(lambda x:x.lower() in BOOL) if uniq<=3 else 0
    s["date"]      = f(_date) if (numf<0.5 and avgwords<=3 and avglen>=6) else 0
    s["free_text"] = min(1.0, avgwords/25) if avgwords>=12 else 0       # prose = many WORDS, not just length
    # any remaining short string is a keyword field (enum if low-card, else identifier)
    is_str = numf<0.5 and s["free_text"]==0
    s["enum"]       = 0.8 if (is_str and uniq<=50 and ratio<=0.6) else 0
    s["identifier"] = 0.7 if (is_str and s["enum"]==0) else 0
    return s, dict(cardinality=uniq, distinct_ratio=round(ratio,3), avg_len=round(avglen,1), avg_words=round(avgwords,1))

OS={"boolean":"boolean","numeric":"double","date":"date","enum":"keyword",
    "identifier":"keyword","free_text":"text"}

def classify(col, populated):
    """Deterministic classify with confidence margin; escalate to LLM only if ambiguous."""
    if not populated: return dict(kind="empty", os_type=None, source="n/a")
    scores, stats = score_classes(populated)
    ranked=sorted(scores.items(), key=lambda kv:-kv[1])
    (top,ts),(second,ss)=ranked[0],ranked[1]
    if ts-ss >= MARGIN:
        kind,source=top,"deterministic"
    else:
        LLM_CALLS["n"]+=1                       # <-- the ONLY place an LLM would be called
        kind,source=llm_classify(col,populated,scores),"LLM"
    return dict(kind=kind, os_type=OS.get(kind), margin=round(ts-ss,2), source=source, **stats)

def llm_classify(col, sample, scores):
    """STUB for a real, constrained LLM call. Runs only on the ambiguous residual.
       Production: send column name + masked samples + stats, get one of the fixed classes back."""
    return max(scores, key=scores.get)          # POC fallback = deterministic best guess

# ---- canonical column-name unification (deterministic, offline) ----
# Map synonymous column NAMES (Price/Cost/Amt, email/e-mail, qty/quantity...) onto one canonical
# field so data from different CSVs unifies into a single structure. Gated by TYPE COMPATIBILITY:
# a name that looks like "price" but is free_text will NOT be forced onto the numeric canonical
# (never force a confident lie — degrade to the column's own name instead).
_STR={"enum","identifier","free_text"}
CANON=[   # (canonical, {alias tokens}, {compatible kinds} or None=any)
 ("amount",     {"price","cost","amt","amount","value","total","subtotal","fee","charge","salary",
                 "revenue","spend","balance","budget","mrr","arr","payment","paid"}, {"numeric"}),
 ("quantity",   {"qty","quantity","count","units","unit","stock","inventory"}, {"numeric"}),
 ("rating",     {"rating","score","points","point","stars","star","rank","grade"}, {"numeric"}),
 ("age",        {"age","years"}, {"numeric"}),
 ("email",      {"email","emails","mail","e"}, _STR),
 ("phone",      {"phone","tel","telephone","mobile","cell","fax","msisdn"}, _STR),
 ("first_name", {"firstname","fname","givenname","given","forename","first"}, _STR),
 ("last_name",  {"lastname","lname","surname","familyname","family","last"}, _STR),
 ("full_name",  {"name","fullname","contact","person","taster"}, _STR),
 ("company",    {"company","organization","organisation","org","employer","business","vendor",
                 "supplier","winery","brand","account","firm"}, _STR),
 ("job_title",  {"jobtitle","role","position","designation","occupation"}, _STR),
 ("country",    {"country","cntry","ctry","nation","countrycode"}, _STR),
 ("region",     {"region","state","province","county","territory","area"}, _STR),
 ("interests",  {"interest","interests","hobby","hobbies","topic","topics"}, {"enum","identifier"}),
 ("city",       {"city","town","municipality"}, _STR),
 ("address",    {"address","addr","street"}, None),
 ("postal_code",{"zip","zipcode","postal","postcode","postalcode"}, _STR),
 ("date",       {"date","datetime","timestamp","created","updated","modified","day","time"}, {"date","numeric"}),
 ("url",        {"url","link","website","site","web","homepage","handle"}, _STR),
 ("identifier", {"id","identifier","uuid","guid","key","ref","reference","sku","code"}, {"identifier","numeric"}),
 ("gender",     {"gender","sex"}, {"enum"}),
 ("currency",   {"currency","ccy"}, {"enum","identifier"}),
 ("description",{"description","desc","notes","note","comment","comments","summary","bio",
                 "review","text","details","detail","remarks","about","content"}, {"free_text","identifier","enum"}),
]
def _name_tokens(name):
    s=re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", str(name))          # split camelCase
    parts=re.split(r"[^A-Za-z0-9]+", s.lower())                   # split snake/space/hyphen
    return [p for p in parts if p and not p.isdigit()]            # drop empties & pure numbers (region_1 -> region)
def canonicalize(name, kind):
    """Return (canonical, confidence, method). Best alias hit wins; type-gated; else identity."""
    toks=set(_name_tokens(name))
    best=None
    for canon,aliases,types in CANON:
        if types is not None and kind not in types: continue     # type ∩ tag: skip incompatible
        hit=toks & aliases
        if hit:
            alen=max(len(a) for a in hit)                        # prefer the more specific (longer) alias
            if best is None or alen>best[0]: best=(alen, canon)
    if best: return best[1], 0.9, "alias"
    ident="_".join(_name_tokens(name)) or str(name).lower()      # unmatched -> its own normalized name
    return ident, 0.0, "identity"

# ---- fuzzy-text enrichers (offline; swap for real models) ----
def summarise(t, k=1):
    ss=[s.strip() for s in re.split(r'(?<=[.!?])\s+', t) if s.strip()]
    if len(ss)<=k: return t.strip()
    try:
        tf=TfidfVectorizer().fit_transform(ss); c=cosine_similarity(tf,tf).sum(1)
        return " ".join(ss[i] for i in sorted(sorted(range(len(ss)),key=lambda i:-c[i])[:k]))
    except: return ss[0]
_kw=yake.KeywordExtractor(n=2, top=6, dedupLim=0.8)
def keywords(t): return [k for k,_ in _kw.extract_keywords(t)]
class LSA:
    def __init__(s,d=64): s.v=TfidfVectorizer(stop_words="english",max_features=6000); s.s=TruncatedSVD(d,random_state=1); s.dim=d
    def fit(s,c):
        X=s.v.fit_transform(c); s.dim=min(s.dim,X.shape[1]-1); s.s.n_components=s.dim
        s.M=s._n(s.s.fit_transform(X)); return s
    def tf(s,t): return s._n(s.s.transform(s.v.transform(t)))
    @staticmethod
    def _n(M): n=np.linalg.norm(M,axis=1,keepdims=True); n[n==0]=1; return M/n

# ---- field consolidation: coalesce-to-scalar vs gather-to-array (decided by fill co-occupancy) ----
def _clean_cell(raw, kind):
    """Coerce one populated cell to its typed value; returns (value, was_coerced) or raises."""
    if kind == "numeric":
        cln = raw.replace(",", "").replace("$", ""); return float(cln), (cln != raw)
    if kind == "boolean":
        return raw.lower() in {"true", "yes"}, False
    if kind == "date":
        return pd.to_datetime(raw).strftime("%Y-%m-%d"), False
    return raw, False

def _qualifiers(cols):
    """Derive each column's distinguishing suffix by stripping the common prefix/suffix from the
    RAW names. Positional differentiators (1/2/3) -> drop; semantic ones (home/work) -> keep as labels.
    Also report whether the columns share a real STEM: arrays should come from SLOTTED columns
    (phone_home/work, interest_1/2/3), not from arbitrary synonyms that merely share a canonical."""
    if len(cols) == 1:
        return {cols[0]: None}, "single", False
    def _lcp(strs):
        lo, hi = min(strs), max(strs); i = 0
        while i < len(lo) and lo[i] == hi[i]: i += 1
        return lo[:i]
    pre = _lcp(cols); suf = _lcp([c[::-1] for c in cols])[::-1]
    stem = re.sub(r"[^A-Za-z0-9]+", "", pre) + re.sub(r"[^A-Za-z0-9]+", "", suf)
    has_stem = len(stem) >= 3                          # a shared word-ish stem => these are slots
    labels = {}; positional = True
    for c in cols:
        q = c[len(pre): len(c) - len(suf)] if len(pre) + len(suf) < len(c) else ""
        q = re.sub(r"[^A-Za-z0-9]+", " ", q).strip()
        labels[c] = q
        if q and not q.isdigit(): positional = False
    return labels, ("positional" if positional else "semantic"), has_stem

def _decide_cardinality(cols, df, kind, n):
    """Read per-row co-occupancy: exclusive fill -> scalar (alternatives); concurrent with distinct
    values -> array (multiples); concurrent but same value -> scalar (redundant duplicate)."""
    if len(cols) == 1 or kind == "free_text":
        return "scalar"
    concurrent = distinct = 0
    for _, row in df[cols].iterrows():
        vals = [str(row[c]).strip() for c in cols if pd.notna(row[c]) and str(row[c]).strip() != ""]
        if len(vals) >= 2:
            concurrent += 1
            if len(set(vals)) >= 2: distinct += 1
    if not n or concurrent / n < 0.02:            # essentially never co-populated -> alternatives
        return "scalar"
    return "array" if distinct / max(concurrent, 1) >= 0.5 else "scalar"

def process_dataframe(df, vec_dim=64, sample=1500):
    """In-memory, side-effect-free variant of run(): classify -> clean -> enrich a DataFrame
    and return everything the web UI needs. Mirrors run() exactly but returns data instead of
    printing / writing files. Resets the per-run LLM counter so escalations are scoped to this call."""
    LLM_CALLS["n"]=0
    n=len(df)

    # ---- classify every column (minimal LLM) with null-aware fill-rate ----
    catalog={}
    for c in df.columns:
        col=df[c]
        populated=[x for x in col.tolist() if pd.notna(x) and str(x).strip()!=""]
        info=classify(c, populated[:sample])
        info["fill_rate"]=round(len(populated)/n,3) if n else 0.0
        if info.get("kind")=="numeric" and info.get("distinct_ratio",0)>0.99 and c.lower().startswith("unnamed"):
            info["searchable"]=False; info["note"]="looks like a row index — excluded"
        else:
            info["searchable"]=info["os_type"] is not None
        catalog[c]=info
    fuzzy=[c for c,i in catalog.items() if i.get("kind")=="free_text"]

    # ---- canonical name unification (unify columns across CSVs into one structure) ----
    merge_groups={}
    for c,i in catalog.items():
        canon,conf,method=canonicalize(c, i.get("kind"))
        i["canonical"]=canon; i["canonical_confidence"]=conf; i["canonical_method"]=method
        merge_groups.setdefault(canon, []).append(c)

    # ---- decide consolidation shape per canonical group (scalar vs array) ----
    sgroups = {canon: [c for c in cols if catalog[c].get("searchable")]
               for canon, cols in merge_groups.items()}
    sgroups = {k: v for k, v in sgroups.items() if v}
    shape = {}   # canonical -> how its columns consolidate
    for canon, cols in sgroups.items():
        kinds = [catalog[c]["kind"] for c in cols]
        kind = "free_text" if all(k == "free_text" for k in kinds) \
               else next((k for k in kinds if k != "free_text"), kinds[0])
        labels, style, has_stem = _qualifiers(cols)
        card = _decide_cardinality(cols, df, kind, n)
        if card == "array" and not has_stem:
            card = "scalar"          # synonyms wrongly co-populated -> coalesce, don't array-ify
        shape[canon] = dict(cardinality=card, style=(style if card == "array" else "single"),
                            labels=labels, kind=kind, os_type=OS.get(kind), sources=cols)
    fuzzy_canon = [canon for canon, sh in shape.items() if sh["kind"] == "free_text"]

    # ---- consolidate every row into canonical-keyed docs (unify structure) ----
    coerced = quarantine = 0
    docs = []
    ftext = {canon: [] for canon in fuzzy_canon}    # coalesced free text per canonical, per row
    for _, row in df.iterrows():
        doc = {}
        for canon, sh in shape.items():
            kind = sh["kind"]
            items = []                              # (label, cleaned_value) for populated cells
            for c in sh["sources"]:
                v = row[c]
                if pd.isna(v) or str(v).strip() == "": continue     # null = coverage, not a member
                try:
                    cv, was = _clean_cell(str(v), kind); coerced += was
                except Exception:
                    quarantine += 1; continue                        # cell fails its type -> quarantine
                items.append((sh["labels"].get(c), cv))
            if kind == "free_text":
                txt = str(items[0][1]) if items else ""             # survivorship: first non-null
                ftext[canon].append(txt)
                if txt: doc[canon] = txt
                continue
            if not items: continue
            seen = set(); dedup = []                                 # dedup, preserve order
            for lab, cv in items:
                if cv in seen: continue
                seen.add(cv); dedup.append((lab, cv))
            if sh["cardinality"] == "scalar":
                doc[canon] = dedup[0][1]                             # coalesce to one value
            elif sh["style"] == "semantic":
                doc[canon] = [{"type": lab or "other", "value": cv} for lab, cv in dedup]
            else:                                                    # positional array -> bare list
                doc[canon] = [cv for _, cv in dedup]
        docs.append(doc)

    # ---- fit embedders on the coalesced free-text, then enrich (canonical-keyed) ----
    emb = {}
    for canon in fuzzy_canon:
        corpus = [t if t else "" for t in ftext[canon]]
        e = emb[canon] = LSA(vec_dim).fit(corpus)
        M = e.tf(corpus)
        for idx, doc in enumerate(docs):
            t = ftext[canon][idx]
            doc[f"{canon}_summary"] = summarise(t) if t else ""
            doc[f"{canon}_keywords"] = keywords(t) if t else []
            doc[f"{canon}_vector"] = [round(float(x), 5) for x in M[idx]]

    # ---- OpenSearch mapping (scalar & array share the same field type; labeled arrays are objects) ----
    props = {}
    for canon, sh in shape.items():
        if sh["kind"] == "free_text":
            props[canon] = {"type": "text"}
            props[f"{canon}_summary"] = {"type": "text"}
            props[f"{canon}_keywords"] = {"type": "keyword"}
            props[f"{canon}_vector"] = {"type": "knn_vector", "dimension": emb[canon].dim,
                                        "method": {"name": "hnsw", "engine": "lucene", "space_type": "cosinesimil"}}
        elif sh["cardinality"] == "array" and sh["style"] == "semantic":
            props[canon] = {"properties": {"type": {"type": "keyword"}, "value": {"type": sh["os_type"]}}}
        else:
            props[canon] = {"type": sh["os_type"]}
    mapping = {"settings": {"index.knn": True}, "mappings": {"properties": props}}

    unified = [dict(canonical=canon, cardinality=sh["cardinality"], style=sh["style"],
                    kind=sh["kind"], os_type=sh["os_type"], sources=sh["sources"],
                    labels=[sh["labels"].get(c) for c in sh["sources"]])
               for canon, sh in shape.items()]
    vectors = {canon: np.array([d[f"{canon}_vector"] for d in docs]) for canon in fuzzy_canon}
    return dict(n_rows=n, n_cols=len(df.columns), catalog=catalog, fuzzy=fuzzy_canon, docs=docs,
                embedders=emb, vectors=vectors, mapping=mapping, merge_groups=merge_groups,
                unified=unified, coerced=int(coerced), quarantine=int(quarantine), llm_calls=LLM_CALLS["n"])

def run(path):
    df=pd.read_csv(path)
    n=len(df); print(f"loaded {n} rows x {len(df.columns)} cols from {path}\n")

    # ---- classify every column (minimal LLM) with null-aware fill-rate ----
    catalog={}
    for c in df.columns:
        col=df[c]
        populated=[x for x in col.tolist() if pd.notna(x) and str(x).strip()!=""]
        info=classify(c, populated[:1500])                 # classify on populated cells only
        info["fill_rate"]=round(len(populated)/n,3)         # nulls are COVERAGE, not signal
        # flag a likely row-index column (unique, sequential int)
        if info.get("kind")=="numeric" and info.get("distinct_ratio",0)>0.99 and c.lower().startswith("unnamed"):
            info["searchable"]=False; info["note"]="looks like a row index — excluded"
        else:
            info["searchable"]=info["os_type"] is not None
        catalog[c]=info

    print(f"{'COLUMN':<22}{'KIND':<11}{'FILL':>6}{'MARGIN':>7}  SOURCE")
    print("-"*62)
    for c,i in catalog.items():
        print(f"{c:<22}{i.get('kind',''):<11}{i.get('fill_rate',0):>6}{i.get('margin',0):>7}  {i['source']}"
              + ("   [not searchable]" if not i.get('searchable',True) else ""))
    fuzzy=[c for c,i in catalog.items() if i.get("kind")=="free_text"]
    print(f"\nLLM escalations: {LLM_CALLS['n']} / {len(df.columns)} columns"
          f"  ({'all deterministic — $0 LLM cost' if LLM_CALLS['n']==0 else 'only the ambiguous residual'})")
    print(f"fuzzy-text columns to enrich: {fuzzy}")

    # ---- clean + build docs ----
    emb={c: LSA(64).fit(df[c].fillna("").astype(str).tolist()) for c in fuzzy}
    coerced=quarantine=0; docs=[]
    for _,row in df.iterrows():
        doc={}
        for c,i in catalog.items():
            if not i.get("searchable"): continue
            v=row[c]
            if pd.isna(v) or str(v).strip()=="": continue         # null -> skip (coverage, not junk)
            try:
                raw=str(v)
                if i["kind"]=="numeric":
                    cln=raw.replace(",","").replace("$",""); doc[c]=float(cln); coerced+=(cln!=raw)
                elif i["kind"]=="boolean": doc[c]=raw.lower() in {"true","yes"}
                elif i["kind"]=="date": doc[c]=pd.to_datetime(v).strftime("%Y-%m-%d")
                else: doc[c]=raw
            except Exception:
                quarantine+=1; continue                            # cell fails its type -> quarantine
        for c in fuzzy:
            t=str(row[c]); doc[f"{c}_summary"]=summarise(t)
            doc[f"{c}_keywords"]=keywords(t); doc[f"{c}_vector"]=[round(float(x),5) for x in emb[c].tf([t])[0]]
        docs.append(doc)
    print(f"\ncleaning: {coerced} cells coerced · {quarantine} cells quarantined · "
          f"nulls skipped as coverage (see fill rates)")

    # ---- mapping + bulk ----
    props={}
    for c,i in catalog.items():
        if not i.get("searchable"): continue
        props[c]={"type":i["os_type"]}
        if c in fuzzy:
            props[f"{c}_summary"]={"type":"text"}; props[f"{c}_keywords"]={"type":"keyword"}
            props[f"{c}_vector"]={"type":"knn_vector","dimension":emb[c].dim,
                                  "method":{"name":"hnsw","engine":"lucene","space_type":"cosinesimil"}}
    json.dump({"settings":{"index.knn":True},"mappings":{"properties":props}}, open("wine_mapping.json","w"), indent=2)
    json.dump({c:{k:v for k,v in i.items()} for c,i in catalog.items()}, open("wine_catalog.json","w"), indent=2)
    if fuzzy:
        import joblib; joblib.dump(emb[fuzzy[0]], "embedder.pkl")   # so load_and_search can embed queries
    with open("wine_bulk.ndjson","w") as f:
        for i,d in enumerate(docs):
            f.write(json.dumps({"index":{"_index":"wine","_id":i}})+"\n"); f.write(json.dumps(d)+"\n")

    # ---- live semantic search over REAL wine descriptions ----
    if fuzzy:
        c=fuzzy[0]; e=emb[c]; mat=np.array([d[f"{c}_vector"] for d in docs])
        title=[d.get("title","?") for d in docs]; kw=[d[f"{c}_keywords"] for d in docs]
        print("\n=== live semantic search over real wine reviews ===")
        for q in ["citrus and green apple, crisp acidity","dark chocolate, bold tannins",
                  "sweet honey and tropical fruit"]:
            qv=e.tf([q])[0]; sims=mat@qv; top=np.argsort(-sims)[:3]
            print(f'\nquery: "{q}"')
            for idx in top: print(f"   {sims[idx]:.3f}  {str(title[idx])[:52]:<52} {kw[idx][:3]}")
    print(f"\nwrote wine_catalog.json + wine_mapping.json + wine_bulk.ndjson ({len(docs)} docs)")

if __name__=="__main__":
    run(sys.argv[1] if len(sys.argv)>1 else "wine.csv")
