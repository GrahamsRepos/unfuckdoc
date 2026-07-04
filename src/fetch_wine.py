"""Download the real messy wine-reviews dataset and save a sample to wine.csv."""
import urllib.request, pandas as pd
URL="https://raw.githubusercontent.com/davestroud/Wine/master/winemag-data-130k-v2.csv"
print("downloading 130k wine reviews (~51MB)...")
urllib.request.urlretrieve(URL, "wine_raw.csv")
df=pd.read_csv("wine_raw.csv")
print("shape:", df.shape, "| nulls in region_2:", int(df.region_2.isna().sum()), "of", len(df))
df.sample(n=2000, random_state=3).to_csv("wine.csv", index=False)
print("wrote wine.csv (2000-row sample)")
