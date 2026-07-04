import { redirect } from "react-router";
import type { Route } from "./+types/home";
import { api } from "~/lib/api";
import { UploadBar } from "~/components/UploadBar";
import { StatChips } from "~/components/StatChips";
import { Donut } from "~/components/Donut";
import { FillRates } from "~/components/FillRates";
import { MergeGroups } from "~/components/MergeGroups";
import { Registry } from "~/components/Registry";
import { Consolidated } from "~/components/Consolidated";
import { OsStructure } from "~/components/OsStructure";
import { ClassificationTable } from "~/components/ClassificationTable";
import { TagBars } from "~/components/TagBars";
import { SearchPanel } from "~/components/SearchPanel";

export function meta(_: Route.MetaArgs) {
  return [{ title: "unfuckdoc — explore" }];
}

export async function loader({ request }: Route.LoaderArgs) {
  const url = new URL(request.url);
  const p = url.searchParams;
  const [overview, samples] = await Promise.all([api.overview(), api.samples()]);

  // Search state lives in the URL, so results are server-rendered.
  const hasQuery = p.get("q") || p.get("tag") || p.getAll("f").length > 0;
  const filters = p.getAll("f").map((s) => {
    const i = s.indexOf(":");
    return { field: s.slice(0, i), value: s.slice(i + 1) };
  });
  const search = overview.loaded && hasQuery
    ? await api.search({
        q: p.get("q") ?? "",
        mode: p.get("mode") ?? "semantic",
        field: p.get("field") ?? (overview.fuzzy[0] ?? undefined),
        tag: p.get("tag") ?? "",
        filters,
        size: 12,
      })
    : null;

  return { overview, samples: samples.samples, search };
}

export async function action({ request }: Route.ActionArgs) {
  const form = await request.formData();
  const intent = form.get("intent");
  if (intent === "sample") {
    await api.loadSample(String(form.get("name")));
  } else if (intent === "upload") {
    const file = form.get("file");
    if (file instanceof File && file.size > 0) {
      const fd = new FormData();
      fd.append("file", file);
      await api.upload(fd);
    }
  }
  return redirect("/");
}

export default function Home({ loaderData }: Route.ComponentProps) {
  const { overview, samples, search } = loaderData;

  return (
    <>
      <UploadBar samples={samples} filename={overview.loaded ? overview.filename : undefined}
        opensearch={overview.opensearch} />

      {!overview.loaded ? (
        <div className="card empty">Upload a CSV or load a sample to begin.</div>
      ) : (
        <>
          <section className="card">
            <h2>{overview.filename}</h2>
            <p className="hint">
              Deterministic classification unless a column is ambiguous — only then does it escalate to an LLM.
            </p>
            <StatChips overview={overview} />
          </section>

          <div className="row">
            <Donut kindCounts={overview.kind_counts} />
            <FillRates columns={overview.columns} />
          </div>

          <MergeGroups groups={overview.merge_groups} />
          <Registry entries={overview.registry} />
          <Consolidated unified={overview.unified} />
          <OsStructure mapping={overview.mapping} unified={overview.unified}
            opensearch={overview.opensearch} embedder={overview.embedder} vecDim={overview.vec_dim} />
          <ClassificationTable columns={overview.columns} />
          <TagBars tags={overview.tags} />
          <SearchPanel overview={overview} search={search} />
        </>
      )}
    </>
  );
}
