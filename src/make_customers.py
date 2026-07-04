"""
Generate 3 messy, realistic *customer* CSVs for testing the pipeline.

Each file describes the SAME kind of entity (a business contact) but uses a DIFFERENT
column-naming convention on purpose, so the canonical-unification layer has to merge
e.g. Email / e-mail / mail  ->  email, and Revenue / amt / cost -> amount.
Also injects real-world mess: nulls, "$1,200.00" money, mixed date formats, free-text notes.

Run:  python3 src/make_customers.py      # writes data/samples/*.csv
"""
import csv, os, random

random.seed(42)  # reproducible

FIRST=["Aisha","Liam","Sofia","Kenji","Marcus","Priya","Elena","Tomas","Grace","Omar",
       "Yuki","Noah","Amara","Diego","Ingrid","Ravi","Chloe","Mateo","Fatima","Lars",
       "Nadia","Ethan","Wei","Camila","Hassan","Freya","Andre","Leila","Sven","Mei"]
LAST=["Okoro","Nguyen","Rossi","Tanaka","Hall","Patel","Kovac","Silva","Bauer","Haddad",
      "Sato","Brown","Diallo","Moreno","Larsen","Reddy","Dubois","Costa","Khan","Berg"]
COMPANIES=["Northwind Traders","Acme Logistics","Blue Harbor Foods","Vertex Analytics",
           "Sunrise Textiles","Ironclad Security","Meridian Health","Cobalt Robotics",
           "Evergreen Farms","Lumen Media","Harborview Bank","Quantum Freight",
           "Redwood Ceramics","Atlas Mining","Willow & Vine","Pioneer Solar"]
COUNTRIES=["United States","Germany","Japan","Brazil","India","France","Nigeria","Sweden","Canada","Spain"]
CITIES=["Austin","Berlin","Osaka","São Paulo","Mumbai","Lyon","Lagos","Malmö","Toronto","Valencia"]
TITLES=["Head of Procurement","VP Sales","Operations Manager","Founder","CFO","IT Director",
        "Marketing Lead","Supply Chain Analyst","Account Executive","Plant Manager"]
# Free-text note fragments — composed 2-3 at a time so each note is mostly unique
# (high cardinality -> classifies as free_text, not a low-card enum).
NOTE_OPEN=[
 "Renewed the annual contract early and seems very happy with onboarding",
 "Flagged as a churn risk after complaining about slow API response times",
 "Strong upsell opportunity as they expand into new regional warehouses",
 "Price-sensitive account that negotiated a steep discount at signing",
 "The internal champion just left, so the new stakeholder is skeptical",
 "Loves the reporting dashboards and keeps requesting custom data exports",
 "Integration with their ERP has stalled, blocked on their own IT team",
 "High-NPS promoter who referred two other companies to us recently",
 "Wants SSO, audit logs and stricter role permissions before a wider rollout",
 "Seasonal buyer whose order volume spikes hard in the fourth quarter",
 "Evaluating a competitor in parallel and asked for a side-by-side comparison",
 "Support tickets have dropped since the last release, sentiment is improving",
]
NOTE_MID=[
 "and the procurement team wants a multi-year pricing commitment",
 "while their finance lead keeps pushing back on the renewal amount",
 "so we should schedule a quarterly business review before month end",
 "though adoption in the field offices is still lagging behind plan",
 "and they hinted at a much larger rollout across sister companies",
 "but the technical buyer needs a security questionnaire completed first",
 "and marketing wants co-branded case studies out of the relationship",
 "so the account manager is planning an on-site visit next quarter",
]
NOTE_END=[
 "Overall a healthy relationship worth investing in.",
 "Risk of downgrade if we can't move faster on their requests.",
 "Good candidate for an executive sponsorship program.",
 "Needs a clear success plan to justify the spend internally.",
 "Likely to expand seats if the pilot goes well.",
 "Watch closely over the next two renewal cycles.",
]
def make_note():
    return " ".join([random.choice(NOTE_OPEN), random.choice(NOTE_MID), random.choice(NOTE_END)])

def maybe(v, p=0.15):
    """Randomly blank a value to simulate sparse real-world columns."""
    return "" if random.random() < p else v

def money(lo, hi, style):
    n = random.randint(lo, hi)
    if style == "usd":   return f"${n:,}.00"        # "$12,000.00"
    if style == "plain": return str(n)              # "12000"
    return f"{n/1000:.1f}k"                          # "12.0k"

