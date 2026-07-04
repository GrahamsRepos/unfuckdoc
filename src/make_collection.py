"""
Generate several "vendor export" CSVs that all describe the SAME entity (a customer/person) but
with DIFFERENT column names and different subsets of fields — the inputs for a single COLLECTION.

The point: each file's columns must be inferred and mapped onto the collection's canonical schema
(First Name/fname/name -> full_name or first_name, Email/email_address/work_email -> email, etc.),
and because the files share underlying people, records also link across sources.

Run:  python3 src/make_collection.py     # writes data/collections/*.csv
"""
import csv, os, random

random.seed(11)
BASE = os.path.join(os.path.dirname(__file__), "..", "data", "collections")
os.makedirs(BASE, exist_ok=True)

FIRST = ["Aisha","Liam","Sofia","Kenji","Marcus","Priya","Elena","Tomas","Grace","Omar","Yuki","Noah",
         "Amara","Diego","Ingrid","Ravi","Chloe","Mateo","Fatima","Lars","Nadia","Ethan","Wei","Camila"]
LAST  = ["Okoro","Nguyen","Rossi","Tanaka","Hall","Patel","Kovac","Silva","Bauer","Haddad","Sato","Brown",
         "Diallo","Moreno","Larsen","Reddy","Dubois","Costa","Khan","Berg"]
COMPANIES = ["Northwind Traders","Acme Logistics","Blue Harbor Foods","Vertex Analytics","Sunrise Textiles",
             "Ironclad Security","Meridian Health","Cobalt Robotics","Evergreen Farms","Lumen Media"]
COUNTRIES = ["United States","Germany","Japan","Brazil","India","France","Nigeria","Sweden","Canada","Spain"]
CITIES = ["Austin","Berlin","Osaka","Sao Paulo","Mumbai","Lyon","Lagos","Malmo","Toronto","Valencia"]

# a shared pool of PEOPLE — each vendor file draws from these so records link across sources
PEOPLE = []
for i in range(80):
    f, l = random.choice(FIRST), random.choice(LAST)
    PEOPLE.append(dict(first=f, last=l, email=f"{f.lower()}.{l.lower()}@{random.choice(['acme','corp','mail','biz'])}.com",
                       company=random.choice(COMPANIES), country=random.choice(COUNTRIES),
                       city=random.choice(CITIES),
                       phone=f"+1-{random.randint(200,999)}-{random.randint(100,999)}-{random.randint(1000,9999)}",
                       value=random.randint(2000, 240000)))

def sample(n): return random.sample(PEOPLE, n)
def blank(v, p=0.12): return "" if random.random() < p else v

MONTHS = ["Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"]
def rand_date(fmt):
    """Same kind of date, four different string formats — each must be inferred as a date field."""
    y, m, d = random.randint(2022, 2024), random.randint(1, 12), random.randint(1, 28)
    if fmt == "iso":   return f"{y}-{m:02d}-{d:02d}"                                  # 2024-03-07
    if fmt == "us":    return f"{m}/{d}/{y}"                                          # 3/7/2024
    if fmt == "isodt": return f"{y}-{m:02d}-{d:02d}T{random.randint(0,23):02d}:{random.randint(0,59):02d}:00Z"
    return f"{MONTHS[m-1]} {d}, {y}"                                                  # Mar 7, 2024
def write(fname, header, rows):
    with open(os.path.join(BASE, fname), "w", newline="") as fh:
        w = csv.writer(fh); w.writerow(header)
        for r in rows: w.writerow(r)
    print(f"wrote {fname:<26} {len(rows):>3} rows · cols: {header}")

# 1) HubSpot-style: TitleCase, split name, has city, ISO date
write("hubspot_contacts.csv",
      ["First Name","Last Name","Email","Company Name","Phone Number","Country","City","Created Date"],
      [[p["first"], p["last"], blank(p["email"]), p["company"], blank(p["phone"]), p["country"], blank(p["city"]),
        rand_date("iso")]
       for p in sample(60)])

# 2) Salesforce-style: snake/abbrev, extra lead_source, US-format date
write("salesforce_leads.csv",
      ["fname","lname","email_address","account","mobile","nation","lead_source","created"],
      [[p["first"], p["last"], blank(p["email"],0.18), p["company"], blank(p["phone"],0.2), p["country"],
        random.choice(["web","referral","event","cold-call","partner"]), rand_date("us")]
       for p in sample(55)])

# 3) Stripe-style: single name field, business, mrr (money), ISO datetime
write("stripe_customers.csv",
      ["name","email","business","phone","country","mrr","signup_date"],
      [[f"{p['first']} {p['last']}", blank(p["email"],0.05), p["company"], blank(p["phone"],0.25),
        p["country"], f"${p['value']:,}", rand_date("isodt")]
       for p in sample(50)])

# 4) Intercom-style: yet another convention, month-name date
write("intercom_users.csv",
      ["full_name","work_email","organization","contact_number","location","registration_date"],
      [[f"{p['first']} {p['last']}", blank(p["email"],0.1), p["company"], blank(p["phone"],0.3),
        random.choice(CITIES), rand_date("month")]
       for p in sample(45)])

print(f"\ndone — four vendor exports (same customer entity, different schemas) in data/collections/")
