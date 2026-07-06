#!/usr/bin/env python3
"""Adversarial benchmark for canonical field inference.

Each CSV encodes ONE hard pattern. `benchmark_answers.json` is the ground truth:
for every column, the expected canonical + how we expect it resolved:
  - "alias"     : should hit the deterministic dictionary
  - "semantic"  : synonym not in the dict; should resolve via embedding
  - "identity"  : novel/ambiguous; should STAY its own name (a wrong canonical here is a
                  false positive — the dangerous failure, e.g. carrier->phone)

Run `score_canonical.py` after generating to measure the current engine against this.
"""
import csv, json, os, random

random.seed(7)
ROOT = os.path.dirname(__file__)
OUT = os.path.join(ROOT, "benchmark")
os.makedirs(OUT, exist_ok=True)

firsts = ["Wei", "Mei", "Sven", "Freya", "Leila", "Priya", "Diego", "Yuki", "Omar", "Grace", "Noah", "Amara", "Ivan", "Sofia"]
lasts = ["Moreno", "Khan", "Costa", "Reddy", "Kovac", "Tanaka", "Bauer", "Silva", "Okoro", "Nguyen", "Haddad", "Rossi"]
cities = ["Toronto", "Berlin", "Mumbai", "Lyon", "Osaka", "Lagos", "Madrid", "Austin", "Oslo", "Cairo"]
countries = ["Canada", "Germany", "India", "France", "Japan", "Nigeria", "Spain", "USA", "Norway", "Egypt"]
companies = ["Acme Analytics", "Vertex Robotics", "Sunrise Textiles", "Cobalt Health", "Northwind Traders", "Blue Harbor"]

def nm(): return f"{random.choice(firsts)} {random.choice(lasts)}"
def em(n): return f"{n.lower().replace(' ', '.')}@example.com"
def ph(): return f"+1-555-{random.randint(1000,9999)}"
def dt(): return f"{random.randint(2019,2024)}-{random.randint(1,12):02d}-{random.randint(1,28):02d}"

def write(fname, header, rowfn, n=30):
    with open(os.path.join(OUT, fname), "w", newline="") as f:
        w = csv.writer(f); w.writerow(header)
        for _ in range(n): w.writerow(rowfn())
    print(f"  wrote benchmark/{fname:<24} {n} rows · {len(header)} cols")

ANSWERS = {}

# ── 1. cryptic abbreviations (should still hit aliases / semantic) ───────────────
write("cryptic_abbrev.csv",
      ["fnm", "lnm", "eml", "tel_no", "cmpy", "cntry", "amt_paid", "cust_id"],
      lambda: [random.choice(firsts), random.choice(lasts), em(nm()), ph(),
               random.choice(companies), random.choice(countries),
               f"${random.randint(50,9000)}", f"C{random.randint(10000,99999)}"])
ANSWERS["cryptic_abbrev.csv"] = {
    "fnm": ["first_name", "alias"], "lnm": ["last_name", "alias"], "eml": ["email", "alias"],
    "tel_no": ["phone", "alias"], "cmpy": ["company", "semantic"], "cntry": ["country", "alias"],
    "amt_paid": ["amount", "alias"], "cust_id": ["identifier", "alias"],
}

# ── 2. multilingual headers (es/fr/de) — tests non-English recall ────────────────
write("multilingual.csv",
      ["nombre", "correo", "telefono", "pais", "empresa", "ciudad", "edad"],
      lambda: [nm(), em(nm()), ph(), random.choice(countries), random.choice(companies),
               random.choice(cities), random.randint(18, 75)])
ANSWERS["multilingual.csv"] = {
    "nombre": ["full_name", "semantic"], "correo": ["email", "semantic"], "telefono": ["phone", "semantic"],
    "pais": ["country", "semantic"], "empresa": ["company", "semantic"], "ciudad": ["city", "semantic"],
    "edad": ["age", "semantic"],
}

