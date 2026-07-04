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
