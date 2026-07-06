import { useSearchParams } from "react-router";
import type { FieldFilter, Overview, SearchResponse } from "~/lib/types";
import { FieldFilterBar } from "./FieldFilterBar";
import { ResultsTable } from "./ResultsTable";
import { Dsl } from "./format";
import { Pagination } from "./Pagination";

export function SearchPanel({ overview, search }: { overview: Overview; search: SearchResponse | null }) {
  const [params, setParams] = useSearchParams();
  const filters: FieldFilter[] = params.getAll("f").map((s) => {
    const i = s.indexOf(":");
    return { field: s.slice(0, i), value: s.slice(i + 1) };
  });
  const hasText = overview.fuzzy.length > 0;
  const mode = params.get("mode") ?? (hasText ? "semantic" : "keyword");
  const field = params.get("field") ?? overview.fuzzy[0] ?? "";
  const tag = params.get("tag") ?? "";
  const q = params.get("q") ?? "";
  const showAllColumns = params.get("cols") === "all";
  const page = Math.max(1, Number.parseInt(params.get("page") ?? "1", 10) || 1);
  const pageSize = Math.max(1, Number.parseInt(params.get("size") ?? String(search?.page_size ?? 12), 10) || (search?.page_size ?? 12));

  function apply(next: URLSearchParams) {
    setParams(next, { preventScrollReset: true });
  }
  function runSearch(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const fd = new FormData(e.currentTarget);
    const np = new URLSearchParams();
    const qv = String(fd.get("q") ?? "").trim();
    np.set("q", qv);
    np.set("page", "1");
    const existingSize = params.get("size");
    if (existingSize) np.set("size", existingSize);
    np.set("mode", String(fd.get("mode")));
    if (fd.get("field")) np.set("field", String(fd.get("field")));
    const tg = String(fd.get("tag") ?? "");
    if (tg) np.set("tag", tg);
    if (fd.get("cols") === "all") np.set("cols", "all");
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
    const all = np.getAll("f");
    np.delete("f");
    all.forEach((v, i) => i !== idx && np.append("f", v));
    np.set("page", "1");
    apply(np);
  }
  function setPage(nextPage: number) {
    const np = new URLSearchParams(params);
    np.set("page", String(nextPage));
    apply(np);
  }

  const banner = search && (
    <p className="hint">
      {search.count} on this page
      {" "}
      <span className="mut">({search.total} total)</span>
      {tag && <> — tag <span className="kw">{tag}</span></>}
      {filters.map((f) => <span key={f.field + f.value}> · <span className="kw">{f.field}={f.value}</span></span>)}
    </p>
  );

  return (
    <section className="card">
      <h2>Search the fields</h2>
      <form className="searchbar" onSubmit={runSearch}>
        <select name="mode" defaultValue={mode}>
          <option value="semantic">Semantic (vector)</option>
          <option value="keyword">Keyword</option>
        </select>
        <label style={{ display: "inline-flex", alignItems: "center", gap: 6 }} title="show every canonical field in the result table">
          <input type="checkbox" name="cols" value="all" defaultChecked={showAllColumns} />
          <span className="mut">all columns</span>
        </label>
        {hasText && (
          <select name="field" defaultValue={field}>
            {overview.fuzzy.map((f) => <option key={f} value={f}>{f}</option>)}
          </select>
        )}
        <select name="tag" defaultValue={tag}>
          <option value="">— any tag —</option>
          {overview.all_tags.map((t) => <option key={t} value={t}>{t}</option>)}
        </select>
        <input name="q" type="text" defaultValue={q}
          placeholder="e.g. churn risk, crisp citrus… (leave empty to browse)" />
        <button className="btn" type="submit">Search</button>
      </form>

      <FieldFilterBar facets={overview.facets} filters={filters} onAdd={addFilter} onRemove={removeFilter} />

      {search && (
        <div style={{ marginTop: 14 }}>
          <Dsl dsl={search.dsl} index={search.index} />
          {banner}
          {search.error ? (
            <div className="empty">⚠ {search.error}</div>
          ) : search.results.length ? (
            <>
              <ResultsTable columns={search.display_columns} results={search.results} />
              <Pagination page={search.page} pageSize={search.page_size ?? pageSize} total={search.total}
                onPage={setPage} />
            </>
          ) : (
            <div className="empty">no matches</div>
          )}
        </div>
      )}
    </section>
  );
}
