// TypeScript mirrors of the Flask JSON payloads (src/webapp.py).

export interface OsStatus {
  status: "indexed" | "unavailable" | "error" | "no-client" | "unknown";
  index?: string;
  count?: number;
  detail?: string;
}

export interface Column {
  name: string;
  kind: string;
  os_type: string | null;
  fill_rate: number;
  margin: number | null;
  source: string;
  searchable: boolean;
  canonical: string;
  canonical_method: string;
  cardinality?: number;
  distinct_ratio?: number;
  avg_words?: number;
  note?: string;
}

export interface MergeGroup {
  canonical: string;
  columns: string[];
  unified: boolean;
}

export interface UnifiedField {
  canonical: string;
  cardinality: "scalar" | "array";
  style: string;
  kind: string;
  os_type: string | null;
  sources: string[];
  labels: (string | null)[];
}

export interface Facet {
  field: string;
  kind: string;
  os_type: string | null;
  cardinality: string;
  distinct: number;
  values?: [string, number][];
}

export interface RegistrySource {
  ref: string;
  kind?: string;
  os_type?: string | null;
  method?: string;
}
export interface RegistryEntry {
  canonical: string;
  sources: RegistrySource[];
  n_files: number;
  n_columns: number;
  unified: boolean;
}

export interface Mapping {
  settings?: Record<string, unknown>;
  mappings: { properties: Record<string, any> };
}

export interface Overview {
  loaded: boolean;
  filename?: string;
  n_rows: number;
  n_cols: number;
  llm_calls: number;
  coerced: number;
  quarantine: number;
  columns: Column[];
  kind_counts: Record<string, number>;
  merge_groups: MergeGroup[];
  fuzzy: string[];
  tags: Record<string, [string, number][]>;
  all_tags: string[];
  unified: UnifiedField[];
  facets: Facet[];
  mapping: Mapping;
  embedder?: string;
  vec_dim?: number;
  sample_docs: Record<string, unknown>[];
  display_columns: string[];
  registry: RegistryEntry[];
  opensearch: OsStatus;
}

export type CellValue =
  | string | number | boolean | null
  | Array<string | number | { type: string; value: unknown }>;

export interface SearchResult {
  score: number | string;
  row: Record<string, CellValue>;
  keywords: string[];
}
export interface FieldFilter { field: string; value: string; }
export interface SearchResponse {
  mode: string;
  field: string | null;
  tag: string;
  filters: FieldFilter[];
  count: number;
  display_columns: string[];
  results: SearchResult[];
  dsl: unknown;
  index?: string;
  error?: string;
}

export interface CollectionSummary {
  name: string; index: string; n_files: number; n_records: number; n_fields: number;
  key_field: string;
}
export interface SchemaField {
  field: string; os_type: string | null; kind: string; cardinality: string;
  sources: string[]; n_sources: number; count: number; conflict: boolean;
  values?: [string, number][];
}
export interface FileMappingEntry { column: string; canonical: string; method: string; }
export interface CollectionFile { name: string; rows: number; mapping: FileMappingEntry[]; }
export interface Segment { name: string; filters: FieldFilter[]; count: number; }
export interface CollectionDetail {
  name: string; index: string; n_records: number;
  key_field: string; raw_records: number; merged: number;
  schema: SchemaField[]; files: CollectionFile[]; segments: Segment[]; opensearch: OsStatus;
}
export interface CollectionSearchResponse {
  display: string[]; count: number; results: Record<string, string>[];
  dsl: unknown; index: string; error?: string;
}

export interface MatchKey {
  field: string; kind: string; uniqueness: number; fill_a?: number; fill_b?: number;
}
export interface MatchPair { sim: number; a: Record<string, string>; b: Record<string, string>; }
export interface MatchResult {
  key: string; threshold: number; rows_a: number; rows_b: number; keyed_a: number;
  matched: number; exact: number; unmatched_a: number; unmatched_b: number;
  display_a: string[]; display_b: string[]; pairs: MatchPair[]; error?: string;
}
