import csv, random
random.seed(5)
roles=["backend engineer","growth marketer","ceramic artist","data scientist","solar installer",
       "product designer","financial analyst","pediatric nurse","jazz musician","logistics manager"]
topics={"backend engineer":"distributed systems, Go, and event-driven architectures",
 "growth marketer":"B2B pipeline, paid acquisition, and lifecycle campaigns",
 "ceramic artist":"glazing techniques, wheel throwing, and craft markets",
 "data scientist":"NLP, embeddings, and retrieval-augmented search",
 "solar installer":"rooftop solar, battery storage, and rural electrification",
 "product designer":"accessibility, design systems, and typography",
 "financial analyst":"valuation models, M&A analysis, and portfolio risk",
 "pediatric nurse":"neonatal care, patient advocacy, and family education",
 "jazz musician":"improvisation, composition, and touring across Europe",
 "logistics manager":"route optimization, warehousing, and last-mile delivery"}
extras=["Open-source contributor and community mentor.","Enjoys trail running and specialty coffee.",
 "Speaks at conferences and writes a monthly newsletter.","Previously founded a small startup.",
 "Volunteers teaching workshops on weekends.","Fluent in three languages and travels often.",
 "Passionate about clean data and reproducible work.","Building a side project in their spare time."]
cities=["Cape Town","London","Berlin","Mumbai","Austin","Toronto"]; plans=["free","pro","enterprise"]
rows=[]
for i in range(1,121):
    role=random.choice(roles)
    bio=(f"{role.capitalize()} focused on {topics[role]}. {random.choice(extras)} {random.choice(extras)}")
    rows.append({"name":f"user{i}","email":f"user{i}@example.com","age":random.randint(22,64),
                 "signup_date":f"2026-0{random.randint(1,6)}-{random.randint(10,28)}",
                 "is_active":random.choice(["true","false"]),"plan":random.choice(plans),
                 "city":random.choice(cities),"bio":bio})
with open("profiles.csv","w",newline="") as f:
    w=csv.DictWriter(f,fieldnames=list(rows[0].keys())); w.writeheader(); w.writerows(rows)
print(f"wrote profiles.csv: {len(rows)} rows, distinct bios: {len(set(r['bio'] for r in rows))}")
