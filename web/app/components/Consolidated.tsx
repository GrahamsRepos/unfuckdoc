import type { UnifiedField } from "~/lib/types";
import { KindBadge } from "./format";

export function Consolidated({ unified }: { unified: UnifiedField[] }) {
  if (!unified?.length) return null;
  return (
    <section className="card">
      <h2>Consolidated fields — scalar vs array</h2>
      <p className="hint">
        Columns sharing a canonical key are merged. Fill-pattern decides the shape: exclusive fill → scalar
        (survivorship); concurrent distinct values → array (positional <code>x_1/x_2</code> → bare list;
        semantic <code>x_home/x_work</code> → <code>{"{type, value}"}</code>).
      </p>
      <div className="scroll">
        <table>
          <thead><tr><th>Canonical field</th><th>Shape</th><th>From columns</th></tr></thead>
          <tbody>
            {unified.map((u) => (
              <tr key={u.canonical}>
                <td><span className="canon">{u.canonical}</span></td>
                <td>
                  {u.cardinality === "array"
                    ? <KindBadge kind="enum" label={`array · ${u.style}`} />
                    : <KindBadge kind="identifier" label={`scalar${u.sources.length > 1 ? " · coalesced" : ""}`} />}
                </td>
                <td>
                  {u.sources.map((c, i) => {
                    const lab = u.labels[i];
                    return (
                      <span key={c}>
                        {i > 0 && <span className="arrow"> · </span>}
                        {c}
                        {u.cardinality === "array" && u.style === "semantic" && lab
                          ? <span className="mut"> →{lab}</span> : null}
                      </span>
                    );
                  })}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
