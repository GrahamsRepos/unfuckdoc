import { useState } from "react";
import type { Facet, FieldFilter } from "~/lib/types";

function isRange(f: Facet) {
  return f.kind === "numeric" || f.os_type === "date" || !f.values?.length;
}
function rangeHint(f: Facet) {
  if (f.kind === "numeric") return "e.g. >100000, 100000-200000";
  if (f.os_type === "date") return "e.g. >2024-01-01, 2024-01-01..2024-06-30";
  return "contains…";
}

/** Two-stage filter: ① choose one or more canonical fields, ② pick from the tags (values) belonging
 *  to those fields. Enumerable keyword fields show their values as toggleable tag chips; numeric/date
 *  fields show a range input. Active selections render as removable pills. */
export function TagPicker({ facets, filters, onAdd, onRemove }:
  { facets: Facet[]; filters: FieldFilter[]; onAdd: (f: FieldFilter) => void; onRemove: (i: number) => void }) {
  // stage ①: which fields are open. Default: fields that already have an active filter, else the first enumerable one.
  const filtered = new Set(filters.map((f) => f.field));
  const firstEnum = facets.find((f) => f.values?.length)?.field;
  const [open, setOpen] = useState<string[]>(() => {
    const init = facets.filter((f) => filtered.has(f.field)).map((f) => f.field);
    return init.length ? init : firstEnum ? [firstEnum] : [];
  });
  const [ranges, setRanges] = useState<Record<string, string>>({});

  const toggleField = (name: string) =>
    setOpen((o) => (o.includes(name) ? o.filter((x) => x !== name) : [...o, name]));

  const activeIndex = (field: string, value: string) =>
    filters.findIndex((f) => f.field === field && f.value === value);

  const openFacets = open.map((n) => facets.find((f) => f.field === n)).filter(Boolean) as Facet[];

  return (
    <div className="tagpicker">
      {/* ① field select */}
      <div className="fieldbar">
        <span className="mut">Filter fields:</span>
        {facets.map((f) => {
          const on = open.includes(f.field);
          const hasActive = filtered.has(f.field);
          return (
            <button key={f.field} type="button" className={`chip toggle${on ? " on" : ""}`}
              onClick={() => toggleField(f.field)} title={`${f.distinct} distinct`}>
              {f.field} <span className="mut">({f.distinct})</span>{hasActive ? " ●" : ""}
            </button>
          );
        })}
      </div>

      {/* ② tags / range for each chosen field */}
      {openFacets.map((f) => (
        <div key={f.field} className="tag-group">
          <span className="tag-group-name">{f.field}</span>
          {isRange(f) ? (
            <input type="text" className="range-in" defaultValue={ranges[f.field] ?? ""}
              placeholder={rangeHint(f)}
              onKeyDown={(e) => {
                if (e.key === "Enter") {
                  e.preventDefault();
                  const v = e.currentTarget.value.trim();
                  if (v) { onAdd({ field: f.field, value: v }); setRanges((r) => ({ ...r, [f.field]: "" })); e.currentTarget.value = ""; }
                }
              }} />
          ) : (
            <span className="tags">
              {f.values!.map(([v, n]) => {
                const idx = activeIndex(f.field, v);
                const on = idx >= 0;
                return (
                  <button key={v} type="button" className={`kw toggle${on ? " on" : ""}`}
                    onClick={() => (on ? onRemove(idx) : onAdd({ field: f.field, value: v }))}>
                    {v} <span className="mut">· {n}</span>
                  </button>
                );
              })}
            </span>
          )}
        </div>
      ))}

      {/* active filters */}
      {filters.length > 0 && (
        <div className="fieldbar" style={{ marginTop: 4 }}>
          <span className="mut">Active:</span>
          <span className="chips">
            {filters.map((f, i) => (
              <span key={i} className="chip on">
                <b>{f.field}</b> = {f.value}
                <button type="button" className="x" onClick={() => onRemove(i)}>×</button>
              </span>
            ))}
          </span>
        </div>
      )}
    </div>
  );
}
