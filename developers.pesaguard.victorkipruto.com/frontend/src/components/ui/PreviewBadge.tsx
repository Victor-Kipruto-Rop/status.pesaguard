export function PreviewBadge({ children = "Preview data" }: { children?: string }) {
  return <span className="preview-badge"><span />{children}</span>;
}
