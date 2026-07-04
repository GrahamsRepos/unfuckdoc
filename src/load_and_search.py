"""
Load the cleaned+enriched wine docs into a Docker OpenSearch and run the 3 search modes.
Prereq:  docker compose up -d   &&   python3 clean_and_enrich.py wine.csv
         pip install opensearch-py joblib
Run:     python3 load_and_search.py
"""
import json, joblib
from opensearchpy import OpenSearch, helpers
from clean_and_enrich import LSA          # so the pickled embedder unpickles

INDEX="wine"
client=OpenSearch(hosts=[{"host":"localhost","port":9200}], use_ssl=False)  # security disabled in compose
emb=joblib.load("embedder.pkl")           # same model used at ingest -> embed queries consistently

# 1) create index from the generated mapping
if client.indices.exists(INDEX): client.indices.delete(INDEX)
client.indices.create(INDEX, body=json.load(open("wine_mapping.json")))

# 2) bulk load
def actions():
    L=open("wine_bulk.ndjson").read().splitlines()
    for i in range(0,len(L),2):
        m=json.loads(L[i])["index"]; yield {"_index":INDEX,"_id":m["_id"],"_source":json.loads(L[i+1])}
helpers.bulk(client, actions()); client.indices.refresh(INDEX)
print("loaded", client.count(index=INDEX)["count"], "docs\n")

def show(res):
    for h in res["hits"]["hits"]:
        s=h["_source"]; print(f"   {h.get('_score',0):.3f}  {str(s.get('title',''))[:50]:<50} {s.get('country','')}")

# 3a) SEMANTIC — kNN over description_vector (query embedded with the SAME model)
q="dark chocolate and bold tannins"
qv=emb.tf([q])[0].tolist()
print(f'SEMANTIC  "{q}"')
show(client.search(index=INDEX, body={"size":3,"_source":["title","country"],
      "query":{"knn":{"description_vector":{"vector":qv,"k":3}}}}))

# 3b) KEYWORD-TAG + PRIMITIVE filter — exact, cheap, explainable
print('\nFILTER  country=Italy AND points>=92 AND keyword="black cherry"')
show(client.search(index=INDEX, body={"size":3,"_source":["title","country"],"query":{"bool":{"filter":[
      {"term":{"country":"Italy"}}, {"range":{"points":{"gte":92}}},
      {"term":{"description_keywords":"black cherry"}}]}}}))

# 3c) HYBRID — BM25 on the summary + kNN on the vector (attach an RRF search pipeline for true fusion)
print('\nHYBRID  match(summary)="crisp citrus" + kNN(vector)')
show(client.search(index=INDEX, body={"size":3,"_source":["title","country"],"query":{"hybrid":{"queries":[
      {"match":{"description_summary":"crisp citrus"}},
      {"knn":{"description_vector":{"vector":emb.tf(["crisp citrus"])[0].tolist(),"k":3}}}]}}}))
