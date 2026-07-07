import { Form } from "react-router";
import type { CollectionDetail } from "~/lib/types";

/** Lookup/enrichment joins: attach fields from a reference file onto entities that share a field
 *  (e.g. join people to a location table on `city` to attach `location` coords). Entities keep their
 *  identity and gain the attached fields — which then become searchable/geo-filterable. */
export function EnrichmentJoins({ detail, samples }: { detail: CollectionDetail; samples: string[] }) {
  const joinable = detail.schema.map((s) => s.field);   // fields to join on (must exist in both)
  const enrichments = detail.enrichments ?? [];

  return (
    <section className="card">
      <h2>Enrichment joins <span className="mut">— attach fields from a reference by a shared field</span></h2>
      <p className="hint">
        Look up a reference file on a shared field (e.g. <b>city</b>) and copy its other fields (e.g.
        <b> coordinates</b>) onto every matching entity. Unlike the merge key, this doesn&apos;t combine
        entities — each keeps its identity and <i>gains</i> the attached fields, so you can then filter/map on them.
      </p>

      <Form method="post" className="searchbar" style={{ gap: 8, flexWrap: "wrap" }}>
        <input type="hidden" name="intent" value="add-enrichment" />
        <span className="mut">Join</span>
        <select name="source" defaultValue={samples.find((s) => s.startsWith("collections/")) ?? samples[0]}>
          {samples.map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
        <span className="mut">on field</span>
        <select name="join_field" defaultValue={joinable.includes("city") ? "city" : joinable[0]}>
          {joinable.map((f) => <option key={f} value={f}>{f}</option>)}
        </select>
        <button className="btn" type="submit">+ join &amp; attach</button>
      </Form>

      <div className="merge" style={{ marginTop: 12 }}>
        {enrichments.length === 0 && <span className="mut">No enrichment joins yet.</span>}
        {enrichments.map((e) => (
          <div key={e.source} className="mgroup">
            <span className="src">{e.source}</span>
            <span className="mut">on <b>{e.join_field}</b> → attaches {e.attached.join(", ") || "—"}</span>
            <span className="badge">{e.matched} matched</span>
            <Form method="post" style={{ marginLeft: "auto" }}
              onSubmit={(ev) => { if (!confirm(`Remove enrichment join from "${e.source}"?`)) ev.preventDefault(); }}>
              <input type="hidden" name="intent" value="remove-enrichment" />
              <input type="hidden" name="source" value={e.source} />
              <button className="btn ghost" type="submit">remove</button>
            </Form>
          </div>
        ))}
      </div>
    </section>
  );
}
