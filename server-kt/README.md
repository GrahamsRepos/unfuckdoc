# unfuckdoc-kt — Kotlin / Ktor / Koin exploration

A spike porting the **deterministic core** of the pipeline to Kotlin, to feel the ergonomics of a
JVM backend (Ktor server + Koin DI). Java 21, Gradle wrapper, no external services.

## What's ported (and verified byte-identical to the Python pipeline)

- **Classifier** (`domain/Classifier.kt`) — statistical type inference with a confidence *margin*;
  numeric / boolean / date / free_text / enum / identifier, currency-aware numeric detection.
- **Canonicalizer** (`domain/Canonicalizer.kt`) — priority-ordered alias table, type-gated
  (`email_address → email`, `Company Name → company`).
- **Pipeline** (`domain/Pipeline.kt`) — classify → canonicalize → light clean; emits the same
  catalog / merge-groups / LLM-escalation counter / coerced+quarantine counts.

Verified against the Python `process_dataframe`: `wine.csv` and `crm_contacts.csv` produce the same
kinds, canonicals, and counts (e.g. `crm_contacts`: 0 LLM, 120 coerced, `Signup Date → date`).

## Not in this slice (the ML 20%)

Enrichment — embeddings, keyword extraction, summarization — is intentionally omitted. On the JVM the
production path is **DJL** or **fastembed-java** (ONNX Runtime) to run `all-MiniLM-L6-v2` in-process
(same 384-d vectors), plus Smile/EJML for the LSA fallback. The OpenSearch client is first-class on the
JVM (`opensearch-java`).

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
build.gradle.kts                 Ktor 3 + Koin 4 + kotlinx.serialization + commons-csv
src/main/kotlin/com/unfuckdoc/
  Application.kt                 embeddedServer(Netty) + plugins (Koin, ContentNegotiation, ...)
  di/AppModule.kt                Koin module — pipeline as a graph of singletons
  domain/                        Classifier, Canonicalizer, Pipeline, CsvReader, Models
  routes/Routes.kt               /health, /api/samples, /api/process
```
