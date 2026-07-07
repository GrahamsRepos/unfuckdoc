import { Link, redirect, useRouteLoaderData } from "react-router";
import type { Route } from "./+types/collection.canonical";
import { api } from "~/lib/api";
import type { CollectionDetail } from "~/lib/types";
import { MergeGraph } from "~/components/MergeGraph";
import { CollectionSchema } from "~/components/CollectionSchema";
import { CustomCanonicals } from "~/components/CustomCanonicals";
import { FieldTransforms } from "~/components/FieldTransforms";
import { ExtractedAttributes } from "~/components/ExtractedAttributes";
import { FileMapping } from "~/components/FileMapping";

export async function action({ request, params }: Route.ActionArgs) {
  const form = await request.formData();
  const intent = String(form.get("intent") ?? "");
  const base = `/collections/${encodeURIComponent(params.name)}`;

  if (intent === "set-mapping") {
    await api.setMapping(params.name, String(form.get("column")), String(form.get("canonical") ?? ""));
  } else if (intent === "add-canonical") {
    await api.addCanonical(params.name, String(form.get("canon") ?? ""), String(form.get("type") ?? "keyword"), form.get("array") === "on");
  } else if (intent === "delete-canonical") {
    await api.deleteCanonical(params.name, String(form.get("canon")));
  } else if (intent === "add-extraction") {
    const values = String(form.get("values") ?? "").split(",").map((v) => v.trim()).filter(Boolean);
    await api.addExtraction(params.name, String(form.get("attr") ?? ""), String(form.get("type") ?? "keyword"), values);
  } else if (intent === "remove-extraction") {
    await api.removeExtraction(params.name, String(form.get("attr")));
  } else if (intent === "set-transform") {
    const res = await api.setTransform(params.name, String(form.get("field") ?? ""), String(form.get("expr") ?? ""));
    if (res.error) return { error: res.error };   // surface expression errors, don't redirect
  }
  return redirect(`${base}/canonical`);
}

export default function Canonical({ params }: Route.ComponentProps) {
  const parent = useRouteLoaderData("routes/collection") as { detail: CollectionDetail };
  const { detail } = parent;
  const base = `/collections/${encodeURIComponent(params.name)}`;
  const conflicts = detail.schema.reduce((n, s) => n + (s.conflicts ?? 0), 0);

  if (detail.files.length === 0) {
    return (
      <section className="card">
        <h3>② Canonical</h3>
        <p className="hint">No sources yet — <Link to={base} className="to">add a source</Link> first.</p>
      </section>
    );
  }

  return (
    <>
      <section className="card">
        <h3>② Canonical <span className="mut">— standardise columns into your unified set of fields</span></h3>
        <p className="hint">
          Your merged data is standardised to {detail.schema.length} canonical fields across {detail.files.length} sources
          {conflicts > 0 && <> · <span className="no">⚠ {conflicts} value conflict{conflicts === 1 ? "" : "s"} to review</span></>}.
          Fix any wrong mappings here, then <Link to={`${base}/enrich`} className="to">→ Enrich</Link> to join other sets onto it.
        </p>
      </section>

      <MergeGraph detail={detail} />
      <FieldTransforms detail={detail} />
      <CustomCanonicals detail={detail} />
      <ExtractedAttributes detail={detail} />
      <FileMapping detail={detail} />
      <CollectionSchema detail={detail} />
    </>
  );
}
