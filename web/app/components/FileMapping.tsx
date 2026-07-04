import { useSubmit } from "react-router";
import type { CollectionDetail } from "~/lib/types";

const COMMON = [
  "full_name", "first_name", "last_name", "email", "phone", "company", "job_title",
  "country", "region", "city", "address", "postal_code", "amount", "rating", "quantity",
  "date", "url", "identifier", "description", "interests", "gender", "currency", "age",
];

/** Column → canonical mapping, editable: pick a different canonical to override the inference,
 *  or ↺ to re-infer. Changing a mapping rebuilds the merge (posts intent=set-mapping). */
export function FileMapping({ detail }: { detail: CollectionDetail }) {
  const submit = useSubmit();
  const canonicals = Array.from(new Set([...detail.schema.map((s) => s.field), ...COMMON])).sort();

  function setMapping(column: string, canonical: string) {
    const fd = new FormData();
    fd.set("intent", "set-mapping");
    fd.set("column", column);
    fd.set("canonical", canonical);
    submit(fd, { method: "post" });
  }

  return (
    <section className="card">
      <h2>Source files → column mapping <span className="mut">— override any inferred field</span></h2>
      <p className="hint">Each column is standardised to a canonical field. Wrong guess? Pick the right one — records re-merge on the new mapping.</p>
      <div className="merge">
        {detail.files.map((f) => (
          <div key={f.name} className="mgroup">
            <span className="src">{f.name}</span>
            <span className="mut">{f.rows} rows</span>
            {f.mapping.map((m) => (
              <span key={m.column} className="chip" style={m.method === "override" ? { outline: "1px solid #2f81f7" } : undefined}>
                <span className="mut">{m.column}</span> →
                <select value={m.canonical} title={`inferred by ${m.method}`}
                  onChange={(e) => setMapping(m.column, e.target.value === "__auto__" ? "" : e.target.value)}>
                  {!canonicals.includes(m.canonical) && <option value={m.canonical}>{m.canonical}</option>}
                  {canonicals.map((c) => <option key={c} value={c}>{c}</option>)}
                  <option value="__auto__">↺ auto-detect</option>
                </select>
                {m.method === "override" && <span className="badge" style={{ background: "#0d2847", color: "#79c0ff" }}>override</span>}
              </span>
            ))}
          </div>
        ))}
      </div>
    </section>
  );
}
