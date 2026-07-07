#!/usr/bin/env python3
"""Example data for evaluating semantic search over LARGER description text.

16 property listings in 4 themes. Within a theme the listings describe the same concept in
DELIBERATELY DIFFERENT vocabulary (no shared keywords) — so keyword search can't link them but a
good embedding model should. listings_eval.json is the ground truth: for each theme, the member ids
+ a natural-language query worded differently again.
"""
import csv, json, os

OUT = os.path.join(os.path.dirname(__file__), "samples")
os.makedirs(OUT, exist_ok=True)

# theme -> (query, [ (id, title, city, description) ... ])  — descriptions share concept, not words
DATA = {
    "outdoor_family": ("a house with a big backyard where kids and dogs can play", [
        ("L01", "The Willows", "Leeds", "A generously proportioned detached residence set behind a sprawling lawn, with a fenced paddock ideal for pets and a timber play frame for little ones. Ample room for children to run and roam in complete safety."),
        ("L02", "Orchard House", "Bristol", "This characterful home enjoys a mature fruit orchard and an expansive rear meadow. Families will appreciate the enclosed grounds where youngsters and animals can wander freely away from any traffic."),
        ("L03", "Meadowbank", "York", "Sitting on a substantial plot, the property offers a huge grassed area to the back, a swing set and a secure boundary — perfect for households with young children and energetic hounds."),
        ("L04", "Fielders Rest", "Norwich", "A spacious family dwelling wrapped by open green space, including a sizeable turfed garden and a dedicated pets' run. The outdoor area is fully bounded, giving toddlers and dogs a safe place to play."),
    ]),
    "transit_urban": ("an apartment with an easy public-transport commute into the city centre", [
        ("L05", "Tower View", "Manchester", "A sleek one-bedroom flat positioned mere moments from the underground line, with several bus routes and a tram halt on the doorstep. Commuting into the centre could scarcely be more effortless."),
        ("L06", "The Exchange", "Glasgow", "This modern apartment sits beside a busy interchange served by frequent rail links and a nearby metro entrance, making the daily journey to the business district quick and painless."),
        ("L07", "Platform Lofts", "Birmingham", "Superbly connected, the residence is a short stroll from the light-rail stop and the mainline station, offering rapid access to the heart of town for car-free professionals."),
        ("L08", "Signal House", "Sheffield", "Ideally placed for the commuter, with tram and bus connections immediately outside and a train terminus within walking distance — reaching downtown takes only minutes without a vehicle."),
    ]),
    "period_restored": ("a characterful old property that has been lovingly renovated", [
        ("L09", "Ivy Manor", "Bath", "A distinguished Victorian villa whose original cornicing, fireplaces and sash windows have been painstakingly restored, marrying nineteenth-century craftsmanship with tasteful modern comforts."),
        ("L10", "The Rectory", "Chester", "Full of heritage charm, this former parsonage retains its 1800s character — flagstone floors, beamed ceilings and period joinery — all sympathetically refurbished to a high standard."),
        ("L11", "Elm Cottage", "Ludlow", "Steeped in history, the dwelling preserves its antique timber framing and inglenook hearth, thoughtfully updated so that its old-world personality shines alongside contemporary conveniences."),
        ("L12", "Georgian House", "Stamford", "An elegant early-1900s townhouse where the authentic mouldings, panelled doors and decorative plasterwork have been faithfully revived by skilled artisans without losing their vintage soul."),
    ]),
    "water_views": ("a home with beautiful views over the water", [
        ("L13", "Estuary Reach", "Falmouth", "Perched above the tidal inlet, this property commands a sweeping outlook across the shimmering channel, with boats drifting past and the far shoreline framing every window."),
        ("L14", "Lakeside Lodge", "Windermere", "Set right at the water's edge, the house looks out over a tranquil mere, its rippling surface and wooded banks providing an ever-changing panorama from the living spaces."),
        ("L15", "Harbour Point", "Whitby", "Enjoying an enviable coastal aspect, the residence gazes out to the open sea, with wide marine horizons and passing vessels visible from the principal rooms."),
        ("L16", "River Walk", "Henley", "Occupying a riverside position, the home overlooks the gently flowing stream, offering serene aquatic vistas and reflections of the opposite bank throughout the day."),
    ]),
}

rows = []
eval_themes = {}
for theme, (query, listings) in DATA.items():
    # cities are unique per listing, so the scorer can identify a result row by its city
    eval_themes[theme] = {"query": query, "members": [l[0] for l in listings], "cities": [l[2] for l in listings]}
    for (lid, title, city, desc) in listings:
        rows.append([lid, title, city, desc])

with open(os.path.join(OUT, "listings.csv"), "w", newline="") as f:
    w = csv.writer(f); w.writerow(["listing_id", "title", "city", "description"]); w.writerows(rows)
with open(os.path.join(os.path.dirname(__file__), "listings_eval.json"), "w") as f:
    json.dump(eval_themes, f, indent=2)

wc = sum(len(r[3].split()) for r in rows) / len(rows)
print(f"wrote samples/listings.csv  ({len(rows)} listings, avg {wc:.0f} words/description)")
print(f"wrote listings_eval.json    ({len(eval_themes)} themes, 4 members each)")
