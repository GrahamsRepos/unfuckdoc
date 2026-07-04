import { Link, redirect, useSubmit } from "react-router";
import { useRef } from "react";
import type { Route } from "./+types/collection";
import { api } from "~/lib/api";
import { CollectionSchema } from "~/components/CollectionSchema";
import { FileMapping } from "~/components/FileMapping";
import { CollectionSearchPanel } from "~/components/CollectionSearchPanel";

export function meta({ params }: Route.MetaArgs) {
  return [{ title: `collection · ${params.name}` }];
}

export async function loader({ params, request }: Route.LoaderArgs) {
  const url = new URL(request.url);
  const p = url.searchParams;
  const [detail, samples] = await Promise.all([api.collection(params.name), api.samples()]);
  const filters = p.getAll("f").map((s) => {
    const i = s.indexOf(":");
    return { field: s.slice(0, i), value: s.slice(i + 1) };
  });
  const hasQuery = p.get("q") || filters.length > 0;
  const search = hasQuery
    ? await api.searchCollection(params.name, { q: p.get("q") ?? "", filters, size: 30 })
    : null;
  return { detail, samples: samples.samples, search };
}

export async function action({ request, params }: Route.ActionArgs) {
  const form = await request.formData();
  const file = form.get("file");
  if (file instanceof File && file.size > 0) {
    const fd = new FormData();
    fd.append("file", file);
    await api.addFileToCollection(params.name, fd);
  } else {
    const sample = String(form.get("sample") ?? "");
    if (sample) await api.addSampleToCollection(params.name, sample);
  }
  return redirect(`/collections/${encodeURIComponent(params.name)}`);
}

export default function Collection({ loaderData, params }: Route.ComponentProps) {
  const { detail, samples, search } = loaderData;
  const os = detail.opensearch;
  const submit = useSubmit();
  const fileRef = useRef<HTMLInputElement>(null);

  return (
    <>
      <section className="card">
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
          <h2>Collection: {detail.name}</h2>
          <Link to="/collections" className="mut">← all collections</Link>
        </div>
        <div className="stats" style={{ margin: "14px 0" }}>
          <div className="stat"><b>{detail.n_records}</b><span>records</span></div>
          <div className="stat"><b>{detail.schema.length}</b><span>canonical fields</span></div>
          <div className="stat"><b>{detail.files.length}</b><span>source files</span></div>
          <div className={`stat ${os.status === "indexed" ? "ok" : ""}`}><b>{os.count ?? 0}</b><span>{os.index ?? "index"}</span></div>
        </div>
        <div className="fieldbar">
          <span className="mut">Add a file:</span>
          <form onSubmit={(e) => { e.preventDefault(); submit(new FormData(e.currentTarget), { method: "post" }); }}
            className="searchbar" style={{ gap: 8 }}>
            <select name="sample" defaultValue={samples.find((s) => s.startsWith("collections/")) ?? samples[0]}>
              {samples.map((s) => <option key={s} value={s}>{s}</option>)}
            </select>
            <button className="btn ghost" type="submit">+ add &amp; map</button>
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

      <CollectionSchema detail={detail} />
      <FileMapping files={detail.files} />
      <CollectionSearchPanel detail={detail} search={search} />
    </>
  );
}
