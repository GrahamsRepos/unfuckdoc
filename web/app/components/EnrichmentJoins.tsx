import { Form } from "react-router";
import type { CollectionDetail } from "~/lib/types";

/** Enrichment (lookup) joins, shown as a visual chain: THIS collection ⟵ joins a reference (another
 *  collection or a file) on a shared field, gaining its fields. Unlike the merge key, entities keep
 *  their identity and simply *gain* the attached fields (which become searchable/geo-filterable). */
export function EnrichmentJoins({ detail, samples, collections }:
  { detail: CollectionDetail; samples: string[]; collections: string[] }) {
  const joinable = detail.schema.map((s) => s.field);
  const enrichments = detail.enrichments ?? [];

  return (
    <section className="card">
      <h2>Enrichment joins <span className="mut">— chain a collection or file to gain its fields</span></h2>
      <p className="hint">
        Look up a reference on a shared field (e.g. <b>city</b>) and copy its other fields (e.g.
        <b> coordinates</b>) onto every matching entity. This <i>doesn&apos;t</i> merge — each entity keeps
        its identity and gains the attached fields, so you can then filter/map on them.
      </p>

      {/* the join, as a flow: [reference] ⟶ on <field> ⟶ [this collection gains fields] */}
      {enrichments.length > 0 && (
        <div className="join-chain">
          {enrichments.map((e) => (
            <div key={e.source} className="join-row">
              <span className="join-node ref">{e.from_collection ? "▦" : "📄"} {e.source}</span>
              <span className="join-edge">
                <span className="join-on">on {e.join_field}</span>
                <span className="arrow">──▶</span>
              </span>
              <span className="join-node self">
                {detail.name}
                <span className="join-gain">+ {e.attached.join(", ") || "—"}</span>
              </span>
              <span className="badge">{e.matched} matched</span>
              <Form method="post" style={{ marginLeft: "auto" }}
                onSubmit={(ev) => { if (!confirm(`Remove enrichment join from "${e.source}"?`)) ev.preventDefault(); }}>
                <input type="hidden" name="intent" value="remove-enrichment" />
                <input type="hidden" name="source" value={e.source} />
                <button className="x" type="submit" title="remove join">×</button>
              </Form>
            </div>
          ))}
        </div>
      )}

      <Form method="post" className="searchbar" style={{ gap: 8, flexWrap: "wrap", marginTop: enrichments.length ? 12 : 0 }}>
        <input type="hidden" name="intent" value="add-enrichment" />
        <span className="mut">Join</span>
        <select name="ref" defaultValue={collections[0] ? `collection:${collections[0]}` : samples[0]}>
          {collections.length > 0 && (
            <optgroup label="Collections">
              {collections.map((c) => <option key={c} value={`collection:${c}`}>▦ {c}</option>)}
            </optgroup>
          )}
          <optgroup label="Sample files">
            {samples.map((s) => <option key={s} value={s}>📄 {s}</option>)}
          </optgroup>
        </select>
        <span className="mut">on field</span>
        <select name="join_field" defaultValue={joinable.includes("city") ? "city" : joinable[0]}>
          {joinable.map((f) => <option key={f} value={f}>{f}</option>)}
        </select>
        <button className="btn" type="submit">+ join &amp; attach</button>
      </Form>
    </section>
  );
}
