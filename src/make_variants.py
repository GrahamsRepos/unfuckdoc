"""
Generate a spread of messy, realistic CSVs across DIFFERENT domains to stress-test the pipeline
beyond contact data. Each file exercises specific behaviours:

  products.csv        e-commerce  — currency ($1,299.00), booleans, enum category, free-text, quarantine
  employees.csv       HR          — mixed date format, yes/no booleans, salary, bio free-text, sparse col
  transactions.csv    finance     — multi-currency money, ISO dates, enum status, bad cells -> quarantine
  support_tickets.csv support     — TWO free-text columns (subject + body), very sparse numeric score
  movies.csv          media       — genre_1/2/3 positional array, numeric year/runtime, synopsis free-text
  properties.csv      real estate — numeric beds/baths/sqft, price, free-text listing, agent contact

Run:  python3 src/make_variants.py     # writes data/samples/*.csv   (reproducible; pass a seed to vary)
"""
import csv, os, sys, random

SEED = int(sys.argv[1]) if len(sys.argv) > 1 else 7
random.seed(SEED)

BASE = os.path.join(os.path.dirname(__file__), "..", "data", "samples")
os.makedirs(BASE, exist_ok=True)

FIRST = ["Aisha","Liam","Sofia","Kenji","Marcus","Priya","Elena","Tomas","Grace","Omar","Yuki","Noah",
         "Amara","Diego","Ingrid","Ravi","Chloe","Mateo","Fatima","Lars","Nadia","Ethan","Wei","Camila"]
LAST  = ["Okoro","Nguyen","Rossi","Tanaka","Hall","Patel","Kovac","Silva","Bauer","Haddad","Sato","Brown",
         "Diallo","Moreno","Larsen","Reddy","Dubois","Costa","Khan","Berg","Ferrari","Yusuf","Lindqvist"]
CITIES = ["Austin","Berlin","Osaka","Sao Paulo","Mumbai","Lyon","Lagos","Malmo","Toronto","Valencia","Denver","Leeds"]
COUNTRIES = ["United States","Germany","Japan","Brazil","India","France","Nigeria","Sweden","Canada","Spain"]

def maybe(v, p=0.15):
    return "" if random.random() < p else v
def name():
    return f"{random.choice(FIRST)} {random.choice(LAST)}"
def email(n):
    a, b = n.lower().split(); return f"{a}.{b}@{random.choice(['acme','corp','mail','biz','co'])}.com"
def phone():
    return f"+1-{random.randint(200,999)}-{random.randint(100,999)}-{random.randint(1000,9999)}"
def compose(parts, k=3):
    return " ".join(random.sample(parts, min(k, len(parts))))

def write(fname, header, rows):
    path = os.path.join(BASE, fname)
    with open(path, "w", newline="") as fh:
        w = csv.writer(fh); w.writerow(header)
        for r in rows: w.writerow(r)
    print(f"wrote {fname:<20} {len(rows):>4} rows x {len(header):>2} cols")

# ---------------------------------------------------------------- products
CATEGORIES = ["Home & Kitchen","Electronics","Outdoor","Office","Toys","Beauty","Automotive","Garden"]
ADJ = ["compact","premium","rugged","lightweight","eco-friendly","smart","ergonomic","stainless","cordless","modular"]
NOUN = ["blender","headphones","backpack","desk lamp","water bottle","drone","chair","tent","speaker","kettle"]
PROD_BITS = ["Engineered for everyday durability with a two-year warranty.",
             "Ships flat and assembles in under ten minutes with no tools.",
             "Rated best-in-class for battery life by independent reviewers.",
             "Made from recycled materials and fully recyclable packaging.",
             "Backed by responsive customer support and easy returns.",
             "Designed in Scandinavia with a minimalist, timeless look.",
             "Handles heavy daily use without losing performance.",
             "A customer favourite that consistently earns five-star reviews."]
