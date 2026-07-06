#!/usr/bin/env python3
"""Realistic sales-agent multi-source pack — the messy reality a rep reconciles by hand:
a Salesforce export + HubSpot dump + Mailchimp list + a hand-made rep spreadsheet +
LinkedIn Sales Navigator + an Apollo/ZoomInfo lead CSV.

The SAME people appear across sources (overlapping, keyed by email) but every tool names its
columns differently and at different granularity (full name vs first/last; MailingCity vs Loc vs
city; work_email vs Email Address vs e-mail). So this pack tests two things at once:
  1. canonical inference  — do vendor column names map to the right canonical? (sales_answers.json)
  2. entity merge         — do the same people unify across all six into one contact list?

Load all six into one collection (key=email) to exercise the merge.
"""
import csv, json, os, random

random.seed(21)
OUT = os.path.join(os.path.dirname(__file__), "benchmark", "sales")
os.makedirs(OUT, exist_ok=True)

firsts = ["Wei", "Mei", "Sven", "Freya", "Leila", "Priya", "Diego", "Yuki", "Omar", "Grace",
          "Noah", "Amara", "Ivan", "Sofia", "Kenji", "Aisha", "Tomas", "Elena", "Marcus", "Nadia"]
lasts = ["Moreno", "Khan", "Costa", "Reddy", "Kovac", "Tanaka", "Bauer", "Silva", "Okoro", "Nguyen"]
comps = ["Acme Analytics", "Vertex Robotics", "Sunrise Textiles", "Cobalt Health", "Northwind Traders",
         "Blue Harbor Foods", "Meridian Systems", "Redwood Ceramics"]
titles = ["VP Sales", "Marketing Lead", "CFO", "Operations Manager", "Account Executive", "Founder", "Head of Growth"]
cities = ["Toronto", "Berlin", "Mumbai", "Lyon", "Osaka", "Lagos", "Madrid", "Austin"]
states = ["ON", "BE", "MH", "ARA", "OSK", "LA", "MD", "TX"]
countries = ["Canada", "Germany", "India", "France", "Japan", "Nigeria", "Spain", "USA"]
industries = ["SaaS", "Manufacturing", "Healthcare", "Retail", "Logistics"]
sources = ["Webinar", "Cold Call", "Referral", "Inbound", "Event"]

# canonical person pool — each source projects a subset of these fields, renamed per vendor
pool = []
for i in range(40):
    f, l = random.choice(firsts), random.choice(lasts)
    ci = random.randrange(len(cities))
    pool.append(dict(
        first=f, last=l, email=f"{f.lower()}.{l.lower()}@{random.choice(comps).split()[0].lower()}.com",
        phone=f"+1-555-{random.randint(1000,9999)}", company=random.choice(comps),
        title=random.choice(titles), city=cities[ci], state=states[ci], country=countries[ci],
        industry=random.choice(industries), revenue=random.randint(50_000, 5_000_000),
        source=random.choice(sources), rating=random.randint(1, 5), tag=random.choice(["hot", "warm", "cold"]),
        created=f"{random.randint(2022,2024)}-{random.randint(1,12):02d}-{random.randint(1,28):02d}",
        amount=random.randint(2_000, 90_000), address=f"{random.randint(1,999)} {random.choice(['Oak','Elm','Main'])} St",
    ))

def sample(n): return random.sample(pool, n)
def write(fname, header, rows):
    with open(os.path.join(OUT, fname), "w", newline="") as fh:
        w = csv.writer(fh); w.writerow(header); w.writerows(rows)
    print(f"  wrote benchmark/sales/{fname:<24} {len(rows)} rows · {len(header)} cols")

# ── 1. Salesforce contact export (custom __c fields, 18-char Id, Mailing* prefix) ─
rows = [[f"003{random.randint(10**14,10**15)}", p["first"], p["last"], p["title"], p["company"],
         p["email"], p["phone"], p["city"], p["country"], p["revenue"], p["source"], p["created"]]
        for p in sample(30)]
write("salesforce_export.csv",
      ["Id", "First Name", "Last Name", "Title", "Account Name", "Email", "Phone",
       "Mailing City", "Mailing Country", "Annual Revenue", "Lead Source", "Created Date"], rows)

# ── 2. HubSpot contact export ────────────────────────────────────────────────────
rows = [[random.randint(10**7, 10**8), p["first"], p["last"], p["email"], p["phone"], p["company"],
         p["title"], p["city"], p["country"], p["created"], p["source"], random.choice(["Lead", "MQL", "SQL"])]
        for p in sample(28)]
write("hubspot_export.csv",
      ["Record ID", "First Name", "Last Name", "Email", "Phone Number", "Company Name", "Job Title",
       "City", "Country/Region", "Create Date", "Original Source", "Lifecycle Stage"], rows)

# ── 3. Mailchimp audience export (UPPER_SNAKE merge tags) ─────────────────────────
rows = [[p["email"], p["first"], p["last"], p["company"], p["phone"], p["address"],
         p["rating"], p["created"], p["tag"]] for p in sample(32)]
write("mailchimp_export.csv",
      ["Email Address", "First Name", "Last Name", "Company", "Phone", "Address",
       "MEMBER_RATING", "OPTIN_TIME", "TAGS"], rows)

# ── 4. a rep's hand-maintained spreadsheet (full name, ad-hoc abbreviations) ──────
rows = [[f"{p['first']} {p['last']}", p["company"], p["email"], p["phone"], p["city"], p["country"],
         f"${p['amount']:,}", random.choice(["Prospect", "Qualified", "Won", "Lost"]),
         p["created"], f"spoke re {random.choice(['renewal','demo','pricing'])}"]
        for p in sample(25)]
