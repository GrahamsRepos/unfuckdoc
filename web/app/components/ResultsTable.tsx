import type { SearchResult } from "~/lib/types";
import { Cell } from "./format";

export function ResultsTable({ columns, results, withScore = true, withTags = true }:
  { columns: string[]; results: SearchResult[]; withScore?: boolean; withTags?: boolean }) {
  const cols = columns.filter((c) => results.some((r) => c in r.row));
  return (
    <div className="scroll">
      <table>
        <thead>
          <tr>
            {withScore && <th>score</th>}
            {cols.map((c) => <th key={c}>{c.replace(/_summary$/, " ✎")}</th>)}
            {withTags && <th>tags</th>}
          </tr>
        </thead>
        <tbody>
          {results.map((r, i) => (
            <tr key={i}>
              {withScore && <td className="score">{r.score}</td>}
              {cols.map((c) => {
                let node = <Cell value={r.row[c]} />;
                const raw = r.row[c];
                if (c.endsWith("_summary") && typeof raw === "string" && raw.length > 140) {
                  node = <>{raw.slice(0, 140)}…</>;
                }
                return <td key={c}>{node}</td>;
              })}
              {withTags && (
                <td>
                  <div style={{ display: "flex", gap: 5, flexWrap: "wrap" }}>
                    {(r.keywords ?? []).map((k) => <span key={k} className="kw">{k}</span>)}
                  </div>
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
