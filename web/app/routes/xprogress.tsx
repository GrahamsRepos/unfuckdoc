import type { Route } from "./+types/xprogress";
import { api } from "~/lib/api";

/** Resource route (loader only) — lets the UI poll live extraction progress without reloading detail. */
export async function loader({ params }: Route.LoaderArgs) {
  return api.extractProgress(params.name);
}
