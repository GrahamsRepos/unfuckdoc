import { Form, useActionData } from "react-router";
import type { CollectionDetail } from "~/lib/types";

/** Row-level field transforms (safe expression DSL) — mutate/clean/derive values BEFORE processing,
 *  OpenRefine-style. The output becomes a normal column (classified, canonicalized, filterable). No
 *  arbitrary code: only a whitelist of string/number/date functions. */
export function FieldTransforms({ detail }: { detail: CollectionDetail }) {
  const action = useActionData() as { error?: string } | undefined;
  const transforms = detail.transforms ?? [];
  return (
    <section className="card">
      <h2>Field transforms <span className="mut">— clean / derive values before processing</span></h2>
      <p className="hint">
        Compute an output field from a safe expression over a row's columns (like OpenRefine, but with a
        fixed function whitelist — no code runs). The output is processed like any other column.
        Functions: <code>upper lower trim strip replace regex_replace regex_extract split concat digits
        substring default coalesce to_number round add sub mul div if eq gt lt contains and or not</code>.
      </p>
      {action?.error && <div className="empty">⚠ {action.error}</div>}

      <Form method="post" className="searchbar" style={{ gap: 8, flexWrap: "wrap" }}>
        <input type="hidden" name="intent" value="set-transform" />
        <input name="field" type="text" placeholder="output field (e.g. clean_price)" required
          pattern="[A-Za-z0-9 _-]+" style={{ width: "16ch" }} />
        <span className="mut">=</span>
        <input name="expr" type="text" required style={{ flex: 1, minWidth: 280, fontFamily: "ui-monospace, Menlo, monospace" }}
          placeholder={`to_number(strip(raw_price, "$,"))`} />
        <button className="btn" type="submit">+ transform</button>
      </Form>

      <div className="merge" style={{ marginTop: 12 }}>
        {transforms.length === 0 && <span className="mut">No transforms yet.</span>}
        {transforms.map((t) => (
          <div key={t.field} className="mgroup">
            <span className="to">{t.field}</span>
            <span className="mut">=</span>
            <code style={{ flex: 1 }}>{t.expr}</code>
            <Form method="post" style={{ marginLeft: "auto" }}>
              <input type="hidden" name="intent" value="set-transform" />
              <input type="hidden" name="field" value={t.field} />
              <input type="hidden" name="expr" value="" />
              <button className="btn ghost" type="submit" title="remove (blank expression clears it)">remove</button>
            </Form>
          </div>
        ))}
      </div>
    </section>
  );
}
