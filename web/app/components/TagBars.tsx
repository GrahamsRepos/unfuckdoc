import type { Overview } from "~/lib/types";

export function TagBars({ tags }: { tags: Overview["tags"] }) {
  const cols = Object.keys(tags ?? {});
  if (!cols.length) return null;
  return (
    <section className="card">
      <h2>Extracted tags / keywords</h2>
      <p className="hint">Pulled from free-text columns (YAKE) — the facets you can search &amp; segment on.</p>
      {cols.map((col) => {
        const items = tags[col];
        if (!items?.length) return null;
        const max = items[0][1];
        return (
          <div key={col}>
            <div className="mut" style={{ margin: "2px 0 8px" }}>
              <b style={{ color: "var(--ink)" }}>{col}</b> — top keywords
            </div>
            <div style={{ display: "flex", flexDirection: "column", gap: 5 }}>
              {items.slice(0, 15).map(([t, n]) => (
                <div key={t} style={{ display: "grid", gridTemplateColumns: "150px 1fr 40px", gap: 10, alignItems: "center", fontSize: 12.5 }}>
                  <span style={{ whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{t}</span>
                  <div style={{ height: 14, borderRadius: 4, width: `${Math.max(4, Math.round((n / max) * 100))}%`, background: "linear-gradient(90deg,#a371f7,#4c9be8)" }} />
                  <span className="mut" style={{ textAlign: "right" }}>{n}</span>
                </div>
              ))}
            </div>
          </div>
        );
      })}
    </section>
  );
}
