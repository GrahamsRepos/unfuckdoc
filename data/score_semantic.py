#!/usr/bin/env python3
"""Evaluate semantic-search ACCURACY on listings.csv — does it find MEANING, not keywords?

For each theme we run its natural-language query and check whether the theme's 4 listings (which
share the concept but NO keywords with each other or the query) come back at the top. We report
standard information-retrieval metrics, and run the SAME query in keyword mode to prove the gap:

  Precision@4  of the top-4 results, how many are the 4 true members        (1.0 = perfect)
  Recall@4     of the 4 true members, how many appear in the top-4          (same as P@4 when k=members)
  MRR          1/rank of the first true member                              (1.0 = top hit is relevant)

Semantic should score high; keyword should score ~0 (no shared words) — that gap IS the value of
meaning-based search. Usage: python3 data/score_semantic.py [http://localhost:8080] [collection]
"""
import json, os, sys, urllib.request

BASE = next((a for a in sys.argv[1:] if a.startswith("http")), "http://localhost:8080")
COLL = next((a for a in sys.argv[1:] if not a.startswith("http")), "listings")
themes = json.load(open(os.path.join(os.path.dirname(__file__), "listings_eval.json")))

def search(mode, q, k=4):
    body = json.dumps({"q": q, "mode": mode, "size": k}).encode()
    req = urllib.request.Request(f"{BASE}/api/collections/{COLL}/search", data=body,
                                 headers={"Content-Type": "application/json"})
    d = json.load(urllib.request.urlopen(req, timeout=60))
    return [r.get("city", "") for r in d["results"]]     # identify each result by its (unique) city

def metrics(ranked_cities, truth_cities):
    truth = set(truth_cities); k = len(truth_cities)
    top = ranked_cities[:k]
    hits = sum(1 for c in top if c in truth)
    p_at_k = hits / k
    mrr = next((1.0 / (i + 1) for i, c in enumerate(ranked_cities) if c in truth), 0.0)
    return p_at_k, mrr

print(f"collection '{COLL}' @ {BASE}\n" + "=" * 74)
agg = {"semantic": [0.0, 0.0], "keyword": [0.0, 0.0]}
for theme, spec in themes.items():
    print(f"\n▶ {theme}: \"{spec['query']}\"   (true: {', '.join(spec['cities'])})")
    for mode in ("semantic", "keyword"):
        ranked = search(mode, spec["query"])
        p, mrr = metrics(ranked, spec["cities"])
        agg[mode][0] += p; agg[mode][1] += mrr
        marks = " ".join(("✓" + c if c in set(spec["cities"]) else "·" + c) for c in ranked) or "(none)"
        print(f"   {mode:<9} P@4={p:.2f} MRR={mrr:.2f}  top: {marks}")

n = len(themes)
print("\n" + "=" * 74)
for mode in ("semantic", "keyword"):
    print(f"{mode:<9} mean P@4={agg[mode][0]/n:.2f}  mean MRR={agg[mode][1]/n:.2f}")
print("\nsemantic ≫ keyword here = the model is matching MEANING, not shared words.")