def products(n=140):
    rows = []
    for i in range(n):
        price = random.choice([f"${random.randint(9,1999):,}.99", f"{random.randint(9,1999)}.99"])
        rating = random.choice([round(random.uniform(1, 5), 1)] * 9 + ["unrated"])   # ~10% bad -> quarantine
        rows.append([f"SKU-{100000+i}", f"{random.choice(ADJ).title()} {random.choice(NOUN).title()}",
                     random.choice(CATEGORIES), price, random.choice(["true","false"]),
                     rating, maybe(compose(PROD_BITS, 3), 0.1),
                     f"2023-{random.randint(1,12):02d}-{random.randint(1,28):02d}"])
    return rows
write("products.csv",
      ["sku","product_name","category","price","in_stock","rating","description","launch_date"],
      products())

# ---------------------------------------------------------------- employees
DEPTS = ["Engineering","Sales","Marketing","Finance","Operations","People","Support","Design"]
ROLES = ["Manager","Senior Analyst","Director","Coordinator","Lead","Specialist","VP","Associate"]
MONTHS = ["Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"]
BIO_BITS = ["Joined from a fast-growing logistics startup where they scaled the ops team.",
            "Spent a decade in enterprise software before moving into people leadership.",
            "Known internally as the go-to person for gnarly data problems.",
            "Mentors two junior teammates and runs the monthly brown-bag sessions.",
            "Passionate about accessibility and pushes for it in every review.",
            "Previously founded a small consultancy serving healthcare clients.",
            "Leads the cross-functional guild on developer experience.",
            "Champions remote-first practices and thoughtful async communication."]
def employees(n=120):
    rows = []
    for i in range(n):
        nm = name()
        hire = f"{random.choice(MONTHS)} {random.randint(1,28)}, {random.randint(2016,2024)}"  # "Mar 4, 2021"
        rows.append([f"E{2000+i}", nm.split()[0], nm.split()[1], random.choice(DEPTS),
                     f"{random.choice(ROLES)}", f"${random.randint(45,210)*1000:,}",
                     hire, email(nm), random.choice(["yes","no"]),
                     maybe(name(), 0.2),                                   # manager, 20% null
                     maybe(compose(BIO_BITS, 3), 0.1), maybe(random.choice(CITIES), 0.35)])  # office, sparse
    return rows
write("employees.csv",
      ["emp_id","first_name","last_name","department","title","salary","hire_date","work_email",
       "is_remote","manager","bio","office"],
      employees())

# ---------------------------------------------------------------- transactions
STATUS = ["completed","pending","failed","refunded"]
PAY = ["visa","mastercard","paypal","bank_transfer","apple_pay"]
CCY = ["USD","EUR","GBP"]
SYM = {"USD":"$","EUR":"€","GBP":"£"}
def transactions(n=160):
    rows = []
    for i in range(n):
        ccy = random.choice(CCY); amt_n = random.randint(5, 9000)
        amt = random.choice([f"{SYM[ccy]}{amt_n:,}.{random.randint(0,99):02d}", f"{amt_n}.00"])
        if random.random() < 0.04: amt = random.choice(["N/A", "PENDING", "-"])  # bad money -> quarantine
        rows.append([f"TXN{random.randint(10**9,10**10-1)}",
                     f"2024-{random.randint(1,12):02d}-{random.randint(1,28):02d}T{random.randint(0,23):02d}:{random.randint(0,59):02d}:00Z",
                     amt, ccy, email(name()), random.choice(STATUS), random.choice(PAY),
                     maybe(random.choice(["chargeback opened","verified by 3DS","flagged for review",
                                          "recurring subscription","first-time buyer",""]), 0.5)])
    return rows
write("transactions.csv",
      ["txn_id","timestamp","amount","currency","customer_email","status","payment_method","memo"],
      transactions())

# ---------------------------------------------------------------- support tickets (two free-text cols)
PRIORITY = ["low","medium","high","urgent"]
TSTATUS = ["open","pending","resolved","closed"]
SUBJECTS = ["Cannot log in after password reset","Export to CSV is missing columns",
            "Billing charged me twice this month","Dashboard loads very slowly",
            "Feature request: dark mode","API returns 500 on bulk upload",
            "How do I invite teammates?","Data not syncing from integration"]
