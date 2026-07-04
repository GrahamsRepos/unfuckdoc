import type { CellValue } from "~/lib/types";

/** Render a consolidated value (scalar / array / labeled {type,value}) as a table cell. */
export function Cell({ value }: { value: CellValue | undefined }) {
  if (value === null || value === undefined || value === "") return <span className="mut">—</span>;
  if (Array.isArray(value)) {
    return (
      <>
        {value.map((v, i) => (
          <span key={i}>
            {i > 0 && <span className="mut">, </span>}
            {typeof v === "object" && v !== null ? (
              <><span className="mut">{v.type}:</span>{String(v.value)}</>
            ) : (
              String(v)
            )}
          </span>
        ))}
      </>
    );
  }
  return <>{String(value)}</>;
}

/** Colored type badge; `label` overrides the displayed text (e.g. os_type vs kind). */
export function KindBadge({ kind, label }: { kind: string; label?: string }) {
  return <span className={`kind k-${kind}`}>{label ?? kind}</span>;
}

/** Collapsible OpenSearch query DSL block. */
export function Dsl({ dsl, index }: { dsl: unknown; index?: string }) {
  if (!dsl) return null;
  const target = index ? `POST ${index}/_search` : "_search";
  return (
    <details className="dslbox" open>
      <summary>
        Equivalent OpenSearch query{" "}
        <span className="mut">— {target} · (search runs in-memory; shown for reference)</span>
      </summary>
      <pre className="dsl">{JSON.stringify(dsl, null, 2)}</pre>
    </details>
  );
}