def rows(n):
    out=[]
    for i in range(n):
        f=random.choice(FIRST); l=random.choice(LAST)
        out.append(dict(
            i=i, first=f, last=l, full=f"{f} {l}",
            company=random.choice(COMPANIES),
            email=f"{f.lower()}.{l.lower()}@{random.choice(['acme','mail','corp','biz'])}.com",
            phone=f"+1-{random.randint(200,999)}-{random.randint(100,999)}-{random.randint(1000,9999)}",
            country=random.choice(COUNTRIES), city=random.choice(CITIES),
            title=random.choice(TITLES),
            amount=random.randint(2000, 250000),
            note=make_note(),
        ))
    return out

BASE=os.path.join(os.path.dirname(__file__), "..", "data", "samples")
os.makedirs(BASE, exist_ok=True)

def write(name, header, rowfn, data):
    path=os.path.join(BASE, name)
    with open(path, "w", newline="") as fh:
        w=csv.writer(fh); w.writerow(header)
        for r in data: w.writerow(rowfn(r))
    print(f"wrote {path}  ({len(data)} rows, cols: {header})")

data = rows(120)

# File 1 — tidy CRM export, TitleCase headers, USD money, ISO dates
write("crm_contacts.csv",
    ["Index","First Name","Last Name","Company","Email","Phone","Country","City",
     "Job Title","Annual Revenue","Signup Date","Notes"],
    lambda r: [r["i"], r["first"], r["last"], r["company"], maybe(r["email"]),
               maybe(r["phone"]), r["country"], maybe(r["city"]), r["title"],
               money(r["amount"], r["amount"], "usd"), f"2024-{random.randint(1,12):02d}-{random.randint(1,28):02d}",
               maybe(r["note"], 0.1)],
    data)

# File 2 — scrappy sales-lead dump, snake/abbrev headers, SYNONYM columns, US-style dates
write("sales_leads.csv",
    ["id","fname","lname","employer","e-mail","mobile","nation","town","role","amt","created","comments"],
    lambda r: [r["i"], r["first"], r["last"], r["company"], maybe(r["email"], 0.2),
               maybe(r["phone"], 0.25), r["country"], maybe(r["city"], 0.2), r["title"],
               money(r["amount"], r["amount"], "plain"),
               f"{random.randint(1,12)}/{random.randint(1,28)}/2024", maybe(r["note"], 0.15)],
    data)

# File 3 — subscription list, yet another convention, "k" money, free-text bio
write("subscribers.csv",
    ["subscriber_id","full_name","organization","mail","cell","region","cost","description"],
    lambda r: [r["i"], r["full"], r["company"], maybe(r["email"], 0.1),
               maybe(r["phone"], 0.3), r["country"], money(r["amount"], r["amount"], "plain"),
               maybe(r["note"], 0.05)],
    data)

# File 4 — multi-valued contacts: exercises scalar-vs-array consolidation.
#   phone_home/work/mobile  -> concurrent, distinct  -> SEMANTIC array [{type,value}]
#   interest_1/2/3          -> concurrent, distinct  -> POSITIONAL array [..]
#   country / cntry         -> mutually exclusive     -> SCALAR (survivorship coalesce)
INTERESTS=["sustainability","logistics","automation","analytics","robotics","fintech",
           "healthcare","solar","ceramics","textiles","security","mining","media","farming"]
def subset(seq, lo, hi):
    k=random.randint(lo, hi); return random.sample(seq, k)
def phones():
    # each row populates a random subset of the three phone slots, with DISTINCT numbers
    slots=subset(["home","work","mobile"], 1, 3)
    return {s: f"+1-{random.randint(200,999)}-{random.randint(100,999)}-{random.randint(1000,9999)}" for s in slots}

def multi_row(r):
    ph=phones()
    ints=subset(INTERESTS, 1, 3)
    # country lives in EXACTLY ONE of the two columns per row (alternatives, not multiples)
    use_alt = random.random() < 0.4
    country, cntry = ("", r["country"]) if use_alt else (r["country"], "")
    return [r["full"], ph.get("home",""), ph.get("work",""), ph.get("mobile",""),
            ints[0] if len(ints)>0 else "", ints[1] if len(ints)>1 else "", ints[2] if len(ints)>2 else "",
            country, cntry, r["note"]]

write("multi_contacts.csv",
    ["full_name","phone_home","phone_work","phone_mobile",
     "interest_1","interest_2","interest_3","country","cntry","notes"],
    multi_row, data)

print("\ndone — four customer CSVs (three schema variants + one multi-valued) describing the same entity.")