BODY_BITS = ["The issue started right after the latest release went out on Tuesday.",
             "I've tried clearing the cache and using an incognito window with no luck.",
             "This is blocking my whole team from getting their reports out on time.",
             "Attaching the console logs and a screen recording for reference.",
             "It works fine on staging but consistently fails in production.",
             "Happy to hop on a call if that helps you reproduce it faster.",
             "We're on the enterprise plan and this is impacting a customer demo.",
             "Reproduced it on both Chrome and Safari across two machines."]
def tickets(n=130):
    rows = []
    for i in range(n):
        score = random.choice([random.randint(1,5)] * 3 + [""] * 7)   # very sparse CSAT
        rows.append([f"TK-{5000+i}", random.choice(SUBJECTS), compose(BODY_BITS, 3),
                     random.choice(PRIORITY), random.choice(TSTATUS),
                     f"2024-{random.randint(1,12):02d}-{random.randint(1,28):02d}",
                     maybe(name(), 0.25), email(name()),
                     random.choice(["true","false"]), score])
    return rows
write("support_tickets.csv",
      ["ticket_id","subject","body","priority","status","created_at","assignee",
       "customer_email","resolved","satisfaction_score"],
      tickets())

# ---------------------------------------------------------------- movies (positional genre array)
GENRES = ["Drama","Comedy","Thriller","Sci-Fi","Romance","Horror","Action","Documentary","Fantasy","Mystery"]
DIRECTORS = [name() for _ in range(30)]
SYN_BITS = ["A quiet town hides a secret that unravels over one long summer.",
            "Two strangers cross paths and change the course of each other's lives.",
            "An unlikely hero must outwit a ruthless corporation to survive.",
            "A family reckons with loss while restoring an old seaside house.",
            "A detective's last case forces her to confront her own past.",
            "In a near future, memory itself becomes the most valuable currency.",
            "A road trip across the country becomes a search for belonging.",
            "Rivals in a small orchestra learn the cost of ambition."]
def movies(n=120):
    rows = []
    for i in range(n):
        g = random.sample(GENRES, random.randint(1, 3))
        rows.append([f"A {random.choice(['Quiet','Bright','Long','Distant','Golden'])} {random.choice(['Summer','Horizon','Promise','Echo','Season'])}",
                     random.randint(1975, 2024), g[0], g[1] if len(g) > 1 else "", g[2] if len(g) > 2 else "",
                     random.choice(DIRECTORS), round(random.uniform(4.5, 9.3), 1),
                     random.randint(78, 190), compose(SYN_BITS, 2), random.choice(COUNTRIES)])
    return rows
write("movies.csv",
      ["title","year","genre_1","genre_2","genre_3","director","imdb_rating","runtime_min","synopsis","country"],
      movies())

# ---------------------------------------------------------------- properties (real estate)
PTYPE = ["Apartment","House","Condo","Townhouse","Loft","Bungalow"]
LISTING_BITS = ["Bright open-plan living space with floor-to-ceiling windows.",
                "Recently renovated kitchen with quartz counters and new appliances.",
                "Steps from the park, cafes and the metro station.",
                "Quiet tree-lined street in a sought-after school district.",
                "Private south-facing garden perfect for entertaining.",
                "Move-in ready with fresh paint throughout and hardwood floors.",
                "Rare corner unit with abundant natural light and storage.",
                "Comes with two secure parking spaces and a large basement."]
def properties(n=110):
    rows = []
    for i in range(n):
        nm = name()
        rows.append([f"L{700000+i}", f"{random.randint(1,9999)} {random.choice(['Oak','Maple','Cedar','Elm','Birch'])} St",
                     random.choice(CITIES), random.choice(["CA","NY","TX","WA","IL","CO","OR"]),
                     f"${random.randint(180,2400)*1000:,}", random.randint(1, 6), random.choice([1,1.5,2,2.5,3,3.5]),
                     f"{random.randint(480,4200):,}", random.choice(PTYPE),
                     f"2024-{random.randint(1,12):02d}-{random.randint(1,28):02d}",
                     compose(LISTING_BITS, 3), email(nm), maybe(phone(), 0.2)])
    return rows
write("properties.csv",
      ["listing_id","address","city","state","list_price","bedrooms","bathrooms","sqft","property_type",
       "listed_date","listing_description","agent_email","agent_phone"],
      properties())

print(f"\ndone (seed={SEED}) — six domain CSVs written to data/samples/")
