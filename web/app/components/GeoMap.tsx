import { useEffect, useRef, useState } from "react";
import { useSearchParams } from "react-router";
import type { GeoPoint } from "~/lib/types";
import "leaflet/dist/leaflet.css";

/** Client-only Leaflet map: plots the collection's located entities and lets you drag a rectangle to
 *  filter by area. The box is encoded into `geo`/`geofield` URL params (bbox:S,W,N,E), which the
 *  Explore loader turns into a GeoFilter — so map, results, and segments stay in sync. Native mouse
 *  drag is used (no leaflet-draw) for reliability. */
export function GeoMap({ points, field }: { points: GeoPoint[]; field: string }) {
  const ref = useRef<HTMLDivElement>(null);
  const mapRef = useRef<any>(null);
  const markersRef = useRef<any>(null);
  const drawState = useRef<{ armed: boolean; start: any; rect: any }>({ armed: false, start: null, rect: null });
  const [params, setParams] = useSearchParams();
  const paramsRef = useRef(params);
  paramsRef.current = params;
  const [drawing, setDrawing] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      const L = (await import("leaflet")).default;
      if (cancelled || !ref.current || mapRef.current) return;

      const map = L.map(ref.current).setView([20, 0], 2);
      mapRef.current = map;
      L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
        attribution: "© OpenStreetMap contributors", maxZoom: 19,
      }).addTo(map);
      markersRef.current = L.featureGroup().addTo(map);

      map.on("mousedown", (e: any) => {
        const s = drawState.current;
        if (!s.armed) return;
        s.start = e.latlng;
        if (s.rect) { s.rect.remove(); s.rect = null; }
      });
      map.on("mousemove", (e: any) => {
        const s = drawState.current;
        if (!s.armed || !s.start) return;
        const bounds = L.latLngBounds(s.start, e.latlng);
        if (s.rect) s.rect.setBounds(bounds);
        else s.rect = L.rectangle(bounds, { color: "#4c9be8", weight: 1, fillOpacity: 0.1 }).addTo(map);
      });
      map.on("mouseup", (e: any) => {
        const s = drawState.current;
        if (!s.armed || !s.start) return;
        const b = L.latLngBounds(s.start, e.latlng);
        s.armed = false; s.start = null;
        map.dragging.enable();
        map.getContainer().style.cursor = "";
        setDrawing(false);
        const np = new URLSearchParams(paramsRef.current);
        np.set("geofield", field);
        np.set("geo", `bbox:${b.getSouth()},${b.getWest()},${b.getNorth()},${b.getEast()}`);
        np.set("page", "1");
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

  function armDraw() {
    const map = mapRef.current;
    if (!map) return;
    drawState.current.armed = true;
    map.dragging.disable();
    map.getContainer().style.cursor = "crosshair";
    setDrawing(true);
  }
  function clearArea() {
    const s = drawState.current;
    if (s.rect) { s.rect.remove(); s.rect = null; }
    const np = new URLSearchParams(params);
    np.delete("geo"); np.delete("geofield");
    setParams(np, { preventScrollReset: true });
  }

  return (
    <section className="card">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", gap: 8, flexWrap: "wrap" }}>
        <h3>Map <span className="mut">— {points.length} located; {drawing ? "drag on the map to draw a box" : "draw a box to filter by area"}</span></h3>
        <div className="fieldbar" style={{ gap: 8 }}>
          <button type="button" className="btn ghost" onClick={armDraw} disabled={drawing}>▭ draw area</button>
          {params.get("geo") && <button type="button" className="btn ghost" onClick={clearArea}>✕ clear area</button>}
        </div>
      </div>
      <div ref={ref} style={{ height: 440, borderRadius: 8, overflow: "hidden", marginTop: 10 }} />
    </section>
  );
}
