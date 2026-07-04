import type { CollectionDetail } from "~/lib/types";

// validated categorical palette (dark-mode steps), assigned to files in fixed order
const FILE_COLORS = ["#3987e5", "#199e70", "#c98500", "#008300", "#9085e9", "#e66767", "#d55181", "#d95926"];
const OTHER = "#8b949e";

/** Bipartite graph: each source file's columns flow into canonical fields. Canonicals fed by two or
 *  more files are the cross-dataset MERGES (emphasised). Edge colour = source file. */
export function MergeGraph({ detail }: { detail: CollectionDetail }) {
  const files = detail.files;
  const canon = detail.schema;
  if (files.length === 0 || canon.length === 0) return null;

  const colorOf = (i: number) => FILE_COLORS[i] ?? OTHER;
  const W = 760, padY = 26;
  const rows = Math.max(files.length, canon.length);
  const H = rows * 30 + padY * 2;
  const leftX = 150, rightX = W - 150, midX = (leftX + rightX) / 2;
  const span = H - 2 * padY;
  const fileY = (i: number) => padY + (files.length === 1 ? span / 2 : (span * i) / (files.length - 1));
  const canY = (i: number) => padY + (canon.length === 1 ? span / 2 : (span * i) / (canon.length - 1));
  const canIndex = new Map(canon.map((s, i) => [s.field, i] as const));
  const merged = canon.filter((s) => s.n_sources > 1).length;

  const edges = files.flatMap((f, fi) =>
    f.mapping
      .map((m) => ({ fi, color: colorOf(fi), ci: canIndex.get(m.canonical), col: m.column, canon: m.canonical, file: f.name }))
      .filter((e) => e.ci !== undefined),
  );

  return (
    <section className="card">
      <h2>Merge graph <span className="mut">— how each file's columns unify into canonical fields</span></h2>
      <div className="chips" style={{ marginBottom: 6 }}>
        {files.map((f, i) => (
          <span key={f.name} className="chip">
            <span style={{ width: 10, height: 10, borderRadius: 2, background: colorOf(i), display: "inline-block" }} /> {f.name}
          </span>
        ))}
        <span className="mut">{merged} field{merged === 1 ? "" : "s"} merged across ≥2 files (bold)</span>
      </div>
      <div className="scroll">
        <svg viewBox={`0 0 ${W} ${H}`} width="100%" style={{ maxHeight: 560, minWidth: 560 }} role="img"
          aria-label="bipartite graph of source columns merging into canonical fields">
          {edges.map((e, i) => {
            const y1 = fileY(e.fi), y2 = canY(e.ci!);
            const strong = canon[e.ci!].n_sources > 1;
            return (
              <path key={i} d={`M ${leftX} ${y1} C ${midX} ${y1}, ${midX} ${y2}, ${rightX} ${y2}`}
                fill="none" stroke={e.color} strokeWidth={strong ? 2 : 1.25} strokeOpacity={strong ? 0.7 : 0.35}>
                <title>{e.file}: {e.col} → {e.canon}</title>
              </path>
            );
          })}
          {files.map((f, i) => (
            <g key={f.name}>
              <circle cx={leftX} cy={fileY(i)} r={5} fill={colorOf(i)} />
              <text x={leftX - 10} y={fileY(i) + 4} textAnchor="end" style={{ fontSize: 11.5, fill: "#c9d1d9" }}>{f.name}</text>
            </g>
          ))}
          {canon.map((s, i) => {
            const m = s.n_sources > 1;
            return (
              <g key={s.field}>
                <circle cx={rightX} cy={canY(i)} r={m ? 5 : 3.5} fill={m ? "#e6edf3" : "#6e7681"} />
                <text x={rightX + 10} y={canY(i) + 4} textAnchor="start"
                  style={{ fontSize: 11.5, fontWeight: m ? 600 : 400, fill: m ? "#e6edf3" : "#8b949e" }}>
                  {s.field}{m ? ` ·${s.n_sources}` : ""}
                </text>
              </g>
            );
          })}
        </svg>
      </div>
    </section>
  );
}
