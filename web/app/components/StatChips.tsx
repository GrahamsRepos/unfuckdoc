import type { Overview } from "~/lib/types";

function Stat({ value, label, cls }: { value: React.ReactNode; label: string; cls?: string }) {
  return <div className={`stat ${cls ?? ""}`}><b>{value}</b><span>{label}</span></div>;
}

export function StatChips({ overview }: { overview: Overview }) {
  const unified = overview.merge_groups.filter((g) => g.unified).length;
  return (
    <div className="stats">
      <Stat value={overview.n_rows.toLocaleString()} label="rows" />
      <Stat value={overview.n_cols} label="columns" />
      <Stat value={unified} label="unified fields" cls={unified ? "ok" : ""} />
      <Stat value={overview.llm_calls} label="LLM calls" cls={overview.llm_calls === 0 ? "ok" : "warn"} />
      <Stat value={overview.coerced} label="cells coerced" />
      <Stat value={overview.quarantine} label="quarantined" cls={overview.quarantine ? "warn" : ""} />
    </div>
  );
}
