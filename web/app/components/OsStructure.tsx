import type { Mapping, OsStatus, UnifiedField } from "~/lib/types";

const CAP: Record<string, string> = {
  keyword: "exact term filter / facet", text: "full-text (BM25)",
  double: "range filter / sort", long: "range filter / sort",
  date: "date range", boolean: "boolean filter",
  knn_vector: "semantic search (kNN)", object: "nested members",
};
const SUFFIXES = ["_summary", "_keywords", "_vector"];

function typeOf(def: any): string {
  if (def.type === "knn_vector") return "knn_vector";
  if (def.properties) return "object";
  return def.type;
}
function capNote(def: any): string {
  if (def.type === "knn_vector")
    return `${CAP.knn_vector} · ${def.dimension}-dim · ${def.method.name.toUpperCase()} / ${def.method.space_type.replace("simil", "")}`;
  if (def.properties) return CAP.object;
  return CAP[def.type] ?? "";
}

function Node({ name, def, shape, childProps, children }:
  { name: string; def: any; shape?: UnifiedField; childProps?: Record<string, any>; children?: string[] }) {
  const kids: React.ReactNode[] = [];
  if (def.properties) {
    for (const [k, d] of Object.entries<any>(def.properties)) kids.push(<Node key={k} name={k} def={d} />);
  }
  (children ?? []).forEach((ck) => childProps?.[ck] && kids.push(<Node key={ck} name={ck} def={childProps[ck]} />));

  let shapeLabel: React.ReactNode = null;
  if (shape) {
    if (shape.cardinality === "array") shapeLabel = <span className="osshape">[ array · {shape.style} ]</span>;
    else if (shape.sources.length > 1) shapeLabel = <span className="osshape">(coalesced)</span>;
  }

  return (
    <div className="osnode">
      <span className="osfield">{name}</span>
      <span className={`ostype t-${typeOf(def)}`}>{typeOf(def)}</span>
      <span className="oscap">{capNote(def)}</span>
      {shapeLabel}
      {kids.length > 0 && <div className="oschild">{kids}</div>}
    </div>
  );
}

export function OsStructure({ mapping, unified, opensearch, embedder, vecDim }:
  { mapping: Mapping; unified: UnifiedField[]; opensearch: OsStatus; embedder?: string; vecDim?: number }) {
  if (!mapping?.mappings) return null;
  const props = mapping.mappings.properties ?? {};
  const shapeOf: Record<string, UnifiedField> = {};
  unified.forEach((u) => (shapeOf[u.canonical] = u));

  const base: Record<string, any> = {};
  const childOf: Record<string, string[]> = {};
  const childProps: Record<string, any> = {};
  for (const k of Object.keys(props)) {
    const suf = SUFFIXES.find((s) => k.endsWith(s));
    if (suf) {
      const p = k.slice(0, -suf.length);
      (childOf[p] = childOf[p] ?? []).push(k);
      childProps[k] = props[k];
    } else {
      base[k] = props[k];
    }
  }

  const meta = opensearch.status === "indexed"
    ? <>live · <code>{opensearch.index}</code> · {opensearch.count} docs · <span style={{ color: "var(--good)" }}>index.knn ✓</span></>
    : <span className="mut">generated mapping · not indexed live</span>;

  return (
    <section className="card">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 8, marginBottom: 6 }}>
        <h2>OpenSearch index structure</h2>
        <span className="mut">{meta}{embedder && <> · embeddings: <b>{embedder}</b> ({vecDim}-d)</>}</span>
      </div>
      <p className="hint">
        The typed mapping generated for this dataset — what the messy CSV becomes in the DB. Free-text
        columns fan out into a BM25 summary, a keyword facet, and a kNN vector.
      </p>
      <div className="ostree">
        {Object.keys(base).map((k) => (
          <Node key={k} name={k} def={base[k]} shape={shapeOf[k]} childProps={childProps} children={childOf[k]} />
        ))}
      </div>
    </section>
  );
}
