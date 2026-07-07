# Getting unfuckdoc running — step by step

Everything runs **locally on free, open models**. There are four moving parts:

```
OpenSearch (:9200)   ← index          (Docker)
Ollama    (:11434)   ← nomic + qwen   (native on macOS for GPU; or Docker on Linux)
server-kt (:8080)    ← the engine/API (Kotlin/Gradle)
web       (:3000)    ← the UI         (Node/RR7) → talks to :8080
```

## 0. Prerequisites

| Need | Why | Install |
|---|---|---|
| **JDK 21** | run the Kotlin backend | `brew install openjdk@21` (or any JDK 21) |
| **Node 18+** | run the frontend | `brew install node` |
| **Docker** | OpenSearch | Docker Desktop or Rancher Desktop |
| **Ollama** | the AI models | `brew install ollama` |
| ~**16 GB RAM** | qwen2.5:7b + the stack | Apple Silicon recommended (Metal GPU) |

> **Apple Silicon:** run **Ollama natively** (not in Docker) — a container can't use the Metal GPU, so
> a containerised Ollama would be CPU-only and slow. On 8 GB RAM, use `qwen2.5:3b` instead of `:7b`.

---

## 1. OpenSearch (the index)

```bash
cd /path/to/unfuckdoc
docker compose up -d           # single-node OpenSearch on :9200 (security disabled — dev only)
curl -s localhost:9200 | head  # should return JSON, not an error
```

*(The backend also works without OpenSearch — collection search is in-memory — but indexing/JSON-Schema
need it. Not for production as-is: no TLS/auth.)*

---

## 2. Ollama + the two models

Ollama is a small **native daemon** (wraps llama.cpp) serving an OpenAI-compatible API on `:11434`.

```bash
brew install ollama
brew services start ollama            # runs it in the background (or: `ollama serve`)

# pull the two models we use:
ollama pull nomic-embed-text          # embeddings for semantic search  (~275 MB, 768-dim, 8k ctx)
ollama pull qwen2.5:7b                # the LLM for attribute extraction (~4.7 GB)

ollama list                           # confirm both are present
```

**Sanity-check both endpoints:**
```bash
# embeddings
curl -s localhost:11434/v1/embeddings \
  -H 'Content-Type: application/json' \
  -d '{"model":"nomic-embed-text","input":"a house with a garden"}' | head -c 120

# chat (structured JSON)
curl -s localhost:11434/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"qwen2.5:7b","messages":[{"role":"user","content":"say {\"ok\":true} as json"}],"response_format":{"type":"json_object"},"stream":false}'
```

---

## 3. The backend (server-kt)

The models are chosen by **environment variables** — set them to point at Ollama. Without them the
backend still runs, but falls back to in-process MiniLM embeddings and **no LLM** (extraction disabled).

```bash
cd server-kt

PORT=8080 \
  EMBED_BASE_URL=http://localhost:11434/v1  EMBED_MODEL=nomic-embed-text \
  LLM_BASE_URL=http://localhost:11434/v1    LLM_MODEL=qwen2.5:7b \
  ./gradlew run

# in another terminal:
curl -s localhost:8080/health            # -> ok
```

First run downloads Gradle deps (a few minutes). The backend prints its URL when ready.

**Environment variables (full reference):**

| Var | Default | Meaning |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `EMBED_BASE_URL` | — (unset → in-process MiniLM/DJL) | OpenAI-compatible embeddings endpoint |
| `EMBED_MODEL` | `nomic-embed-text` | embedding model name |
| `LLM_BASE_URL` | — (unset → **no LLM**) | OpenAI-compatible chat endpoint |
| `LLM_MODEL` | `qwen2.5:7b` | LLM model name |
| `EMBED_API_KEY` / `LLM_API_KEY` | — | bearer token (for hosted endpoints, e.g. OVH) |
| `UNFUCK_NO_EMBED` | — | set to `1` to disable embeddings entirely |
| `OPENSEARCH_HOST` / `OPENSEARCH_PORT` | `localhost` / `9200` | OpenSearch location |
| `DATA_DIR` | `../data` | where sample CSVs live |

> **Tip:** to avoid retyping the env vars, drop them in a `server-kt/.env`-style shell script, e.g.
> `run-full.sh`, and `source` it — or export them in your shell profile.

**Point at OVH / a hosted model instead of Ollama:** same vars, different URL + a key, e.g.
`LLM_BASE_URL=https://<ovh-ai-endpoint>/v1 LLM_MODEL=<model> LLM_API_KEY=<key>`.

**Run the tests:** `./gradlew test` (all suites should be green; the MiniLM-based tests use the cached
model, so the first run may download it).

---

## 4. The frontend (web)

```bash
cd web
npm install
npm run dev                     # http://localhost:3000  (defaults to the backend on :8080)
```

To point the UI at a different backend: `API_URL=http://host:port npm run dev`.

---

## 5. Try it end-to-end

1. Open **http://localhost:3000/collections** → **+ create** a collection (pick a merge key, e.g. `email`).
2. On **① Sources**, add a sample file — e.g. `samples/listings.csv` (property listings with rich
   descriptions) or `samples/crm_contacts.csv`.
3. **② Canonical** — see the unified fields; try a **transform** (`clean_price = to_number(strip(raw_price,"$,"))`)
   and a **custom canonical**; if the LLM is on, define an **extracted attribute** (`has_garden`, yes/no)
   and watch the progress bar.
4. **③ Enrich** — chain another collection/file on a shared field (e.g. join a city→geo set on `city`).
5. **④ Explore** — filter by tag/range/geo; switch search mode to **semantic** and query by *meaning*
   (e.g. on `listings`: "somewhere with water views") — results show cosine **relevance bars**, and
   LLM attributes show as **✓/✗ badges**.

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| **"No LLM configured"** on the Canonical stage | backend started **without** `LLM_BASE_URL` — restart with the env vars (§3). |
| Semantic search seems weak / slow | using in-process MiniLM (256-token) — set `EMBED_BASE_URL` to nomic; or Ollama on CPU (Intel Mac) is slow. |
| **Collections vanished after a restart** | expected — collections are **in-memory only** (no persistence yet). Recreate them. |
| Leftover `col_*` / `kt_*` indexes in OpenSearch | orphans from restarts — `DELETE http://localhost:8080/api/admin/orphans`. |
| **UI shows stale content / 500 after editing routes** | the RR7 dev server caches — kill all `react-router dev` procs and restart `npm run dev`. |
| Ollama slow / not using GPU | on macOS run Ollama **natively** (`brew`), not in Docker; containers reach it at `host.docker.internal:11434`. |
| Docker OOM-kills containers | give the Docker/Rancher VM ≥ 12–16 GB RAM (Preferences → Resources). |
| Port already in use | something's on `:8080/:3000/:9200/:11434` — `lsof -ti tcp:<port> | xargs kill`. |

---

## What's running where (recap)

```
macOS host:  Ollama (native, Metal)  :11434   ← nomic + qwen
Docker:      OpenSearch              :9200
Gradle:      server-kt               :8080     ← EMBED_/LLM_ envs point at :11434
Node:        web (RR7)               :3000     → :8080
```
