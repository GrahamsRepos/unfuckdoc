import type { Column } from "~/lib/types";
import { KindBadge } from "./format";

export function ClassificationTable({ columns }: { columns: Column[] }) {
  return (
    <section className="card">
      <h2>Per-column classification</h2>
      <p className="hint">
        <b>margin</b> = confidence gap to the runner-up class; below the gate a column escalates to the LLM.
      </p>
      <div className="scroll">
        <table>
          <thead>
            <tr><th>Column</th><th>Kind</th><th>Canonical</th><th>Fill</th><th>Margin</th><th>Source</th><th>Searchable</th></tr>
          </thead>
          <tbody>
            {columns.map((c) => (
              <tr key={c.name}>
                <td>
                  <b>{c.name}</b>
                  {c.note && <><br /><span className="mut" style={{ fontSize: 11 }}>{c.note}</span></>}
                </td>
                <td><KindBadge kind={c.kind} /></td>
                <td>
                  <span className="canon">{c.canonical}</span>
                  {c.canonical_method !== "alias" && <span className="mut" style={{ fontSize: 10 }}> (self)</span>}
                </td>
                <td className="mut">{Math.round((c.fill_rate ?? 0) * 100)}%</td>
                <td>{c.margin ?? "—"}</td>
                <td>{c.source === "LLM" ? <span className="no">LLM</span> : <span className="mut">{c.source}</span>}</td>
                <td>{c.searchable ? "✓" : <span className="no">✗</span>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
