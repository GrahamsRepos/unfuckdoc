# unfuckdoc web — React Router v7 (SSR)

Server-rendered frontend for the Python/Flask API. Loaders and actions run on the RR7
server and call the Flask backend over HTTP, so the browser only talks to this app
(no CORS). Search/filter state lives in the URL, so results are server-rendered.

## Run (dev)

```bash
# 1. start the Python API (from repo root)
python3 src/webapp.py            # http://localhost:5001

# 2. start the web app
cd web
npm install
npm run dev                      # http://localhost:3000
```

The Vite dev server proxies `/api/*` to `http://localhost:5001` (override with `API_URL`).

## Build / start (prod)

```bash
npm run build
API_URL=http://localhost:5001 npm run start
```

## Layout

```
app/
  root.tsx              document shell + <Nav> + error boundary
  routes.ts             route config
  routes/
    home.tsx            dashboard + URL-driven search (loader → /api/overview, /api/search)
    collections.tsx     list / create / delete collections
    collection.tsx      schema + file→canonical mapping + add-file + collection search
    match.tsx           fuzzy join between two datasets (URL-driven)
  components/           split, mostly pure SSR render components
  lib/
    api.ts              typed server-side client for the Flask API
    types.ts            TS mirrors of the JSON payloads
```
