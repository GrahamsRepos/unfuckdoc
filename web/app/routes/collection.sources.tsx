import { Link, redirect, useRouteLoaderData, useSubmit } from "react-router";
import { useRef } from "react";
import type { Route } from "./+types/collection.sources";
import { api } from "~/lib/api";
import type { CollectionDetail } from "~/lib/types";

export async function action({ request, params }: Route.ActionArgs) {
  const form = await request.formData();
  const intent = String(form.get("intent") ?? "");
  const base = `/collections/${encodeURIComponent(params.name)}`;

  if (intent === "set-key") {
    await api.setCollectionKey(params.name, String(form.get("key") ?? "email"));
    return redirect(base);
  }
  // default: dump a file into the collection (upload or sample)
  const file = form.get("file");
  if (file instanceof File && file.size > 0) {
    const fd = new FormData();
    fd.append("file", file);
    await api.addFileToCollection(params.name, fd);
  } else {
    const sample = String(form.get("sample") ?? "");
    if (sample) await api.addSampleToCollection(params.name, sample);
  }
  return redirect(base);
}

export default function Sources({ params }: Route.ComponentProps) {
  const parent = useRouteLoaderData("routes/collection") as { detail: CollectionDetail; samples: string[] };
  const { detail, samples } = parent;
  const base = `/collections/${encodeURIComponent(params.name)}`;
  const submit = useSubmit();
  const fileRef = useRef<HTMLInputElement>(null);
  const keyOptions = Array.from(new Set([
    detail.key_field, "email", "company", "account", "identifier", "phone", "full_name",
    ...detail.schema.map((s) => s.field),
  ])).filter(Boolean).sort();

  return (
    <>
      <section className="card">
        <h3>① Sources <span className="mut">— dump files; columns are standardised on ingest</span></h3>
        <div className="fieldbar" style={{ marginTop: 10 }}>
          <span className="mut">Add a source:</span>
          <form onSubmit={(e) => { e.preventDefault(); submit(new FormData(e.currentTarget), { method: "post" }); }}
            className="searchbar" style={{ gap: 8 }}>
            <select name="sample" defaultValue={samples.find((s) => s.startsWith("collections/")) ?? samples[0]}>
              {samples.map((s) => <option key={s} value={s}>{s}</option>)}
            </select>
            <button className="btn ghost" type="submit">+ add &amp; standardise</button>
          </form>
          <label className="file">
            ⬆ upload CSV
            <input ref={fileRef} type="file" accept=".csv"
              onChange={(e) => {
                if (e.currentTarget.files?.length) {
                  const fd = new FormData();
                  fd.append("file", e.currentTarget.files[0]);
                  submit(fd, { method: "post", encType: "multipart/form-data" });
                }
              }} />
          </label>
        </div>

        <form method="post" className="fieldbar" style={{ marginTop: 10, gap: 8 }}>
          <input type="hidden" name="intent" value="set-key" />
          <span className="mut">Associate records by</span>
          <select name="key" defaultValue={detail.key_field} title="canonical field used to merge records across source files">
            {keyOptions.map((k) => <option key={k} value={k}>{k}</option>)}
          </select>
          <button className="btn ghost" type="submit">rebuild merge</button>
        </form>
      </section>

      <section className="card">
        <h3>Sources in this collection <span className="mut">— {detail.files.length}</span></h3>
        {detail.files.length === 0 ? (
          <p className="hint">No sources yet. Add a sample or upload a CSV above.</p>
        ) : (
          <div className="merge" style={{ marginTop: 8 }}>
            {detail.files.map((f) => (
              <div key={f.name} className="mgroup">
                <span className="src">{f.name}</span>
                <span className="mut">{f.rows} rows · {f.mapping.length} columns</span>
              </div>
            ))}
          </div>
        )}
        {detail.files.length > 0 && (
          <p className="hint" style={{ marginTop: 12 }}>
            Next: <Link to={`${base}/canonical`} className="to">→ Canonical</Link> to review the unified schema,
            define custom canonicals, and fix any mappings.
          </p>
        )}
      </section>
    </>
  );
}
