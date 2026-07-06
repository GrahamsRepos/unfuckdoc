#!/usr/bin/env python3
"""Score the canonical inference engine against the adversarial benchmark.

Loads each benchmark CSV through the running backend (/api/load_sample) and compares the
predicted canonical to the ground truth in benchmark_answers.json.

Verdicts per column:
  ✓ correct        predicted canonical == expected
  ✗ false-positive expected identity but a real canonical was assigned  (the DANGEROUS failure)
  · miss           expected a canonical but got identity                (recall gap)
  ≠ wrong-canonical predicted a different real canonical                (precision gap)

Usage:  python3 data/score_canonical.py [http://host:port] [answers.json]
        (answers path is relative to data/benchmark/; default benchmark_answers.json)
"""
import json, os, sys, urllib.request

args = sys.argv[1:]
BASE = next((a for a in args if a.startswith("http")), "http://localhost:8080")
ANS = next((a for a in args if not a.startswith("http")), "benchmark_answers.json")
ROOT = os.path.dirname(__file__)
answers = json.load(open(os.path.join(ROOT, "benchmark", ANS)))

def load(name):
    req = urllib.request.Request(f"{BASE}/api/load_sample",
        data=json.dumps({"name": f"benchmark/{name}"}).encode(),
        headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=60))

tot = dict(correct=0, fp=0, miss=0, wrong=0, n=0)
print(f"scoring against {BASE}\n" + "═" * 72)
for fname, cols in answers.items():
    d = load(fname)
    pred = {c["name"]: (c["canonical"], c["canonical_method"]) for c in d["columns"]}
    print(f"\n{fname}")
    for col, (want, want_method) in cols.items():
        got, method = pred.get(col, ("?", "?"))
        exp_identity = want_method == "identity"
        got_identity = method == "identity"
        if got == want:
            v, mark = "correct", "✓"
        elif exp_identity and not got_identity:
            v, mark = "fp", "✗"          # expected to stay identity, but got canonicalized
        elif not exp_identity and got_identity:
            v, mark = "miss", "·"         # expected a canonical, got identity
        else:
            v, mark = "wrong", "≠"        # different real canonical
        tot[v] += 1; tot["n"] += 1
        note = "" if v == "correct" else f"   (want {want}/{want_method})"
        print(f"  {mark} {col:<16} -> {got:<14} [{method}]{note}")

n = tot["n"]
acc = 100 * tot["correct"] / n if n else 0
print("\n" + "═" * 72)
print(f"BASELINE  {tot['correct']}/{n} correct  ({acc:.0f}%)   "
      f"| false-positives {tot['fp']}  misses {tot['miss']}  wrong {tot['wrong']}")
print("false-positives are the costly failures (a confident wrong canonical).")
