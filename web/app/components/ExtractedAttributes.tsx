import { Form } from "react-router";
import type { CollectionDetail } from "~/lib/types";

const TYPES: [string, string][] = [
  ["yes/no", "boolean"], ["category", "keyword"], ["number", "double"], ["text", "keyword"],
];

/** Define an attribute the LLM extracts from each entity's free text (e.g. has_garden:yes/no from a
 *  description). Extracted attributes become typed, filterable fields — this is what handles negation
 *  ("no garden") that vector search cannot. Requires an LLM endpoint (LLM_BASE_URL). */
export function ExtractedAttributes({ detail }: { detail: CollectionDetail }) {
  const extractions = detail.extractions ?? [];
  if (!detail.llm_available) {
    return (
      <section className="card">
        <h2>Extracted attributes <span className="mut">— structured fields from free text (LLM)</span></h2>
        <p className="hint">No LLM configured. Set <code>LLM_BASE_URL</code> (e.g. Ollama at
          <code> http://localhost:11434/v1</code>) and <code>LLM_MODEL</code> to enable attribute extraction.</p>
      </section>
    );
  }
  return (
    <section className="card">
      <h2>Extracted attributes <span className="mut">— structured fields the LLM reads from your text</span></h2>
      <p className="hint">
        Define an attribute and the LLM reads each description to fill it (handling negation — "no garden"
        → <b>false</b>). It becomes a typed, <b>filterable</b> field, so "no-garden flats" is now a real filter.
      </p>
      <Form method="post" className="searchbar" style={{ gap: 8, flexWrap: "wrap" }}>
        <input type="hidden" name="intent" value="add-extraction" />
        <input name="attr" type="text" placeholder="attribute (e.g. has_garden, property_type)" required
          pattern="[A-Za-z0-9 _-]+" />
        <label className="mut" style={{ display: "flex", alignItems: "center", gap: 6 }}>
          type
          <select name="type" defaultValue="boolean">
            {TYPES.map(([label, os]) => <option key={label} value={os}>{label}</option>)}
          </select>
        </label>
        <input name="values" type="text" placeholder="allowed values (optional, comma-sep)" style={{ width: "22ch" }} />
        <button className="btn" type="submit">+ extract</button>
      </Form>

      <div className="chips" style={{ marginTop: 12 }}>
        {extractions.length === 0 && <span className="mut">No extracted attributes yet.</span>}
        {extractions.map((e) => (
          <span key={e.name} className="chip" style={{ display: "inline-flex", gap: 6, alignItems: "center" }}>
            <b>{e.name}</b> <span className="mut">{e.os_type === "boolean" ? "yes/no" : e.os_type}</span>
            <span className="badge">{e.filled}/{detail.n_records} filled</span>
            <Form method="post" style={{ display: "inline" }}
              onSubmit={(ev) => { if (!confirm(`Remove extracted attribute "${e.name}"?`)) ev.preventDefault(); }}>
              <input type="hidden" name="intent" value="remove-extraction" />
              <input type="hidden" name="attr" value={e.name} />
              <button className="x" type="submit" title="remove">×</button>
            </Form>
          </span>
        ))}
      </div>
    </section>
  );
}
