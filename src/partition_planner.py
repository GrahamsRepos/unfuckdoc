"""
Partition planner: the extra catalog branch for large dedicated-tenant indices.

For each keyword-typed column it recomputes the TRUE distribution over the full set
(never trusting the sample), then decides:
    enumerable + high-cardinality + not-too-skewed + STABLE  -> PARTITION KEY
    everything else                                          -> facet only
Partition keys are bin-packed into N BALANCED buckets (never one-shard-per-value),
using the frequency histogram so a hot value can't create a hotspot.
"""
import sys, json, math
import pandas as pd

# ---- candidacy thresholds -------------------------------------------------
MIN_CARDINALITY   = 8       # need enough distinct values to fill several buckets
MAX_DISTINCT_RATIO= 0.5     # values must repeat (not unique-per-row)
MAX_TOP1_SHARE    = 0.60    # above this, one value dominates -> routing_partition_size, not clean buckets
TARGET_PARTITION_ROWS = 5000  # aim for ~this many rows per partition
MAX_BUCKETS       = 8

# stability is CHURN, which comes from usage telemetry, not one upload.
# Here it's a hint; in production this is measured (how often the value changes).
STABILITY_HINTS = {"plan": "mutable", "is_active": "mutable", "status": "mutable"}
DEFAULT_STABILITY = "stable"   # assume stable unless known-mutable (POC choice; flagged below)

def skew_metrics(counts):
    total = sum(counts.values())
    top1 = max(counts.values()) / total
    # normalized entropy: 1.0 = perfectly uniform, ->0 = one value dominates
    probs = [c/total for c in counts.values()]
    H = -sum(p*math.log(p) for p in probs if p>0)
    Hnorm = H / math.log(len(counts)) if len(counts) > 1 else 0.0
    return round(top1,3), round(Hnorm,3), total

def bin_pack(counts, n_buckets):
    """Greedy longest-processing-time multiway partition -> balanced buckets."""
    buckets = [{"id": i, "values": [], "rows": 0} for i in range(n_buckets)]
    for val, cnt in sorted(counts.items(), key=lambda kv: -kv[1]):
        b = min(buckets, key=lambda x: x["rows"])   # assign to lightest bucket
        b["values"].append(val); b["rows"] += cnt
    return buckets

def plan(path):
    df = pd.read_csv(path)
    catalog = json.load(open("catalog.json"))
    keyword_cols = [c for c in catalog if c.get("os_type") == "keyword"]
    total_rows = len(df)
    print(f"analysing {len(keyword_cols)} keyword columns over full set ({total_rows} rows)\n")

    report, plan_out = [], {}
    for c in keyword_cols:
        col = c["source_column"]
        s = df[col].dropna().astype(str)
        s = s[s.str.strip() != ""]
        counts = s.value_counts().to_dict()
        card = len(counts)
        ratio = card / max(len(s), 1)
        top1, entropy, populated = skew_metrics(counts) if counts else (0,0,0)
        stability = STABILITY_HINTS.get(c["key"], DEFAULT_STABILITY)

        # ---- candidacy tests ---------------------------------------------
        reasons = []
        if card < MIN_CARDINALITY:            reasons.append(f"low cardinality ({card}<{MIN_CARDINALITY})")
        if ratio > MAX_DISTINCT_RATIO:        reasons.append(f"near-unique (ratio {ratio:.2f})")
        if stability == "mutable":            reasons.append("mutable key (churn -> partition migration)")
        skewed = top1 > MAX_TOP1_SHARE

        verdict = "FACET_ONLY" if reasons else ("PARTITION_KEY_SKEWED" if skewed else "PARTITION_KEY")

        entry = dict(column=col, key=c["key"], cardinality=card, distinct_ratio=round(ratio,4),
                     top1_share=top1, entropy=entropy, stability=stability, verdict=verdict)

        if verdict.startswith("PARTITION_KEY"):
            n = max(2, min(MAX_BUCKETS, card, round(populated / TARGET_PARTITION_ROWS) or 2))
            buckets = bin_pack(counts, n)
            rows_ = [b["rows"] for b in buckets]
            balance = round(max(rows_) / max(min(rows_),1), 2)   # 1.0 = perfect
            entry.update(n_buckets=n, balance_ratio=balance,
                         buckets=[{"id":b["id"],"rows":b["rows"],
                                   "pct":round(100*b["rows"]/populated,1),
                                   "n_values":len(b["values"]),
                                   "values":b["values"][:6]+(["..."] if len(b["values"])>6 else [])}
                                  for b in buckets])
            # concrete value->bucket routing map + OpenSearch strategy
            vmap = {v: b["id"] for b in buckets for v in b["values"]}
            plan_out[c["key"]] = dict(strategy="index_per_bucket",
                                      index_pattern=f"{{tenant}}__{c['key']}__b{{bucket}}",
                                      routing_partition_size_hint=(skewed or None),
                                      value_to_bucket=vmap)
        report.append(entry)

    # ---- print report -------------------------------------------------
    print(f"{'COLUMN':<14}{'CARD':>5}{'TOP1%':>7}{'ENTR':>6}  {'STABILITY':<9} VERDICT")
    print("-"*74)
    for e in report:
        print(f"{e['column']:<14}{e['cardinality']:>5}{e['top1_share']*100:>6.0f}%{e['entropy']:>6}"
              f"  {e['stability']:<9} {e['verdict']}"
              + ("" if not e.get('reason_str') else ""))
        if e['verdict'] == "FACET_ONLY":
            print(f"{'':16}└─ not a partition key: {', '.join([r for r in ['x'] and _reasons(e)])}")
        elif "buckets" in e:
            print(f"{'':16}└─ {e['n_buckets']} balanced buckets, balance ratio {e['balance_ratio']} "
                  f"(1.0=perfect){'  [SKEWED: use routing_partition_size]' if e['verdict'].endswith('SKEWED') else ''}")
            for b in e["buckets"]:
                print(f"{'':18}bucket {b['id']}: {b['rows']:>6} rows ({b['pct']:>4}%)  {b['n_values']:>2} values  {b['values']}")
    json.dump({"report": report, "plan": plan_out}, open("partition_plan.json","w"), indent=2)
    print(f"\nwrote partition_plan.json ({len(plan_out)} partition key(s))")

def _reasons(e):
    r=[]
    if e['cardinality']<MIN_CARDINALITY: r.append(f"low cardinality ({e['cardinality']})")
    if e['distinct_ratio']>MAX_DISTINCT_RATIO: r.append(f"near-unique (ratio {e['distinct_ratio']})")
    if e['stability']=="mutable": r.append("mutable key")
    return r

if __name__ == "__main__":
    plan(sys.argv[1] if len(sys.argv)>1 else "people.csv")
