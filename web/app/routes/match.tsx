import { Form } from "react-router";
import type { Route } from "./+types/match";
import { api } from "~/lib/api";
import { MatchReport } from "~/components/MatchReport";

export function meta(_: Route.MetaArgs) {
  return [{ title: "unfuckdoc — match" }];
}

export async function loader({ request }: Route.LoaderArgs) {
  const p = new URL(request.url).searchParams;
  const { samples } = await api.samples();
  const a = p.get("a") ?? samples.find((s) => s.includes("crm_contacts")) ?? samples[0] ?? "";
  const b = p.get("b") ?? samples.find((s) => s.includes("sales_leads")) ?? samples[1] ?? "";
  const threshold = Number(p.get("threshold") ?? 0.85);

  let candidates: Awaited<ReturnType<typeof api.matchCandidates>>["keys"] = [];
  let result = null;
  if (a && b && a !== b) {
    candidates = (await api.matchCandidates(a, b)).keys;
    const key = p.get("key") || candidates[0]?.field;
    if (key) result = await api.match({ a, b, key, threshold });
  }
  return { samples, a, b, key: p.get("key") ?? "", threshold, candidates, result };
}

export default function Match({ loaderData }: Route.ComponentProps) {
  const { samples, a, b, key, threshold, candidates, result } = loaderData;
  return (
    <section className="card">
      <h2>Match two datasets</h2>
      <p className="hint">
        Unify the schema, then link the records. Pick two files and a join field — even if the column names
        differ, they share a canonical key. Matching is fuzzy, so near-duplicate values still link.
      </p>
      <Form method="get" className="searchbar">
        <select name="a" defaultValue={a}>{samples.map((s) => <option key={s} value={s}>{s}</option>)}</select>
        <span className="mut">match against</span>
        <select name="b" defaultValue={b}>{samples.map((s) => <option key={s} value={s}>{s}</option>)}</select>
        <select name="key" defaultValue={key || candidates[0]?.field || ""}>
          {candidates.map((k) => <option key={k.field} value={k.field}>{k.field} (uniq {k.uniqueness})</option>)}
        </select>
        <label className="mut" style={{ display: "flex", alignItems: "center", gap: 6 }}>
          threshold
          <input name="threshold" type="number" min="0.5" max="1" step="0.01" defaultValue={threshold}
            style={{ width: 80 }} />
        </label>
        <button className="btn" type="submit">Match</button>
      </Form>

      {a === b && <div className="empty">pick two different datasets</div>}
      {result && <MatchReport result={result} a={a} b={b} />}
    </section>
  );
}
