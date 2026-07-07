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
        Compute an output field from an expression over a row's columns (like OpenRefine, but with a
        fixed function whitelist — no code runs). A bare word like <code>city</code> means "that column's
        value"; wrap text in double quotes; functions nest. The output is processed like any other column.
      </p>

      <details className="tx-help">
        <summary>How to write expressions — functions &amp; examples</summary>
        <p className="mut" style={{ margin: "6px 0" }}>
          <b>Values:</b> <code>city</code> = the value of the <i>city</i> column · <code>"hello"</code> = literal
          text · <code>42</code> = a number. <b>Nesting:</b> a function's argument can be another function,
          e.g. <code>upper(trim(city))</code> first trims, then upper-cases.
        </p>
        <div className="scroll">
          <table>
            <thead><tr><th>Function</th><th>What it does</th><th>Example → result</th></tr></thead>
            <tbody>
              <tr><td colSpan={3} className="tx-grp">Text</td></tr>
              <tr><td><code>upper(s)</code> / <code>lower(s)</code></td><td>change case</td><td><code>upper(city)</code> → <code>LONDON</code></td></tr>
              <tr><td><code>trim(s)</code></td><td>remove surrounding spaces</td><td><code>trim(" hi ")</code> → <code>hi</code></td></tr>
              <tr><td><code>strip(s, chars)</code></td><td>trim the given characters off both ends</td><td><code>strip("$1,200","$,")</code> → <code>1,200</code></td></tr>
              <tr><td><code>replace(s, find, with)</code></td><td>replace all occurrences</td><td><code>replace(phone,"-","")</code></td></tr>
              <tr><td><code>regex_replace(s, pattern, with)</code></td><td>regex substitution</td><td><code>regex_replace(x,"[^0-9]","")</code></td></tr>
              <tr><td><code>regex_extract(s, pattern)</code></td><td>first regex match</td><td><code>regex_extract(ref,"[A-Z]+")</code> → <code>REF</code></td></tr>
              <tr><td><code>split(s, sep, i)</code></td><td>split, take part <i>i</i> (0-based)</td><td><code>split(email,"@",1)</code> → domain</td></tr>
              <tr><td><code>concat(a, b, …)</code></td><td>join values together</td><td><code>concat(first," ",last)</code></td></tr>
              <tr><td><code>digits(s)</code></td><td>keep only digits</td><td><code>digits("+1 (555) 12")</code> → <code>155512</code></td></tr>
              <tr><td><code>substring(s, from, to)</code></td><td>slice by index</td><td><code>substring(code,0,3)</code></td></tr>
              <tr><td><code>default(s, fallback)</code></td><td>use fallback if blank</td><td><code>default(region,"?")</code></td></tr>
              <tr><td><code>coalesce(a, b, …)</code></td><td>first non-blank value</td><td><code>coalesce(mobile,phone)</code></td></tr>
              <tr><td colSpan={3} className="tx-grp">Numbers</td></tr>
              <tr><td><code>to_number(s)</code></td><td>parse a number (ignores $ , spaces)</td><td><code>to_number("$1,200")</code> → <code>1200</code></td></tr>
              <tr><td><code>round(n, places)</code></td><td>round</td><td><code>round(3.14159,2)</code> → <code>3.14</code></td></tr>
              <tr><td><code>add/sub/mul/div(a, b)</code></td><td>arithmetic</td><td><code>mul(price,qty)</code></td></tr>
              <tr><td colSpan={3} className="tx-grp">Logic (return true / false)</td></tr>
              <tr><td><code>if(cond, a, b)</code></td><td>a if cond is true, else b</td><td><code>if(gt(amount,"1000"),"big","small")</code></td></tr>
              <tr><td><code>eq / gt / lt / gte / lte(a, b)</code></td><td>compare (numeric if both numbers)</td><td><code>gt(age,"18")</code> → <code>true</code></td></tr>
              <tr><td><code>contains(s, sub)</code></td><td>substring test</td><td><code>contains(city,"ond")</code></td></tr>
              <tr><td><code>and / or / not(…)</code></td><td>combine conditions</td><td><code>and(gt(a,"1"),lt(a,"9"))</code></td></tr>
            </tbody>
          </table>
        </div>
        <p className="mut" style={{ margin: "6px 0" }}>
          <b>Full example:</b> <code>clean_price = to_number(strip(raw_price, "$,"))</code> · &nbsp;
          <code>domain = lower(split(email, "@", 1))</code> · &nbsp;
          <code>label = concat(upper(trim(city)), "-", digits(id))</code>
        </p>
      </details>
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
