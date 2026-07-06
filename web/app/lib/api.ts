// Server-side client for the Flask JSON API. Loaders/actions run on the RR7 server,
// so these fetch the Flask backend directly (browser never talks to Flask -> no CORS).
import type {
  CollectionDetail, CollectionSearchResponse, CollectionSummary,
  MatchKey, MatchResult, Overview, SearchResponse,
} from "./types";

// Defaults to the Kotlin backend (server-kt); override with API_URL to point elsewhere.
const BASE = process.env.API_URL ?? "http://localhost:8080";

async function get<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`);
  if (!res.ok) throw new Response(await res.text(), { status: res.status });
  return res.json() as Promise<T>;
}

async function postJson<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  // The API returns useful error bodies with non-2xx; surface them as data, not throws.
  return res.json() as Promise<T>;
}

export const api = {
  overview: () => get<Overview>("/api/overview"),
  samples: () => get<{ samples: string[] }>("/api/samples"),
  loadSample: (name: string) => postJson<Overview>("/api/load_sample", { name }),

  // multipart upload — forward the incoming FormData straight through
  upload: async (form: FormData): Promise<Overview> => {
    const res = await fetch(`${BASE}/api/upload`, { method: "POST", body: form });
    return res.json();
  },

  search: (body: unknown) => postJson<SearchResponse>("/api/search", body),

  collections: () => get<{ collections: CollectionSummary[] }>("/api/collections"),
  collection: (name: string) => get<CollectionDetail>(`/api/collections/${encodeURIComponent(name)}`),
  createCollection: (name: string, key: string) => postJson<CollectionDetail>("/api/collections", { name, key }),
  deleteCollection: async (name: string) => {
    await fetch(`${BASE}/api/collections/${encodeURIComponent(name)}`, { method: "DELETE" });
  },
  putSegment: (name: string, segName: string, filters: { field: string; value: string }[]) =>
    postJson<CollectionDetail>(`/api/collections/${encodeURIComponent(name)}/segments`, { name: segName, filters }),
  deleteSegment: async (name: string, seg: string) => {
    await fetch(`${BASE}/api/collections/${encodeURIComponent(name)}/segments/${encodeURIComponent(seg)}`, { method: "DELETE" });
  },
  setMapping: (name: string, column: string, canonical: string) =>
    postJson<CollectionDetail>(`/api/collections/${encodeURIComponent(name)}/mapping`, { column, canonical }),
  addCanonical: (name: string, canon: string, type: string) =>
    postJson<CollectionDetail>(`/api/collections/${encodeURIComponent(name)}/canonicals`, { name: canon, type }),
  deleteCanonical: async (name: string, canon: string) => {
    await fetch(`${BASE}/api/collections/${encodeURIComponent(name)}/canonicals/${encodeURIComponent(canon)}`, { method: "DELETE" });
  },
  setCollectionKey: (name: string, key: string) =>
    postJson<CollectionDetail>(`/api/collections/${encodeURIComponent(name)}/key`, { key }),
  addSampleToCollection: (name: string, sample: string) =>
    postJson<{ added?: string; error?: string; detail?: CollectionDetail }>(
      `/api/collections/${encodeURIComponent(name)}/add`, { sample }),
  addFileToCollection: async (name: string, form: FormData) => {
    const res = await fetch(`${BASE}/api/collections/${encodeURIComponent(name)}/add`,
      { method: "POST", body: form });
    return res.json() as Promise<{ added?: string; error?: string; detail?: CollectionDetail }>;
  },
  searchCollection: (name: string, body: unknown) =>
    postJson<CollectionSearchResponse>(`/api/collections/${encodeURIComponent(name)}/search`, body),

  matchCandidates: (a: string, b: string) => postJson<{ keys: MatchKey[] }>("/api/match_candidates", { a, b }),
  match: (body: unknown) => postJson<MatchResult>("/api/match", body),
};
