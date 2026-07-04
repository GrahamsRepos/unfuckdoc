import type { MergeGroup } from "~/lib/types";

export function MergeGroups({ groups }: { groups: MergeGroup[] }) {
  return (
    <section className="card">
      <h2>Canonical structure — unified fields</h2>
      <p className="hint">
        Synonymous column names collapse onto one canonical field (type-gated, so a mislabeled column
        is never force-merged).
      </p>
      <div className="merge">
        {groups.map((g) => (
          <div key={g.canonical} className={`mgroup ${g.unified ? "unified" : ""}`}>
            <span className="to">{g.canonical}</span>
            <span className="arrow">◄</span>
            {g.columns.map((c) => <span key={c} className="src">{c}</span>)}
            {g.unified && <span className="badge">{g.columns.length} merged</span>}
          </div>
        ))}
      </div>
    </section>
  );
}
