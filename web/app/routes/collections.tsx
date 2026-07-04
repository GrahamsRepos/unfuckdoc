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
    if (name) {
      await api.createCollection(name);
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
        A collection is a durable target schema. Add files of any shape — each file's columns are inferred
        and mapped onto the collection's canonical fields, and every record lands in one shared index.
      </p>
      <Form method="post" className="searchbar">
        <input type="hidden" name="intent" value="create" />
        <input name="name" type="text" placeholder="new collection name (e.g. customers)" />
        <button className="btn" type="submit">+ create</button>
      </Form>

      <div className="merge" style={{ marginTop: 16 }}>
        {collections.length === 0 && <div className="mut">No collections yet — create one above.</div>}
        {collections.map((c) => (
          <div key={c.name} className="mgroup">
            <Link to={`/collections/${encodeURIComponent(c.name)}`} className="to">{c.name}</Link>
            <span className="mut">{c.n_records} records · {c.n_fields} fields · {c.n_files} files</span>
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
