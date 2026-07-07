import { useSearchParams } from "react-router";
import type { CollectionDetail, CollectionSearchResponse, Facet, FieldFilter } from "~/lib/types";
import { TagPicker } from "./TagPicker";
import { Dsl } from "./format";
import { Pagination } from "./Pagination";

export function CollectionSearchPanel({ detail, search }:
  { detail: CollectionDetail; search: CollectionSearchResponse | null }) {
  const [params, setParams] = useSearchParams();
  const filters: FieldFilter[] = params.getAll("f").map((s) => {
    const i = s.indexOf(":");
    return { field: s.slice(0, i), value: s.slice(i + 1) };
  });
  const q = params.get("q") ?? "";
  const mode = params.get("mode") ?? "keyword";
  const tag = params.get("tag") ?? "";
  const sourceFiles = params.getAll("source_file");
  const page = Math.max(1, Number.parseInt(params.get("page") ?? "1", 10) || 1);
  const pageSize = Math.max(1, Number.parseInt(params.get("size") ?? String(search?.page_size ?? 30), 10) || (search?.page_size ?? 30));

  // schema fields -> facet shape; low-cardinality keyword fields carry enumerable values (dropdown),
  // numeric/date/high-card fall back to range/text inputs.
  const facets: Facet[] = detail.schema.map((s) => ({
    field: s.field, kind: s.kind, os_type: s.os_type, cardinality: s.cardinality, distinct: s.count,
    values: s.values,
  }));

  function apply(np: URLSearchParams) { setParams(np, { preventScrollReset: true }); }
  function runSearch(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const fd = new FormData(e.currentTarget);
    const np = new URLSearchParams();
    const qv = String(fd.get("q") ?? "").trim();
    np.set("q", qv);
    np.set("page", "1");
    const md = String(fd.get("mode") ?? "keyword");
    if (md === "semantic") np.set("mode", "semantic");
    const existingSize = params.get("size");
    if (existingSize) np.set("size", existingSize);
    const tg = String(fd.get("tag") ?? "").trim();
    if (tg) np.set("tag", tg);
    const selected = fd.getAll("source_file").map((v) => String(v).trim()).filter(Boolean);
    selected.forEach((sf) => np.append("source_file", sf));
    filters.forEach((f) => np.append("f", `${f.field}:${f.value}`));
    apply(np);
  }
  function addFilter(f: FieldFilter) {
    const np = new URLSearchParams(params);
    const key = `${f.field}:${f.value}`;
    if (!np.getAll("f").includes(key)) np.append("f", key);
    np.set("page", "1");
    apply(np);
  }
  function removeFilter(idx: number) {
    const np = new URLSearchParams(params);
    const all = np.getAll("f"); np.delete("f");
    all.forEach((v, i) => i !== idx && np.append("f", v));
    np.set("page", "1");
    apply(np);
  }
  function setPage(nextPage: number) {
    const np = new URLSearchParams(params);
    np.set("page", String(nextPage));
    apply(np);
  }
  function clearAll() { apply(new URLSearchParams()); }
  const hasQuery = Boolean(q || tag || filters.length || sourceFiles.length || params.get("page"));

  return (
    <section className="card">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
        <h2>Search the collection</h2>
        {hasQuery && <button type="button" className="btn ghost" onClick={clearAll}>✕ clear all</button>}
      </div>
      <form className="searchbar" onSubmit={runSearch}>
        <select name="tag" defaultValue={tag}>
          <option value="">— any tag —</option>
          {detail.tags.map((t) => <option key={t.tag} value={t.tag}>{t.tag}</option>)}
        </select>
        <select
          key={sourceFiles.join("\u0000")}
          name="source_file"
          multiple
          size={Math.min(5, Math.max(2, detail.files.length))}
          defaultValue={sourceFiles}
        >
          {detail.files.map((f) => <option key={f.name} value={f.name}>{f.name}</option>)}
        </select>
        <input name="q" type="text" defaultValue={q}
          placeholder={mode === "semantic" ? "describe what you're looking for…" : "keyword across all fields…"} />
        {detail.semantic_search && (
          <select name="mode" defaultValue={mode} title="keyword = exact terms; semantic = meaning (vector) over text fields">
            <option value="keyword">keyword</option>
            <option value="semantic">semantic</option>
          </select>
        )}
        <button className="btn" type="submit">Search</button>
      </form>
      <TagPicker facets={facets} filters={filters} onAdd={addFilter} onRemove={removeFilter} />

      {search && (
        <div style={{ marginTop: 14 }}>
          <Dsl dsl={search.dsl} index={search.index} />
          {search.error ? <div className="empty">⚠ {search.error}</div> : search.count ? (
            <>
              <p className="hint">{search.count} record(s) on this page <span className="mut">({search.total} total)</span></p>
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
              <Pagination page={search.page} pageSize={search.page_size ?? pageSize} total={search.total} onPage={setPage} />
            </>
          ) : <div className="empty">no matches</div>}
        </div>
      )}
    </section>
  );
}
