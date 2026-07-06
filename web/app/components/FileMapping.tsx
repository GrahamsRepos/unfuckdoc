import { useSubmit } from "react-router";
import type { CollectionDetail } from "~/lib/types";

const COMMON = [
  "full_name", "first_name", "last_name", "email", "phone", "company", "job_title",
  "country", "region", "city", "address", "postal_code", "amount", "rating", "quantity",
  "date", "url", "identifier", "description", "interests", "gender", "currency", "age",
];

/** Column → canonical mapping, editable. Pick an existing canonical from the list OR type a new
 *  user-defined one — mismatched columns (here or in other files) can be mapped to the same custom
 *  canonical to unify them. ↺ re-infers. Any change rebuilds the merge (posts intent=set-mapping). */
export function FileMapping({ detail }: { detail: CollectionDetail }) {
  const submit = useSubmit();
  // collection-wide targets: every canonical already in the schema (incl. user-defined ones) + the
  // built-in common set. A custom canonical becomes a schema field once used, so it's offered everywhere.
  const canonicals = Array.from(new Set([...detail.schema.map((s) => s.field), ...COMMON])).sort();

  function setMapping(column: string, canonical: string) {
    const fd = new FormData();
    fd.set("intent", "set-mapping");
    fd.set("column", column);
    fd.set("canonical", canonical.trim());
    submit(fd, { method: "post" });
  }
  function commit(column: string, value: string, original: string) {
    const v = value.trim();
    if (v !== original.trim()) setMapping(column, v);   // "" clears the override (auto-detect)
  }

  return (
    <section className="card">
      <h2>Source files → column mapping <span className="mut">— override or define a canonical</span></h2>
      <p className="hint">
        Each column is standardised to a canonical field. Wrong guess? Pick the right one, or <b>type a
        new canonical name</b> to define your own — map mismatched columns from any file to the same
        name and they unify. Clear the box (↺) to re-infer. Records re-merge on change.
      </p>
      <datalist id="canon-targets">{canonicals.map((c) => <option key={c} value={c} />)}</datalist>
      <div className="merge">
        {detail.files.map((f) => (
          <div key={f.name} className="mgroup">
            <span className="src">{f.name}</span>
            <span className="mut">{f.rows} rows</span>
            {f.mapping.map((m) => {
              const custom = !canonicals.includes(m.canonical);
              return (
                <span key={m.column} className="chip"
                  style={m.method === "override" ? { outline: "1px solid #2f81f7" } : undefined}>
                  <span className="mut">{m.column}</span> →
                  <input list="canon-targets" defaultValue={m.canonical} key={m.canonical}
                    title={`inferred by ${m.method} — type a custom name to define your own`}
                    style={{ width: `${Math.max(9, m.canonical.length + 2)}ch` }}
                    onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); commit(m.column, e.currentTarget.value, m.canonical); } }}
                    onBlur={(e) => commit(m.column, e.currentTarget.value, m.canonical)} />
                  <button type="button" className="x" title="re-infer (auto-detect)"
                    onClick={() => setMapping(m.column, "")}>↺</button>
                  {m.method === "override" && (
                    <span className="badge" style={{ background: "#0d2847", color: "#79c0ff" }}>
                      {custom ? "custom" : "override"}
                    </span>
                  )}
                </span>
              );
            })}
          </div>
        ))}
      </div>
    </section>
  );
}
