import { Form, Link, useLocation } from "react-router";
import type { CollectionDetail } from "~/lib/types";

/** Named saved filter views over the merged collection ("cold leads", "customers · Cape Town"). */
export function Segments({ detail, activeFilters }: { detail: CollectionDetail; activeFilters: { field: string; value: string }[] }) {
  const loc = useLocation();
  const base = `/collections/${encodeURIComponent(detail.name)}/explore`;
  const toQuery = (filters: { field: string; value: string }[]) =>
    "?" + filters.map((f) => `f=${encodeURIComponent(`${f.field}:${f.value}`)}`).join("&");
  const activeKey = JSON.stringify(activeFilters);

  return (
    <section className="card">
      <h3>Segments <span className="mut">— saved filtered views</span></h3>
      <div className="chips" style={{ marginTop: 10 }}>
        <Link to={base}
          className="chip" style={{ opacity: activeFilters.length === 0 ? 1 : 0.6 }}>
          all · {detail.n_records}
        </Link>
        {detail.segments.map((s) => {
          const active = JSON.stringify(s.filters.map((f) => ({ field: f.field, value: f.value }))) === activeKey;
          return (
            <span key={s.name} className="chip" style={{ display: "inline-flex", gap: 6, alignItems: "center", opacity: active ? 1 : 0.75 }}>
              <Link to={`${base}${toQuery(s.filters)}`} className="to">
                {s.name} · {s.count}
              </Link>
              <Form method="post" style={{ display: "inline" }}
                onSubmit={(e) => { if (!confirm(`Delete segment "${s.name}"?`)) e.preventDefault(); }}>
                <input type="hidden" name="intent" value="delete-segment" />
                <input type="hidden" name="seg" value={s.name} />
                <button className="x" type="submit" title="delete segment">×</button>
              </Form>
            </span>
          );
        })}
      </div>

      {activeFilters.length > 0 && (
        <Form method="post" className="searchbar" style={{ marginTop: 12, gap: 8 }}>
          <input type="hidden" name="intent" value="save-segment" />
          <input type="hidden" name="filters" value={JSON.stringify(activeFilters)} />
          <input type="hidden" name="next" value={loc.search} />
          <span className="mut">save current filter as</span>
          <input name="seg" type="text" placeholder="segment name (e.g. cold leads)" required />
          <button className="btn ghost" type="submit">★ save segment</button>
        </Form>
      )}
      {activeFilters.length === 0 && detail.segments.length === 0 && (
        <p className="hint" style={{ marginTop: 10 }}>
          Filter the collection below, then save that filter here as a named segment.
        </p>
      )}
    </section>
  );
}
