import { Link, redirect, useRouteLoaderData } from "react-router";
import type { Route } from "./+types/collection.enrich";
import { api } from "~/lib/api";
import type { CollectionDetail } from "~/lib/types";
import { EnrichmentJoins } from "~/components/EnrichmentJoins";

export async function action({ request, params }: Route.ActionArgs) {
  const form = await request.formData();
  const intent = String(form.get("intent") ?? "");
  const base = `/collections/${encodeURIComponent(params.name)}`;

  if (intent === "add-enrichment") {
    const ref = String(form.get("ref") ?? "");
    const joinField = String(form.get("join_field") ?? "");
    if (ref.startsWith("collection:")) await api.addEnrichment(params.name, { collection: ref.slice("collection:".length) }, joinField);
    else if (ref) await api.addEnrichment(params.name, { source: ref }, joinField);
  } else if (intent === "remove-enrichment") {
    await api.removeEnrichment(params.name, String(form.get("source")));
  }
  return redirect(`${base}/enrich`);
}

export default function Enrich({ params }: Route.ComponentProps) {
  const parent = useRouteLoaderData("routes/collection") as
    { detail: CollectionDetail; samples: string[]; otherCollections: string[] };
  const { detail, samples, otherCollections } = parent;
  const base = `/collections/${encodeURIComponent(params.name)}`;

  if (detail.files.length === 0) {
    return (
      <section className="card">
        <h3>③ Enrich</h3>
        <p className="hint">No set to enrich yet — <Link to={base} className="to">add a source</Link> first.</p>
      </section>
    );
  }

  return (
    <>
      <section className="card">
        <h3>③ Enrich <span className="mut">— join other sets onto your unified set (optional)</span></h3>
        <p className="hint">
          Chain another collection or a reference file onto your set by a shared field to gain its
          fields (e.g. join a city→geo set on <b>city</b> to attach <b>coordinates</b>). Each entity keeps
          its identity and simply gains the fields — then <Link to={`${base}/explore`} className="to">→ Explore</Link>.
        </p>
      </section>

      <EnrichmentJoins detail={detail} samples={samples} collections={otherCollections} />
    </>
  );
}
