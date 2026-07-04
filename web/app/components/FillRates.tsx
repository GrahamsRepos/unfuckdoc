import type { Column } from "~/lib/types";

export function FillRates({ columns }: { columns: Column[] }) {
  return (
    <section className="card">
      <h2>Coverage (fill rate)</h2>
      <p className="hint">Nulls are coverage, not signal — sparse columns stay first-class.</p>
      <div>
        {columns.map((c) => {
          const pct = Math.round((c.fill_rate ?? 0) * 100);
          return (
            <div key={c.name} style={{ display: "grid", gridTemplateColumns: "160px 1fr 42px", gap: 10, alignItems: "center", margin: "4px 0", fontSize: 12.5 }}>
              <span title={c.name}>{c.name.length > 22 ? c.name.slice(0, 21) + "…" : c.name}</span>
              <div className="bar"><i style={{ width: `${pct}%`, background: pct < 50 ? "var(--warn)" : "var(--acc)" }} /></div>
              <span className="mut" style={{ textAlign: "right" }}>{pct}%</span>
            </div>
          );
        })}
      </div>
    </section>
  );
}
