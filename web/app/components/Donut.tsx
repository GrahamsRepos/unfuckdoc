const KCOL: Record<string, string> = {
  numeric: "#4c9be8", enum: "#a371f7", free_text: "#3fb950", date: "#d29922",
  identifier: "#8b96a5", boolean: "#e36209", empty: "#5a6472",
};

export function Donut({ kindCounts }: { kindCounts: Record<string, number> }) {
  const entries = Object.entries(kindCounts).sort((a, b) => b[1] - a[1]);
  const total = entries.reduce((s, [, n]) => s + n, 0) || 1;
  let acc = 0;
  const segs = entries.map(([k, n]) => {
    const a = (acc / total) * 360, b = ((acc + n) / total) * 360;
    acc += n;
    return `${KCOL[k] ?? "#888"} ${a}deg ${b}deg`;
  });
  return (
    <section className="card">
      <h2>Column types</h2>
      <p className="hint">Inferred from a statistical sample of each column.</p>
      <div className="donut-wrap">
        <div className="donut" style={{ background: `conic-gradient(${segs.join(",")})` }} />
        <ul className="legend">
          {entries.map(([k, n]) => (
            <li key={k}>
              <span className="sw" style={{ background: KCOL[k] ?? "#888" }} />
              <span><b>{k}</b> · {n} ({Math.round((n / total) * 100)}%)</span>
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}
