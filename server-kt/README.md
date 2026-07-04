# unfuckdoc-kt — Kotlin / Ktor / Guice exploration

A spike porting the **deterministic core** of the pipeline to Kotlin, to feel the ergonomics of a
JVM backend: Ktor server + **kotlin-guice** DI (Kotlin-optimised Guice 7 DSL, constructor `@Inject`
with standard `jakarta.inject`). Java 21, Gradle wrapper.

## DI & testability

- Services and the HTTP `ApiController` use **constructor `@Inject` + `@Singleton`** (jakarta), so
  Guice just-in-time binds the whole graph (`Pipeline ← Classifier, Canonicalizer`, etc.). The domain
  classes depend only on standard `jakarta.inject`, not Guice.
- `AppModule : KotlinModule()` provides only the config-dependent binding (`OpenSearchService`
  host/port). `appInjector(vararg overrides)` wraps `Modules.override(AppModule()).with(...)`.
- **Tests swap deps selectively:** `ApiControllerTest` builds the real container but overrides one
  binding with a MockK — e.g. `bind<OpenSearchService>().toInstance(mock)` — so `/api/index` runs
  against a mocked cluster while the real pipeline graph is wired normally. `./gradlew test`.

## What's ported (and verified byte-identical to the Python pipeline)

- **Classifier** (`domain/Classifier.kt`) — statistical type inference with a confidence *margin*;
  numeric / boolean / date / free_text / enum / identifier, currency-aware numeric detection.
- **Canonicalizer** (`domain/Canonicalizer.kt`) — priority-ordered alias table, type-gated
  (`email_address → email`, `Company Name → company`).
- **Pipeline** (`domain/Pipeline.kt`) — classify → canonicalize → light clean; emits the same
  catalog / merge-groups / LLM-escalation counter / coerced+quarantine counts.

Verified against the Python `process_dataframe`: `wine.csv` and `crm_contacts.csv` produce the same
kinds, canonicals, and counts (e.g. `crm_contacts`: 0 LLM, 120 coerced, `Signup Date → date`).

## OpenSearch (real `opensearch-java` client)

- **IndexBuilder** consolidates the classified table into canonical-keyed, cleaned docs (scalar
  survivorship coalesce) + the `TypeMapping` body.
- **OpenSearchService** wraps the official `opensearch-java` client (Apache HttpClient5 transport):
  create index from mapping, bulk index, and search. Raw mapping/query JSON is parsed into typed
  `TypeMapping` / `Query` objects via each type's `_DESERIALIZER`.
- **Dsl** builds the query body from params: `term` / `range` (`>`, `<`, `..`) filters + `multi_match`.

Verified against the live cluster on `:9200`: `POST /api/index?sample=…` created `kt_crm_contacts`
(120 docs), and `/api/search` returns correct hits for term filters (`country=Japan`), range filters
(`amount>=200000`), and BM25 text match (`q=renewed`). Note: keyword fields are exact-match (analyzed
BM25 applies only to `text` fields) — real-DB behavior, unlike the Python in-memory substring search.

## Frontend contract (the RR7 SSR app runs on this)

The controller implements the API the `web/` frontend expects (snake_case via
`JsonNamingStrategy.SnakeCase`), backed by a single-dataset `DatasetService` (Python STATE parity):

- `GET  /api/samples` · `GET /api/overview`
- `POST /api/load_sample {name}` · `POST /api/upload` (multipart) — process → consolidate → index
- `POST /api/search {q,mode,field,tag,filters,size}` — keyword + range/date field filters
- `GET  /api/schema` — **OpenSearch mapping → JSON Schema (Draft 2020-12)**; arrays/`{type,value}`
  objects/vectors preserved from the consolidation metadata.

Point the frontend at it: `cd web && API_URL=http://localhost:8080 npm run dev`.

## Semantic field-name matching (embeddings)

The canonicalizer runs the deterministic alias dictionary first (fast, $0). Column names that fall
through to `identity` are embedded with **all-MiniLM-L6-v2 via DJL** and matched to the nearest
**type-compatible** canonical, scored by the **best alias word** (max-over-aliases, not an averaged
bag), and accepted only if it clears an absolute threshold **and** a margin over the runner-up (so an
ambiguous name is never force-routed). Assignments are tagged `method="semantic"`. The model loads
**lazily** — only when an unrecognized field actually appears. `UNFUCK_NO_EMBED=1` disables it.

Verified on `open_vocab.csv` (none of these are dictionary aliases):
`individual → full_name`, `corporation → company`, `webpage → url`, `handset → phone`,
`nationality → country`.

Still omitted: **document/text** embeddings for semantic *search* + keyword extraction (so `tags`/
vector search are absent; the frontend degrades to keyword mode). Same DJL path feeds a `knn_vector`
field when added.

## Run

```bash
./gradlew run                 # http://localhost:8080  (PORT / DATA_DIR overridable)
```

```bash
curl -X POST http://localhost:8080/api/load_sample -d '{"name":"samples/multi_contacts.csv"}' \
  -H 'Content-Type: application/json'
curl http://localhost:8080/api/schema | jq        # mapping -> JSON Schema
```

## Layout

```
build.gradle.kts                 Ktor 3 + kotlin-guice 3 + opensearch-java + kotlinx.serialization + commons-csv
src/main/kotlin/com/unfuckdoc/
  Application.kt                 module(controller) (reusable in tests) + embeddedServer(Netty)
  di/AppModule.kt                KotlinModule — explicit bind<T>().in<Singleton>() for every service
  di/Injectors.kt                appInjector(overrides…) = Modules.override seam for tests
  domain/                        Classifier, Canonicalizer, Consolidator, Pipeline, Cleaner, IndexBuilder, Dsl, CsvReader
  api/                           DatasetService (single-dataset state), Overview/Search DTOs, JsonSchema
  opensearch/OpenSearchService   opensearch-java client (index + search)
  routes/ApiController.kt        @Inject controller — /health, /api/{samples,overview,load_sample,upload,search,schema}
src/test/kotlin/com/unfuckdoc/
  ApiControllerTest.kt           real graph + MockK-swapped OpenSearchService via appInjector override
```
