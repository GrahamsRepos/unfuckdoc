import type { CollectionFile } from "~/lib/types";

export function FileMapping({ files }: { files: CollectionFile[] }) {
  return (
    <section className="card">
      <h2>Source files → column mapping</h2>
      <div className="merge">
        {files.map((f) => (
          <div key={f.name} className="mgroup">
            <span className="src">{f.name}</span>
            <span className="mut">{f.rows} rows</span>
            {f.mapping.map((m) => (
              <span key={m.column} className="chip">
                <span className="mut">{m.column}</span> → <b>{m.canonical}</b>
              </span>
            ))}
          </div>
        ))}
      </div>
    </section>
  );
}
