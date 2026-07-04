import { useSearchParams } from "react-router";
import type { CollectionDetail, CollectionSearchResponse, Facet, FieldFilter } from "~/lib/types";
import { FieldFilterBar } from "./FieldFilterBar";
import { Dsl } from "./format";

export function CollectionSearchPanel({ detail, search }:
  { detail: CollectionDetail; search: CollectionSearchResponse | null }) {
  const [params, setParams] = useSearchParams();
  const filters: FieldFilter[] = params.getAll("f").map((s) => {
    const i = s.indexOf(":");
    return { field: s.slice(0, i), value: s.slice(i + 1) };
  });
  const q = params.get("q") ?? "";

  // schema fields -> facet shape (no value lists; numeric/date get range inputs)
  const facets: Facet[] = detail.schema.map((s) => ({
    field: s.field, kind: s.kind, os_type: s.os_type, cardinality: s.cardinality, distinct: s.count,
  }));

  function apply(np: URLSearchParams) { setParams(np, { preventScrollReset: true }); }
  function runSearch(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const fd = new FormData(e.currentTarget);
    const np = new URLSearchParams();
    const qv = String(fd.get("q") ?? "").trim();
    if (qv) np.set("q", qv);
    filters.forEach((f) => np.append("f", `${f.field}:${f.value}`));
    apply(np);
  }
  function addFilter(f: FieldFilter) {
    const np = new URLSearchParams(params);
    const key = `${f.field}:${f.value}`;
    if (!np.getAll("f").includes(key)) np.append("f", key);
    apply(np);
  }
  function removeFilter(idx: number) {
    const np = new URLSearchParams(params);
    const all = np.getAll("f"); np.delete("f");
    all.forEach((v, i) => i !== idx && np.append("f", v));
    apply(np);
  }

  return (
    <section className="card">
      <h2>Search the collection</h2>
      <form className="searchbar" onSubmit={runSearch}>
        <input name="q" type="text" defaultValue={q} placeholder="keyword across all fields…" />
        <button className="btn" type="submit">Search</button>
      </form>
      <FieldFilterBar facets={facets} filters={filters} onAdd={addFilter} onRemove={removeFilter} />

      {search && (
        <div style={{ marginTop: 14 }}>
          <Dsl dsl={search.dsl} index={search.index} />
          {search.error ? <div className="empty">⚠ {search.error}</div> : search.count ? (
            <>
              <p className="hint">{search.count} record(s)</p>
              <div className="scroll">
                <table>
                  <thead><tr>{search.display.map((c) => <th key={c}>{c === "_source_file" ? "source" : c}</th>)}</tr></thead>
                  <tbody>
                    {search.results.map((r, i) => (
                      <tr key={i}>
                        {search.display.map((c) => <td key={c}>{r[c] ? r[c] : <span className="mut">—</span>}</td>)}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          ) : <div className="empty">no matches</div>}
        </div>
      )}
    </section>
  );
}
