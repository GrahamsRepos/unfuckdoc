export function Pagination({
  page,
  pageSize,
  total,
  onPage,
}: {
  page: number;
  pageSize: number;
  total: number;
  onPage: (nextPage: number) => void;
}) {
  const totalPages = Math.max(1, Math.ceil(total / Math.max(1, pageSize)));
  const currentPage = Math.min(Math.max(1, page), totalPages);
  if (totalPages <= 1) return null;

  return (
    <div className="fieldbar" style={{ justifyContent: "space-between", marginTop: 12 }}>
      <span className="mut">
        Showing {Math.min((currentPage - 1) * pageSize + 1, total)}-{Math.min(currentPage * pageSize, total)} of {total}
      </span>
      <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
        <button className="btn ghost" type="button" disabled={currentPage <= 1} onClick={() => onPage(currentPage - 1)}>
          ← prev
        </button>
        <span className="mut">page {currentPage} / {totalPages}</span>
        <button className="btn ghost" type="button" disabled={currentPage >= totalPages} onClick={() => onPage(currentPage + 1)}>
          next →
        </button>
      </div>
    </div>
  );
}
