import { Form } from "react-router";
import type { CollectionDetail } from "~/lib/types";

// friendly label -> OpenSearch type stored on the canonical
const TYPES: [string, string][] = [
  ["text", "keyword"], ["long text", "text"], ["number", "double"],
  ["integer", "long"], ["date", "date"], ["true/false", "boolean"],
];
const typeLabel = (os: string) => TYPES.find(([, v]) => v === os)?.[0] ?? os;

/** A registry of user-defined canonical fields for this collection: define a name + type once, then
 *  map any file's columns onto it from the mapping dropdowns. */
export function CustomCanonicals({ detail }: { detail: CollectionDetail }) {
  const custom = detail.custom_canonicals ?? [];
  return (
    <section className="card">
      <h2>Custom canonicals <span className="mut">— define your own target fields</span></h2>
      <p className="hint">
        Define a canonical field (name + type) and it appears in the mapping dropdowns below. Map
        columns from any file onto it to unify data the built-in dictionary doesn&apos;t know.
      </p>

      <Form method="post" className="searchbar" style={{ gap: 8, flexWrap: "wrap" }}>
        <input type="hidden" name="intent" value="add-canonical" />
        <input name="canon" type="text" placeholder="canonical name (e.g. lead_tier)" required
          pattern="[A-Za-z0-9 _-]+" title="letters, numbers, spaces, _ or -" />
        <label className="mut" style={{ display: "flex", alignItems: "center", gap: 6 }}>
          type
          <select name="type" defaultValue="keyword">
            {TYPES.map(([label, os]) => <option key={os} value={os}>{label}</option>)}
          </select>
        </label>
        <button className="btn" type="submit">+ define</button>
      </Form>

      <div className="chips" style={{ marginTop: 12 }}>
        {custom.length === 0 && <span className="mut">No custom canonicals yet.</span>}
        {custom.map((cc) => (
          <span key={cc.name} className="chip" style={{ display: "inline-flex", gap: 6, alignItems: "center" }}>
            <b>{cc.name}</b>
            <span className="mut">{typeLabel(cc.os_type)}</span>
            {cc.in_use && <span className="badge" style={{ background: "#0d2847", color: "#79c0ff" }}>in use</span>}
            <Form method="post" style={{ display: "inline" }}
              onSubmit={(e) => { if (!confirm(`Delete custom canonical "${cc.name}"? Columns mapped to it revert to inference.`)) e.preventDefault(); }}>
              <input type="hidden" name="intent" value="delete-canonical" />
              <input type="hidden" name="canon" value={cc.name} />
              <button className="x" type="submit" title="delete">×</button>
            </Form>
          </span>
        ))}
      </div>
    </section>
  );
}
