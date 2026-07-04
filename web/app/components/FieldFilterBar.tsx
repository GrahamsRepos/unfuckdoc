import { useState } from "react";
import type { Facet, FieldFilter } from "~/lib/types";

function rangeHint(f?: Facet): string {
  if (f?.kind === "numeric") return "e.g. >100000, <50, 100000-200000";
  if (f?.os_type === "date") return "e.g. >2024-01-01, 2024-01-01..2024-06-30";
  return "value…";
}

/** Build field filters. Low-cardinality fields get a value dropdown; numeric/date/high-card get a
 *  text box that accepts range expressions. Active filters render as removable chips. */
export function FieldFilterBar({ facets, filters, onAdd, onRemove }:
  { facets: Facet[]; filters: FieldFilter[]; onAdd: (f: FieldFilter) => void; onRemove: (i: number) => void }) {
  const [field, setField] = useState(facets[0]?.field ?? "");
  const [value, setValue] = useState("");
  const facet = facets.find((f) => f.field === field);

  function add() {
    if (field && value) { onAdd({ field, value }); setValue(""); }
  }

  return (
    <div className="fieldbar">
      <span className="mut">Filter by field:</span>
      <select value={field} onChange={(e) => { setField(e.target.value); setValue(""); }}>
        {facets.map((f) => <option key={f.field} value={f.field}>{f.field} ({f.distinct})</option>)}
      </select>
      {facet?.values ? (
        <select value={value} onChange={(e) => setValue(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && add()}>
          <option value="">— value —</option>
          {facet.values.map(([v, n]) => <option key={v} value={v}>{v} ({n})</option>)}
        </select>
      ) : (
        <input type="text" value={value} placeholder={rangeHint(facet)}
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); add(); } }} />
      )}
      <button type="button" className="btn ghost" onClick={add}>+ add filter</button>
      <span className="chips">
        {filters.map((f, i) => (
          <span key={i} className="chip">
            <b>{f.field}</b> = {f.value}
            <button type="button" className="x" onClick={() => onRemove(i)}>×</button>
          </span>
        ))}
      </span>
    </div>
  );
}
