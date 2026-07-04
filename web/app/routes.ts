import { type RouteConfig, index, route } from "@react-router/dev/routes";

export default [
  index("routes/home.tsx"),
  route("collections", "routes/collections.tsx"),
  route("collections/:name", "routes/collection.tsx"),
  route("match", "routes/match.tsx"),
] satisfies RouteConfig;
