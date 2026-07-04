#!/usr/bin/env python3
"""Generate diverse-domain CSVs to stress-test canonical classification.

Column names are chosen to span the three canonicalization tiers:
  - alias    : in the dictionary (list_price->amount, postcode->postal_code, star_rating->rating)
  - semantic : open-vocab synonyms not in the dict (manufacturer, consignee, proprietor, carrier)
  - identity : genuinely novel domain terms (vin, isbn, genre, cuisine, fuel_type, seats)
plus deliberate edge cases (model_year, account_ref, title) to see where inference bends.
"""
import csv, os, random

random.seed(1234)
OUT = os.path.join(os.path.dirname(__file__), "samples")
os.makedirs(OUT, exist_ok=True)

cities = ["Toronto", "Berlin", "Mumbai", "Lyon", "São Paulo", "Malmö", "Osaka", "Lagos", "Madrid", "Austin"]
countries = ["Canada", "Germany", "India", "France", "Brazil", "Sweden", "Japan", "Nigeria", "Spain", "USA"]
firsts = ["Wei", "Mei", "Sven", "Freya", "Leila", "Priya", "Diego", "Yuki", "Omar", "Grace", "Noah", "Amara"]
lasts = ["Moreno", "Khan", "Costa", "Reddy", "Kovac", "Tanaka", "Bauer", "Silva", "Okoro", "Nguyen", "Haddad"]

def name():   return f"{random.choice(firsts)} {random.choice(lasts)}"
def email(n): return f"{n.lower().replace(' ', '.')}@example.com"
def date(y0=2021, y1=2024): return f"{random.randint(y0,y1)}-{random.randint(1,12):02d}-{random.randint(1,28):02d}"
def write(fname, header, rows):
    with open(os.path.join(OUT, fname), "w", newline="") as f:
        w = csv.writer(f); w.writerow(header); w.writerows(rows)
    print(f"  wrote samples/{fname:<22} {len(rows)} rows · {len(header)} cols")

# ---- 1. fleet / vehicles ----
makers = ["Toyota", "Ford", "Volkswagen", "Hino", "Scania", "Renault"]
fuels = ["diesel", "petrol", "electric", "hybrid"]
rows = []
for i in range(35):
    rows.append([f"REG-{random.randint(1000,9999)}", random.choice(makers), f"Model-{random.choice('XZTQ')}{random.randint(1,9)}",
                 random.randint(2012, 2023), random.randint(5000, 240000), f"${random.randint(8000,90000)}",
                 date(2018, 2024), random.choice(cities), random.choice(fuels), f"VIN{random.randint(10**9, 10**10)}"])
write("fleet.csv", ["reg_no", "manufacturer", "model", "model_year", "odometer_km", "list_price",
                    "acquired_on", "depot_city", "fuel_type", "vin"], rows)

# ---- 2. books / catalogue ----
genres = ["fiction", "history", "science", "biography", "poetry", "reference"]
pubs = ["Penguin", "Vertex Press", "Northwind Books", "Cobalt House", "Meridian"]
langs = ["English", "German", "French", "Japanese", "Spanish"]
rows = []
for i in range(30):
    rows.append([f"978-{random.randint(10**9,10**10)}", f"The {random.choice(['Silent','Golden','Broken','Distant'])} {random.choice(['River','Empire','Machine','Garden'])}",
                 name(), random.choice(pubs), date(1998, 2023), f"{random.randint(8,45)}.99",
                 random.randint(120, 640), random.choice(genres), random.choice(langs)])
write("books.csv", ["isbn", "title", "writer", "publisher", "released_on", "list_price", "pages", "genre", "language"], rows)

# ---- 3. shipments / logistics ----
carriers = ["Quantum Freight", "Blue Harbor Logistics", "Atlas Cargo", "Redwood Transport"]
rows = []
for i in range(32):
    n = name()
    rows.append([f"TRK{random.randint(10**7,10**8)}", n, email(n), random.choice(cities), random.choice(countries),
                 round(random.uniform(0.5, 480.0), 1), f"${random.randint(20,3200)}", date(2023, 2024), random.choice(carriers)])
write("shipments.csv", ["tracking_no", "consignee", "sender_email", "origin_city", "destination_country",
                        "weight_kg", "freight_cost", "dispatched_at", "carrier"], rows)

# ---- 4. restaurants / venues ----
cuisines = ["Italian", "Japanese", "Ethiopian", "Mexican", "Thai", "French"]
rows = []
for i in range(28):
    rows.append([f"{random.choice(['The','La','El','Casa'])} {random.choice(['Olive','Ember','Lotus','Copper','Fig'])}",
                 random.choice(cuisines), random.choice(cities), f"{random.randint(1000,9999)}",
                 random.randint(15, 120), round(random.uniform(3.2, 4.9), 1), date(2015, 2023), name(),
                 f"+1-555-{random.randint(1000,9999)}"])
write("restaurants.csv", ["venue", "cuisine", "locality", "postcode", "avg_spend", "star_rating",
                          "opened_on", "proprietor", "contact_no"], rows)

# ---- 5. saas accounts ----
orgs = ["Acme Analytics", "Vertex Robotics", "Sunrise Textiles", "Cobalt Health", "Northwind Traders"]
plans = ["free", "pro", "team", "enterprise"]
rows = []
for i in range(34):
    org = random.choice(orgs)
    rows.append([f"ACC-{random.randint(10000,99999)}", org, f"billing@{org.split()[0].lower()}.com",
                 random.randint(0, 12000), random.choice(plans), date(2020, 2024), random.randint(1, 400),
                 random.choice(countries), random.choice(cities)])
write("saas_accounts.csv", ["account_ref", "organisation", "billing_email", "mrr", "plan_tier",
                            "signed_up_on", "seats", "country", "region"], rows)

# ---- 6. donations / nonprofit ----
campaigns = ["Clean Water", "School Meals", "Reforest", "Winter Aid", "Health Drive"]
ccy = ["USD", "EUR", "GBP", "JPY"]
rows = []
for i in range(33):
    n = name()
    rows.append([n, random.randint(5, 5000), random.choice(ccy), date(2022, 2024), random.choice(campaigns),
                 email(n), random.choice(cities), f"{random.randint(1000,9999)}"])
write("donations.csv", ["donor_name", "gift_amount", "currency", "pledged_on", "campaign",
                        "donor_email", "home_city", "postcode"], rows)

print("done.")
