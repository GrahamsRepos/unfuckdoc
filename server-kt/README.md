# unfuckdoc-kt — Kotlin / Ktor / Guice exploration

A spike porting the **deterministic core** of the pipeline to Kotlin, to feel the ergonomics of a
JVM backend (Ktor server + Guice DI). Java 21, Gradle wrapper, no external services.

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

## Not in this slice (the ML 20%)

Enrichment — embeddings, keyword extraction, summarization — is intentionally omitted. On the JVM the
production path is **DJL** or **fastembed-java** (ONNX Runtime) to run `all-MiniLM-L6-v2` in-process
(same 384-d vectors, feeding a `knn_vector` field), plus Smile/EJML for the LSA fallback.

## Run

```bash
./gradlew run                 # http://localhost:8080  (PORT / DATA_DIR overridable)
```

```bash
curl http://localhost:8080/api/samples
curl -X POST "http://localhost:8080/api/process?sample=wine.csv"      # classify a bundled sample
curl -X POST http://localhost:8080/api/process --data-binary @my.csv  # or a raw CSV body
```

## Layout

```
build.gradle.kts                 Ktor 3 + Guice 7 + opensearch-java + kotlinx.serialization + commons-csv
src/main/kotlin/com/unfuckdoc/
  Application.kt                 embeddedServer(Netty) + Guice injector + plugins (ContentNegotiation, ...)
  di/AppModule.kt                Guice module (@Provides singletons) — pipeline graph
  domain/                        Classifier, Canonicalizer, Pipeline, IndexBuilder, Dsl, CsvReader, Models
  opensearch/OpenSearchService   opensearch-java client (index + search)
  routes/Routes.kt               /health, /api/samples, /api/process, /api/index, /api/search
```
