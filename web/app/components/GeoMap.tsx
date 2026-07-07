import { useEffect, useRef } from "react";
import { useSearchParams } from "react-router";
import type { GeoPoint } from "~/lib/types";
import "leaflet/dist/leaflet.css";
import "leaflet-draw/dist/leaflet.draw.css";

/** Client-only Leaflet map: plots the collection's geo points and lets you draw a rectangle or
 *  polygon to filter by area. The drawn shape is encoded into the `geo`/`geofield` URL params, which
 *  the Explore loader turns into a GeoFilter — so the map and the results/segments stay in sync. */
export function GeoMap({ points, field }: { points: GeoPoint[]; field: string }) {
  const ref = useRef<HTMLDivElement>(null);
  const mapRef = useRef<any>(null);
  const markersRef = useRef<any>(null);
  const [params, setParams] = useSearchParams();
  // keep the latest params in a ref so the (once-only) draw handler always reads current state
  const paramsRef = useRef(params);
  paramsRef.current = params;

  // init the map once
  useEffect(() => {
    let cancelled = false;
    (async () => {
      const L = (await import("leaflet")).default;
      (window as any).L = L;                 // leaflet-draw (UMD) expects the global L
      await import("leaflet-draw");
      if (cancelled || !ref.current || mapRef.current) return;

      const map = L.map(ref.current).setView([20, 0], 2);
      mapRef.current = map;
      L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
        attribution: "© OpenStreetMap contributors", maxZoom: 19,
      }).addTo(map);

      markersRef.current = L.featureGroup().addTo(map);

      const drawn = new L.FeatureGroup();
      map.addLayer(drawn);
      const DrawCtl = (L as any).Control.Draw;
      map.addControl(new DrawCtl({
        draw: { rectangle: {}, polygon: {}, marker: false, circle: false, circlemarker: false, polyline: false },
        edit: { featureGroup: drawn, edit: false },
      }));
      map.on((L as any).Draw.Event.CREATED, (e: any) => {
        drawn.clearLayers();
        drawn.addLayer(e.layer);
        const np = new URLSearchParams(paramsRef.current);
        np.set("geofield", field);
        np.set("page", "1");
        if (e.layerType === "rectangle") {
          const b = e.layer.getBounds();
          np.set("geo", `bbox:${b.getSouth()},${b.getWest()},${b.getNorth()},${b.getEast()}`);
        } else if (e.layerType === "polygon") {
          const ring = e.layer.getLatLngs()[0].map((ll: any) => `${ll.lat},${ll.lng}`).join(";");
          np.set("geo", `poly:${ring}`);
        }
        setParams(np, { preventScrollReset: true });
      });
    })();
    return () => {
      cancelled = true;
      if (mapRef.current) { mapRef.current.remove(); mapRef.current = null; }
    };
  }, [field, setParams]);

  // re-plot whenever the (filtered) points change
  useEffect(() => {
    (async () => {
      const L = (await import("leaflet")).default;
      const group = markersRef.current;
      if (!group) return;
      group.clearLayers();
      points.forEach((p) =>
        L.circleMarker([p.lat, p.lng], { radius: 5, color: "#4c9be8", weight: 1, fillColor: "#4c9be8", fillOpacity: 0.7 })
          .bindTooltip(p.label).addTo(group));
      if (points.length && mapRef.current) mapRef.current.fitBounds(group.getBounds().pad(0.2));
    })();
  }, [points]);

  function clearArea() {
    const np = new URLSearchParams(params);
    np.delete("geo"); np.delete("geofield");
    setParams(np, { preventScrollReset: true });
  }

  return (
    <section className="card">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
        <h3>Map <span className="mut">— {points.length} located; draw a ▭ or polygon to filter by area</span></h3>
        {params.get("geo") && <button type="button" className="btn ghost" onClick={clearArea}>✕ clear area</button>}
      </div>
      <div ref={ref} style={{ height: 440, borderRadius: 8, overflow: "hidden", marginTop: 10 }} />
    </section>
  );
}
