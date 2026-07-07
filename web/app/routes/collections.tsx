import { Form, Link, redirect } from "react-router";
import type { Route } from "./+types/collections";
import { api } from "~/lib/api";

export function meta(_: Route.MetaArgs) {
  return [{ title: "unfuckdoc — collections" }];
}

export async function loader() {
  const { collections } = await api.collections();
  return { collections };
}

export async function action({ request }: Route.ActionArgs) {
  const form = await request.formData();
  const intent = form.get("intent");
  if (intent === "create") {
    const name = String(form.get("name") ?? "").trim();
    const key = String(form.get("key") ?? "email").trim() || "email";
    if (name) {
      await api.createCollection(name, key);
      return redirect(`/collections/${encodeURIComponent(name)}`);
    }
  } else if (intent === "delete") {
    await api.deleteCollection(String(form.get("name")));
  }
  return redirect("/collections");
}

export default function Collections({ loaderData }: Route.ComponentProps) {
  const { collections } = loaderData;
  return (
    <section className="card">
      <h2>Collections</h2>
      <p className="hint">
        Dump many CSVs — even with different column names or partial fields. Each file's columns are
        standardised to <b>canonical</b> fields, and records that share the collection's <b>unique key</b>
        are <b>merged into one entity</b> (all datapoints combined). Then search, filter, and save segments.
      </p>
      <Form method="post" className="searchbar" style={{ gap: 8, flexWrap: "wrap" }}>
        <input type="hidden" name="intent" value="create" />
        <input name="name" type="text" placeholder="new collection name (e.g. customers)" />
        <label className="mut" style={{ display: "flex", alignItems: "center", gap: 6 }}>
          merge on
          {/* the collection is empty at creation, so there are no inferred fields yet — offer common
              identity keys but let you type any canonical name; you can also change it after adding files */}
          <input name="key" list="merge-keys" defaultValue="email" style={{ width: "13ch" }}
            title="the unique key used to merge the same entity across files — a high-cardinality identifier" />
          <datalist id="merge-keys">
            {["email", "phone", "identifier", "full_name", "company", "account", "user_id"].map((k) => <option key={k} value={k} />)}
          </datalist>
        </label>
        <button className="btn" type="submit">+ create</button>
      </Form>
      <p className="hint" style={{ marginTop: -6 }}>
        Only a few keys show here because the collection has no data yet. Pick a likely unique identifier
        (or type your own) — you can change it later on the collection&apos;s <b>Sources</b> stage, where the
        full inferred field list appears once you&apos;ve added a file.
      </p>

      <div className="merge" style={{ marginTop: 16 }}>
        {collections.length === 0 && <div className="mut">No collections yet — create one above.</div>}
        {collections.map((c) => (
          <div key={c.name} className="mgroup">
            <Link to={`/collections/${encodeURIComponent(c.name)}`} className="to">{c.name}</Link>
            <span className="mut">{c.n_records} entities · {c.n_fields} fields · {c.n_files} files</span>
            <span className="badge" title="merge key">⌘ {c.key_field}</span>
            <code style={{ marginLeft: "auto" }}>{c.index}</code>
            <Form method="post" onSubmit={(e) => { if (!confirm(`Delete collection "${c.name}"?`)) e.preventDefault(); }}>
              <input type="hidden" name="intent" value="delete" />
              <input type="hidden" name="name" value={c.name} />
              <button className="btn ghost" type="submit">delete</button>
            </Form>
          </div>
        ))}
      </div>
    </section>
  );
}
