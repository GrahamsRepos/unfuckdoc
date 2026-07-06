import type { CollectionDetail } from "~/lib/types";
import { KindBadge } from "./format";

export function CollectionSchema({ detail }: { detail: CollectionDetail }) {
  return (
    <section className="card">
      <h2>Unified schema <span className="mut" style={{ fontWeight: 400 }}>— inferred from the files</span></h2>
      <div className="scroll">
        <table>
          <thead>
            <tr><th>Canonical field</th><th>Type</th><th>Shape</th><th>Coverage</th><th>Contributed by</th></tr>
          </thead>
          <tbody>
            {detail.schema.map((s) => (
              <tr key={s.field}>
                <td>
                  <span className="canon">{s.field}</span>
                  {s.conflict && <span className="no"> ⚠ type conflict</span>}
                  {s.conflicts > 0 && (
                    <span className="no" title="entities where same-key duplicates disagreed — differing values kept as a list">
                      {" "}⚠ {s.conflicts} value conflict{s.conflicts === 1 ? "" : "s"}
                    </span>
                  )}
                </td>
                <td><KindBadge kind={s.kind} label={s.os_type ?? s.kind} /></td>
                <td>
                  <span className="badge" title={s.cardinality === "array" ? "indexed as tagged nested values in OpenSearch" : "indexed as a scalar value"}>
                    {s.cardinality === "array" ? "tagged array" : "scalar"}
                  </span>
                </td>
                <td className="mut">{s.count} / {detail.n_records}</td>
                <td>{s.sources.map((x) => <span key={x} className="src">{x}</span>)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
