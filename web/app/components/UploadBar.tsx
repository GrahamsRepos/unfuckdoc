import { useRef } from "react";
import { useSubmit } from "react-router";
import type { OsStatus } from "~/lib/types";

export function UploadBar({ samples, filename, opensearch }:
  { samples: string[]; filename?: string; opensearch?: OsStatus }) {
  const submit = useSubmit();
  const fileRef = useRef<HTMLInputElement>(null);

  return (
    <section className="card">
      <div className="searchbar">
        <label className="file">
          📄 Choose CSV
          <input ref={fileRef} type="file" accept=".csv"
            onChange={(e) => {
              if (e.currentTarget.files?.length) {
                const fd = new FormData();
                fd.append("intent", "upload");
                fd.append("file", e.currentTarget.files[0]);
                submit(fd, { method: "post", encType: "multipart/form-data" });
              }
            }} />
        </label>
        <span className="mut">or a bundled sample:</span>
        {/* GET form so choosing a sample posts the load action */}
        <form method="post" onSubmit={(e) => {
          e.preventDefault();
          const fd = new FormData(e.currentTarget);
          fd.set("intent", "sample");
          submit(fd, { method: "post" });
        }} className="searchbar" style={{ gap: 8 }}>
          <select name="name" defaultValue="">
            <option value="" disabled>— sample —</option>
            {samples.map((s) => <option key={s} value={s}>{s}</option>)}
          </select>
          <button className="btn ghost" type="submit">Load</button>
        </form>
        {filename && <span className="mut">loaded: <b style={{ color: "var(--ink)" }}>{filename}</b></span>}
      </div>
      {opensearch && <OsLine os={opensearch} />}
    </section>
  );
}

function OsLine({ os }: { os: OsStatus }) {
  const map: Record<string, [string, string]> = {
    indexed: ["var(--good)", `indexed ${os.count} docs into OpenSearch index `],
    unavailable: ["var(--mut)", "OpenSearch not running — search still works in-memory. "],
    error: ["var(--warn)", "OpenSearch error (search still works in-memory): "],
    "no-client": ["var(--mut)", "opensearch-py absent — in-memory only. "],
    unknown: ["var(--mut)", ""],
  };
  const [color, text] = map[os.status] ?? ["var(--mut)", os.status];
  return (
    <div style={{ fontSize: 12.5, marginTop: 8 }}>
      <span style={{ display: "inline-block", width: 8, height: 8, borderRadius: "50%", background: color, marginRight: 6 }} />
      {text}{os.index && <code>{os.index}</code>}{os.detail && <span className="mut"> ({os.detail})</span>}
    </div>
  );
}
