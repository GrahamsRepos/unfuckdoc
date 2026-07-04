import type { MatchResult } from "~/lib/types";

export function MatchReport({ result, a, b }: { result: MatchResult; a: string; b: string }) {
  if (result.error) return <div className="empty">⚠ {result.error}</div>;
  const pct = result.keyed_a ? Math.round((result.matched / result.keyed_a) * 100) : 0;
  const shortA = a.split("/").pop(), shortB = b.split("/").pop();
  return (
    <div style={{ marginTop: 14 }}>
      <div className="stats" style={{ margin: "6px 0" }}>
        <div className="stat ok"><b>{result.matched}</b><span>matched ({pct}%)</span></div>
        <div className="stat"><b>{result.exact}</b><span>exact</span></div>
        <div className="stat"><b>{result.matched - result.exact}</b><span>fuzzy</span></div>
        <div className="stat warn"><b>{result.unmatched_a}</b><span>only in A</span></div>
        <div className="stat warn"><b>{result.unmatched_b}</b><span>only in B</span></div>
      </div>
      <p className="hint">
        joined <code>{shortA}</code> ⋈ <code>{shortB}</code> on{" "}
        <span className="canon">{result.key}</span> · threshold {result.threshold}
      </p>
      {result.pairs.length > 0 && (
        <div className="scroll">
          <table>
            <thead>
              <tr>
                <th>sim</th>
                {result.display_a.map((c) => <th key={c}>A · {c}</th>)}
                <th className="mut">⋈</th>
                {result.display_b.map((c) => <th key={c}>B · {c}</th>)}
              </tr>
            </thead>
            <tbody>
              {result.pairs.map((p, i) => (
                <tr key={i}>
                  <td className="score" style={p.sim >= 1 ? undefined : { color: "var(--warn)" }}>{p.sim}</td>
                  {result.display_a.map((c) => <td key={c}>{p.a[c] || <span className="mut">—</span>}</td>)}
                  <td className="mut">⋈</td>
                  {result.display_b.map((c) => <td key={c}>{p.b[c] || <span className="mut">—</span>}</td>)}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
