import { Link, redirect, useRouteLoaderData } from "react-router";
import type { Route } from "./+types/collection.explore";
import { api } from "~/lib/api";
import type { CollectionDetail } from "~/lib/types";
import { Segments } from "~/components/Segments";
import { CollectionSearchPanel } from "~/components/CollectionSearchPanel";
import { GeoMap } from "~/components/GeoMap";
import type { GeoFilter } from "~/lib/types";

function parseFilters(p: URLSearchParams) {
  return p.getAll("f").map((s) => {
    const i = s.indexOf(":");
    return { field: s.slice(0, i), value: s.slice(i + 1) };
  });
}

function parseGeo(p: URLSearchParams, field: string | undefined): GeoFilter | undefined {
  const g = p.get("geo");
  if (!g || !field) return undefined;
  if (g.startsWith("bbox:")) {
    const n = g.slice(5).split(",").map(Number);
    if (n.length === 4 && n.every((x) => !Number.isNaN(x))) return { field, bbox: n };
  }
  if (g.startsWith("poly:")) {
    const poly = g.slice(5).split(";").map((pt) => pt.split(",").map(Number)).filter((a) => a.length === 2 && a.every((x) => !Number.isNaN(x)));
    if (poly.length >= 3) return { field, polygon: poly };
  }
  return undefined;
}

export async function loader({ params, request }: Route.LoaderArgs) {
  const p = new URL(request.url).searchParams;
  const detail = await api.collection(params.name);
  const filters = parseFilters(p);
  const sourceFiles = p.getAll("source_file");
  const page = Math.max(1, Number.parseInt(p.get("page") ?? "1", 10) || 1);
  const size = Math.max(1, Number.parseInt(p.get("size") ?? "30", 10) || 30);
  const geoField = detail.schema.find((s) => s.os_type === "geo_point")?.field;
  const geo = parseGeo(p, geoField);
  // empty search is match-any: browse all, narrowed by any tag/segment/filter/area
  const search = detail.n_records > 0
    ? await api.searchCollection(params.name, {
      q: p.get("q") ?? "", tag: p.get("tag") ?? "", source_files: sourceFiles, filters, size, page, geo,
    })
    : null;
  const geoData = geoField ? await api.geoPoints(params.name, geoField) : null;
  return { search, filters, geoField, points: geoData?.points ?? [] };
}

export async function action({ request, params }: Route.ActionArgs) {
  const form = await request.formData();
  const intent = String(form.get("intent") ?? "");
  const base = `/collections/${encodeURIComponent(params.name)}/explore`;
  if (intent === "save-segment") {
    const seg = String(form.get("seg") ?? "").trim();
    const filters = JSON.parse(String(form.get("filters") ?? "[]"));
    if (seg) await api.putSegment(params.name, seg, filters);
    return redirect(base + String(form.get("next") ?? ""));
  }
  if (intent === "delete-segment") {
    await api.deleteSegment(params.name, String(form.get("seg")));
  }
  return redirect(base);
}

export default function Explore({ loaderData, params }: Route.ComponentProps) {
  const { search, filters, geoField, points } = loaderData;
  const parent = useRouteLoaderData("routes/collection") as { detail: CollectionDetail };
  const { detail } = parent;
  const base = `/collections/${encodeURIComponent(params.name)}`;

  if (detail.n_records === 0) {
    return (
      <section className="card">
        <h3>③ Explore</h3>
        <p className="hint">Nothing to query yet — <Link to={base} className="to">add a source</Link> to get started.</p>
      </section>
    );
  }

  return (
    <>
      <Segments detail={detail} activeFilters={filters} />
      {detail.tags.length > 0 && (
        <section className="card">
          <h3>Tags <span className="mut">— extracted across all sources; click to filter</span></h3>
          <div className="chips" style={{ marginTop: 10 }}>
            {detail.tags.slice(0, 30).map((t) => (
              <Link key={t.tag} to={`${base}/explore?tag=${encodeURIComponent(t.tag)}`} className="kw">
                {t.tag} · {t.count}
              </Link>
            ))}
          </div>
        </section>
      )}
      {geoField && points.length > 0 && <GeoMap points={points} field={geoField} />}
      <CollectionSearchPanel detail={detail} search={search} />
    </>
  );
}