write("rep_spreadsheet.csv",
      ["Name", "Co.", "e-mail", "Cell", "Loc", "Country", "$ Value", "Stage", "Last Contacted", "Notes"], rows)

# ── 5. LinkedIn Sales Navigator export ───────────────────────────────────────────
rows = [[p["first"], p["last"], p["company"], p["title"], f"{p['city']}, {p['country']}",
         p["email"], p["created"], f"https://linkedin.com/in/{p['first'].lower()}{p['last'].lower()}"]
        for p in sample(26)]
write("linkedin_navigator.csv",
      ["First Name", "Last Name", "Company", "Position", "Location", "Email Address",
       "Connected On", "Profile URL"], rows)

# ── 6. Apollo / ZoomInfo lead export (lower_snake, work_email/mobile_phone) ───────
rows = [[p["first"], p["last"], p["title"], p["company"], p["email"], p["phone"], p["city"],
         p["state"], p["country"], p["industry"], random.choice(["C-Suite", "VP", "Director", "Manager"]),
         random.choice([50, 200, 1000, 5000])]
        for p in sample(30)]
write("apollo_leads.csv",
      ["first_name", "last_name", "title", "company_name", "work_email", "mobile_phone", "city",
       "state", "country", "industry", "seniority", "employee_count"], rows)

# ── 7. multi-channel CRM: several phones per contact (array consolidation test) ──
#    Mobile / Work Phone / Home Phone all canonicalize to `phone` and must consolidate into ONE
#    multi-valued canonical — merged with single-phone sources into one typed phone list.
rows = [[p["email"], p["first"], p["last"],
         f"+1-555-{random.randint(1000,9999)}", f"+1-555-{random.randint(1000,9999)}", f"+1-555-{random.randint(1000,9999)}",
         p["email"], f"{p['first'].lower()}@personal.com"]
        for p in sample(24)]
write("multichannel_crm.csv",
      ["Email", "First Name", "Last Name", "Mobile", "Work Phone", "Home Phone", "Work Email", "Personal Email"], rows)

# ── ground truth: vendor column -> [canonical, expected tier] ─────────────────────
A = {
 "sales/salesforce_export.csv": {"Id": ["identifier", "identity"], "First Name": ["first_name", "alias"],
    "Last Name": ["last_name", "alias"], "Title": ["job_title", "alias"], "Account Name": ["company", "alias"],
    "Email": ["email", "alias"], "Phone": ["phone", "alias"], "Mailing City": ["city", "alias"],
    "Mailing Country": ["country", "alias"], "Annual Revenue": ["amount", "alias"],
    "Lead Source": ["lead_source", "identity"], "Created Date": ["date", "alias"]},
 "sales/hubspot_export.csv": {"Record ID": ["identifier", "alias"], "First Name": ["first_name", "alias"],
    "Last Name": ["last_name", "alias"], "Email": ["email", "alias"], "Phone Number": ["phone", "alias"],
    "Company Name": ["company", "alias"], "Job Title": ["job_title", "alias"], "City": ["city", "alias"],
    "Country/Region": ["country", "alias"], "Create Date": ["date", "alias"],
    "Original Source": ["source", "identity"], "Lifecycle Stage": ["lifecycle_stage", "identity"]},
 "sales/mailchimp_export.csv": {"Email Address": ["email", "alias"], "First Name": ["first_name", "alias"],
    "Last Name": ["last_name", "alias"], "Company": ["company", "alias"], "Phone": ["phone", "alias"],
    "Address": ["address", "alias"], "MEMBER_RATING": ["rating", "semantic"], "OPTIN_TIME": ["date", "semantic"],
    "TAGS": ["interests", "semantic"]},
 "sales/rep_spreadsheet.csv": {"Name": ["full_name", "alias"], "Co.": ["company", "semantic"],
    "e-mail": ["email", "alias"], "Cell": ["phone", "alias"], "Loc": ["city", "semantic"],
    "Country": ["country", "alias"], "$ Value": ["amount", "semantic"], "Stage": ["stage", "identity"],
    "Last Contacted": ["date", "semantic"], "Notes": ["description", "alias"]},
 "sales/linkedin_navigator.csv": {"First Name": ["first_name", "alias"], "Last Name": ["last_name", "alias"],
    "Company": ["company", "alias"], "Position": ["job_title", "semantic"], "Location": ["city", "semantic"],
    "Email Address": ["email", "alias"], "Connected On": ["date", "semantic"], "Profile URL": ["url", "alias"]},
 "sales/apollo_leads.csv": {"first_name": ["first_name", "alias"], "last_name": ["last_name", "alias"],
    "title": ["job_title", "alias"], "company_name": ["company", "alias"], "work_email": ["email", "semantic"],
    "mobile_phone": ["phone", "alias"], "city": ["city", "alias"], "state": ["region", "alias"],
    "country": ["country", "alias"], "industry": ["industry", "identity"], "seniority": ["seniority", "identity"],
    "employee_count": ["employee_count", "identity"]},
 "sales/multichannel_crm.csv": {"Email": ["email", "alias"], "First Name": ["first_name", "alias"],
    "Last Name": ["last_name", "alias"], "Mobile": ["phone", "alias"], "Work Phone": ["phone", "alias"],
    "Home Phone": ["phone", "alias"], "Work Email": ["email", "alias"], "Personal Email": ["email", "alias"]},
}
with open(os.path.join(os.path.dirname(OUT), "sales_answers.json"), "w") as f:
    json.dump(A, f, indent=2)
print(f"\nwrote benchmark/sales_answers.json  ({len(A)} sources, {sum(len(v) for v in A.values())} columns)")
