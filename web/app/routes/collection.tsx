import { Link, redirect, useSubmit } from "react-router";
import { useRef } from "react";
import type { Route } from "./+types/collection";
import { api } from "~/lib/api";
import { CollectionSchema } from "~/components/CollectionSchema";
import { FileMapping } from "~/components/FileMapping";
import { CollectionSearchPanel } from "~/components/CollectionSearchPanel";
import { Segments } from "~/components/Segments";

export function meta({ params }: Route.MetaArgs) {
  return [{ title: `collection · ${params.name}` }];
}

function parseFilters(p: URLSearchParams) {
  return p.getAll("f").map((s) => {
    const i = s.indexOf(":");
    return { field: s.slice(0, i), value: s.slice(i + 1) };
  });
}

export async function loader({ params, request }: Route.LoaderArgs) {
  const url = new URL(request.url);
  const p = url.searchParams;
  const [detail, samples] = await Promise.all([api.collection(params.name), api.samples()]);
  const filters = parseFilters(p);
  // empty search is match-any: browse the whole collection, narrowed by any selected tag/segment/filter
  const search = detail.n_records > 0
    ? await api.searchCollection(params.name, { q: p.get("q") ?? "", filters, size: 30 })
    : null;
  return { detail, samples: samples.samples, search, filters };
}

export async function action({ request, params }: Route.ActionArgs) {
  const form = await request.formData();
  const intent = String(form.get("intent") ?? "");
  const base = `/collections/${encodeURIComponent(params.name)}`;

  if (intent === "save-segment") {
    const seg = String(form.get("seg") ?? "").trim();
    const filters = JSON.parse(String(form.get("filters") ?? "[]"));
    if (seg) await api.putSegment(params.name, seg, filters);
    return redirect(base + String(form.get("next") ?? ""));
  }
  if (intent === "delete-segment") {
    await api.deleteSegment(params.name, String(form.get("seg")));
    return redirect(base);
  }

  // default: dump a file into the collection
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

export default function Collection({ loaderData }: Route.ComponentProps) {
  const { detail, samples, search, filters } = loaderData;
  const os = detail.opensearch;
  const submit = useSubmit();
  const fileRef = useRef<HTMLInputElement>(null);
  const dupRate = detail.raw_records > 0 ? Math.round((detail.merged / detail.raw_records) * 100) : 0;

  return (
    <>
      <section className="card">
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
          <h2>Collection: {detail.name} <span className="badge" title="merge key">⌘ merge on {detail.key_field}</span></h2>
          <Link to="/collections" className="mut">← all collections</Link>
        </div>

        {/* the workflow, as a flow: dump -> standardise -> merge -> entities */}
        <div className="stats" style={{ margin: "14px 0", alignItems: "stretch", flexWrap: "wrap" }}>
          <div className="stat"><b>{detail.files.length}</b><span>files dumped</span></div>
          <span className="arrow">→</span>
          <div className="stat"><b>{detail.raw_records}</b><span>raw records</span></div>
          <span className="arrow" title={`${detail.merged} records merged into an existing entity by ${detail.key_field}`}>
            ⌘ merged {detail.merged} <small className="mut">({dupRate}%)</small> →
          </span>
          <div className="stat ok"><b>{detail.n_records}</b><span>merged entities</span></div>
          <div className="stat"><b>{detail.schema.length}</b><span>canonical fields</span></div>
          <div className={`stat ${os.status === "indexed" ? "ok" : ""}`}><b>{os.count ?? 0}</b><span>{os.index ?? "index"}</span></div>
        </div>

        <div className="fieldbar">
          <span className="mut">Dump a file:</span>
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
      </section>

      <Segments detail={detail} activeFilters={filters} />
      <CollectionSchema detail={detail} />
      <FileMapping files={detail.files} />
      <CollectionSearchPanel detail={detail} search={search} />
    </>
  );
}
