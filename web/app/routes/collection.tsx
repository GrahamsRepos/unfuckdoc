import { Form, Link, NavLink, Outlet, redirect } from "react-router";
import type { Route } from "./+types/collection";
import { api } from "~/lib/api";

export function meta({ params }: Route.MetaArgs) {
  return [{ title: `collection · ${params.name}` }];
}

/** Layout for a single collection: shared header + the 3-stage stepper, with each stage rendered
 *  by a child route (Sources / Model / Explore). Child routes read the detail via the loader below. */
export async function loader({ params }: Route.LoaderArgs) {
  const [detail, samples] = await Promise.all([api.collection(params.name), api.samples()]);
  return { detail, samples: samples.samples };
}

/** Collection-level management: recalc (rebuild merge on the current key) and delete. */
export async function action({ request, params }: Route.ActionArgs) {
  const form = await request.formData();
  const intent = String(form.get("intent") ?? "");
  if (intent === "delete") {
    await api.deleteCollection(params.name);
    return redirect("/collections");
  }
  if (intent === "recalc") {
    await api.setCollectionKey(params.name, String(form.get("key") ?? "email"));
  }
  return redirect(`/collections/${encodeURIComponent(params.name)}`);
}

const STAGES = [
  { to: "", label: "Sources", n: 1, hint: "ingest" },
  { to: "model", label: "Model", n: 2, hint: "unify · canonicals · fixes" },
  { to: "explore", label: "Explore", n: 3, hint: "query · segments" },
];

export default function Collection({ loaderData, params }: Route.ComponentProps) {
  const { detail } = loaderData;
  const base = `/collections/${encodeURIComponent(params.name)}`;
  const os = detail.opensearch;

  return (
    <>
      <section className="card">
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", gap: 12, flexWrap: "wrap" }}>
          <h2>Collection: {detail.name} <span className="badge" title="merge key">⌘ merge on {detail.key_field}</span></h2>
          <div className="fieldbar" style={{ gap: 8 }}>
            <Form method="post" style={{ display: "inline" }}>
              <input type="hidden" name="intent" value="recalc" />
              <input type="hidden" name="key" value={detail.key_field} />
              <button className="btn ghost" type="submit" title="re-run classify → merge over all sources">↻ recalc</button>
            </Form>
            <Form method="post" style={{ display: "inline" }}
              onSubmit={(e) => { if (!confirm(`Delete collection "${detail.name}"? This removes its index too.`)) e.preventDefault(); }}>
              <input type="hidden" name="intent" value="delete" />
              <button className="btn ghost" type="submit" title="delete this collection">🗑 delete</button>
            </Form>
            <Link to="/collections" className="mut">← all</Link>
          </div>
        </div>

        {/* the workflow as a flow: sources -> merged entities -> fields -> index */}
        <div className="stats" style={{ margin: "14px 0", alignItems: "stretch", flexWrap: "wrap" }}>
          <div className="stat"><b>{detail.files.length}</b><span>sources</span></div>
          <span className="arrow">→</span>
          <div className="stat"><b>{detail.raw_records}</b><span>raw records</span></div>
          <span className="arrow" title={`${detail.merged} merged by ${detail.key_field}`}>⌘ →</span>
          <div className="stat ok"><b>{detail.n_records}</b><span>entities</span></div>
          <div className="stat"><b>{detail.schema.length}</b><span>canonical fields</span></div>
          <div className={`stat ${os.status === "indexed" ? "ok" : ""}`}><b>{os.count ?? 0}</b><span>{os.index ?? "index"}</span></div>
        </div>

        {/* stepper */}
        <nav className="stepper">
          {STAGES.map((s, i) => (
            <NavLink key={s.label} to={s.to === "" ? base : `${base}/${s.to}`} end={s.to === ""}
              className={({ isActive }) => `step${isActive ? " active" : ""}`}>
              <span className="step-n">{s.n}</span>
              <span className="step-label">{s.label}<small className="mut"> · {s.hint}</small></span>
              {i < STAGES.length - 1 && <span className="step-arrow">→</span>}
            </NavLink>
          ))}
        </nav>
      </section>

      <Outlet />
    </>
  );
}
