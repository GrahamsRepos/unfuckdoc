import { type RouteConfig, index, route } from "@react-router/dev/routes";

export default [
  index("routes/home.tsx"),
  route("collections", "routes/collections.tsx"),
  route("collections/:name", "routes/collection.tsx", [
    index("routes/collection.sources.tsx"),
    route("canonical", "routes/collection.canonical.tsx"),
    route("enrich", "routes/collection.enrich.tsx"),
    route("explore", "routes/collection.explore.tsx"),
  ]),
  route("match", "routes/match.tsx"),
] satisfies RouteConfig;
