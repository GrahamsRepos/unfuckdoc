import { Link, redirect, useRouteLoaderData } from "react-router";
import type { Route } from "./+types/collection.model";
import { api } from "~/lib/api";
import type { CollectionDetail } from "~/lib/types";
import { MergeGraph } from "~/components/MergeGraph";
import { CollectionSchema } from "~/components/CollectionSchema";
import { CustomCanonicals } from "~/components/CustomCanonicals";
import { FileMapping } from "~/components/FileMapping";
import { EnrichmentJoins } from "~/components/EnrichmentJoins";

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
  } else if (intent === "add-enrichment") {
    await api.addEnrichment(params.name, String(form.get("source") ?? ""), String(form.get("join_field") ?? ""));
  } else if (intent === "remove-enrichment") {
    await api.removeEnrichment(params.name, String(form.get("source")));
  }
  return redirect(`${base}/model`);
}

export default function Model({ params }: Route.ComponentProps) {
  const parent = useRouteLoaderData("routes/collection") as { detail: CollectionDetail; samples: string[] };
  const { detail, samples } = parent;
  const base = `/collections/${encodeURIComponent(params.name)}`;
  const conflicts = detail.schema.reduce((n, s) => n + (s.conflicts ?? 0), 0);

  if (detail.files.length === 0) {
    return (
      <section className="card">
        <h3>② Model</h3>
        <p className="hint">No sources yet — <Link to={base} className="to">add a source</Link> first.</p>
      </section>
    );
  }

  return (
    <>
      <section className="card">
        <h3>② Model <span className="mut">— unify columns into canonical fields, then fix anything wrong</span></h3>
        <p className="hint">
          {detail.schema.length} canonical fields from {detail.files.length} sources
          {conflicts > 0 && <> · <span className="no">⚠ {conflicts} value conflict{conflicts === 1 ? "" : "s"} to review</span></>}.
          When it looks right, go to <Link to={`${base}/explore`} className="to">→ Explore</Link>.
        </p>
      </section>

      <MergeGraph detail={detail} />
      <CustomCanonicals detail={detail} />
      <EnrichmentJoins detail={detail} samples={samples} />
      <FileMapping detail={detail} />
      <CollectionSchema detail={detail} />
    </>
  );
}
