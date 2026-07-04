import type { RegistryEntry } from "~/lib/types";

export function Registry({ entries }: { entries: RegistryEntry[] }) {
  if (!entries?.length) return null;
  return (
    <section className="card">
      <h2>Unified schema across all uploaded files</h2>
      <p className="hint">
        Accumulates across every file you load. Load several differently-named CSVs and watch their
        columns collapse onto the same canonical field.
      </p>
      <div className="merge">
        {entries.map((g) => (
          <div key={g.canonical} className={`mgroup ${g.unified ? "unified" : ""}`}>
            <span className="to">{g.canonical}</span>
            <span className="arrow">◄</span>
            {g.sources.map((s) => <span key={s.ref} className="src">{s.ref}</span>)}
            {g.unified && <span className="badge">{g.n_files} files</span>}
          </div>
        ))}
      </div>
    </section>
  );
}