# ── 3. open-vocab synonyms (not in dict → should go semantic) ────────────────────
write("synonyms.csv",
      ["surname", "given_name", "mobile_number", "organisation", "town", "occupation", "sum_owed"],
      lambda: [random.choice(lasts), random.choice(firsts), ph(), random.choice(companies),
               random.choice(cities), random.choice(["Analyst", "Manager", "Engineer", "Director"]),
               f"{random.randint(100,9000)}.00"])
ANSWERS["synonyms.csv"] = {
    "surname": ["last_name", "semantic"], "given_name": ["first_name", "semantic"],
    "mobile_number": ["phone", "semantic"], "organisation": ["company", "alias"],
    "town": ["city", "alias"], "occupation": ["job_title", "alias"], "sum_owed": ["amount", "semantic"],
}

# ── 4. TRAPS: false friends that must NOT map to a canonical (stay identity) ──────
write("traps.csv",
      ["carrier", "account_ref", "title", "region_manager", "book", "plan", "rate"],
      lambda: [random.choice(["FedEx", "Maersk", "DHL", "UPS"]), f"REF{random.randint(1000,9999)}",
               random.choice(["The Silent River", "Golden Empire", "Broken Machine"]),
               nm(), random.choice(["Atlas", "Odyssey", "Ledger"]),
               random.choice(["free", "pro", "team"]), f"{random.uniform(1,9):.2f}"])
ANSWERS["traps.csv"] = {
    "carrier": ["carrier", "identity"],          # a shipping carrier, NOT phone
    "account_ref": ["identifier", "identity"],   # an id, NOT company (account alias too broad)
    "title": ["title", "identity"],              # a book title, NOT job_title
    "region_manager": ["region_manager", "identity"],  # a person's role, NOT region
    "book": ["book", "identity"], "plan": ["plan", "identity"], "rate": ["rate", "identity"],
}

# ── 5. type-ambiguity values (kind gating is the challenge) ──────────────────────
def amb():
    return [f"{random.randint(1,9):04d}",                    # leading-zero id (NOT numeric)
            random.choice(["2024-01-03", "03/04/2024", "Jan 3 2024"]),  # mixed date formats
            random.choice(["Y", "N"]),                       # boolean as Y/N
            f"{random.randint(1000,9999)}",                  # numeric postcode
            random.choice(["USD", "EUR", "GBP"]),            # currency code
            f"{random.randint(20000,90000)}"]                # salary (numeric amount)
write("type_ambiguity.csv", ["record_no", "when", "active", "zip", "ccy", "gross_pay"], amb)
ANSWERS["type_ambiguity.csv"] = {
    "record_no": ["identifier", "alias"], "when": ["date", "identity"], "active": ["active", "identity"],
    "zip": ["postal_code", "alias"], "ccy": ["currency", "alias"], "gross_pay": ["amount", "semantic"],
}

# ── 6. novel domain identifiers (should all stay identity) ───────────────────────
write("novel_domain.csv",
      ["vin", "isbn", "iban", "imei", "sku", "hs_code", "tail_number"],
      lambda: [f"VIN{random.randint(10**9,10**10)}", f"978{random.randint(10**9,10**10)}",
               f"GB{random.randint(10**14,10**15)}", f"{random.randint(10**14,10**15)}",
               f"SKU-{random.randint(1000,9999)}", f"{random.randint(1000,9999)}.{random.randint(10,99)}",
               f"N{random.randint(100,999)}{random.choice('ABXY')}"])
ANSWERS["novel_domain.csv"] = {c: [c, "identity"] for c in
    ["vin", "isbn", "iban", "imei", "sku", "hs_code", "tail_number"]}

with open(os.path.join(OUT, "benchmark_answers.json"), "w") as f:
    json.dump(ANSWERS, f, indent=2)
total = sum(len(v) for v in ANSWERS.values())
print(f"\nwrote benchmark/benchmark_answers.json  ({len(ANSWERS)} files, {total} labelled columns)")
