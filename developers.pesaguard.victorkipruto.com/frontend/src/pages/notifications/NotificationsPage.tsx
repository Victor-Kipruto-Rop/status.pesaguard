import { getUserMessage } from "../../lib/errors";
import { useEffect, useMemo, useState } from "react";
import { Bell, Check, CheckCheck, Filter, Settings2, X } from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";
import { apiData } from "../../lib/api";
import { NotificationSettingsPage } from "./NotificationSettingsPage";
import "./notifications.css";

type NotificationItem = {
  id: string;
  type: string;
  category: string;
  subject: string;
  body: string;
  severity: string;
  resourceType: string | null;
  resourceId: string | null;
  actionUrl: string | null;
  createdAt: string;
  readAt: string | null;
  deliveryState: string;
};

type InboxResponse = { items: NotificationItem[]; unreadCount: number };
type InboxPage = InboxResponse & { totalItems: number; totalPages: number; page: number; size: number };
type Filter = "all" | "unread" | string;

function formatTime(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(date);
}

function safeInternalPath(value: string | null) {
  if (!value?.startsWith("/") || value.startsWith("//")) return null;
  const target = new URL(value, window.location.origin);
  return target.origin === window.location.origin ? value : null;
}

export function NotificationsPage() {
  const [items, setItems] = useState<NotificationItem[]>([]);
  const [unreadTotal, setUnreadTotal] = useState(0);
  const [totalItems, setTotalItems] = useState(0);
  const [page, setPage] = useState(0);
  const [filter, setFilter] = useState<Filter>("all");
  const [severity, setSeverity] = useState("");
  const [search, setSearch] = useState("");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [preferencesOpen, setPreferencesOpen] = useState(false);
  const [selectedNotification, setSelectedNotification] = useState<NotificationItem | null>(null);

  async function loadInbox() {
    setLoading(true);
    setError("");
    try {
      const params = new URLSearchParams({ page: String(page), size: "50" });
      if (filter === "unread") params.set("unreadOnly", "true");
      else if (filter !== "all") params.set("category", filter);
      if (severity) params.set("severity", severity);
      const result = await apiData<InboxPage>(`/api/v1/notifications?${params.toString()}`);
      if (!Array.isArray(result.items) || typeof result.unreadCount !== "number" || typeof result.totalItems !== "number") {
        throw new Error("The notifications API returned an invalid inbox response.");
      }
      setItems(result.items);
      setUnreadTotal(result.unreadCount);
      setTotalItems(result.totalItems);
    } catch (requestError) {
      setError(getUserMessage(requestError, "Could not load notifications."));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadInbox();
  }, [page, filter, severity]);

  useEffect(() => {
    if (!preferencesOpen && !selectedNotification) return;
    function closeOnEscape(event: KeyboardEvent) {
      if (event.key === "Escape") {
        setPreferencesOpen(false);
        setSelectedNotification(null);
      }
    }
    window.addEventListener("keydown", closeOnEscape);
    return () => window.removeEventListener("keydown", closeOnEscape);
  }, [preferencesOpen, selectedNotification]);

  const unreadCount = unreadTotal;
  const categories = useMemo(() => [...new Set(items.map((item) => item.category))].sort(), [items]);
  const visibleItems = useMemo(() => items.filter((item) => {
    const matchesSearch = `${item.subject} ${item.body} ${item.category} ${item.type}`
      .toLowerCase().includes(search.trim().toLowerCase());
    return matchesSearch;
  }), [items, search]);
  const selectedActionPath = safeInternalPath(selectedNotification?.actionUrl ?? null);

  async function markRead(item: NotificationItem) {
    setBusy(true);
    setError("");
    try {
      const updated = await apiData<NotificationItem>(`/api/v1/notifications/${encodeURIComponent(item.id)}/read`, {
        method: "POST",
      });
      if (!item.readAt && updated.readAt) setUnreadTotal((count) => Math.max(0, count - 1));
      setItems((current) => current.filter((currentItem) => currentItem.id !== updated.id));
      setTotalItems((count) => Math.max(0, count - 1));
      setSelectedNotification((current) => current?.id === updated.id ? null : current);
      window.dispatchEvent(new Event("pesaguard:notifications-updated"));
    } catch (requestError) {
      setError(getUserMessage(requestError, "Could not mark the notification read."));
    } finally {
      setBusy(false);
    }
  }

  async function markAllRead() {
    setBusy(true);
    setError("");
    try {
      const result = await apiData<{ updated: number; readAt: string; deleted: number }>("/api/v1/notifications/read-all", { method: "POST" });
      if (typeof result.updated !== "number" || typeof result.readAt !== "string" || typeof result.deleted !== "number") throw new Error("The notifications API returned an invalid read-all response.");
      setItems([]);
      setUnreadTotal(0);
      setTotalItems((count) => Math.max(0, count - result.deleted));
      setSelectedNotification(null);
      window.dispatchEvent(new Event("pesaguard:notifications-updated"));
    } catch (requestError) {
      setError(getUserMessage(requestError, "Could not mark notifications read."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <PageHeader
        eyebrow="OPERATIONS"
        title="Notifications"
        description="Review notifications recorded for your account."
        action={<button className="button button--secondary" onClick={() => { setSelectedNotification(null); setPreferencesOpen(true); }}><Settings2 size={15} />Preferences</button>}
      />
      <nav className="notification-context-nav" aria-label="Notification sections">
        <button type="button" className="is-active" aria-current="page"><Bell size={14} />Inbox</button>
        <button type="button" onClick={() => { setSelectedNotification(null); setPreferencesOpen(true); }}><Settings2 size={14} />Preferences</button>
      </nav>
      {error && <p className="notification-alert" role="alert">{error}</p>}

      <section className="notification-inbox-summary" aria-label="Notification summary">
        <article className="panel notification-summary-card"><span className="notification-summary-icon"><Bell size={16} /></span><div><small>INBOX</small><strong>{totalItems}</strong><span>saved notifications</span></div></article>
        <article className="panel notification-summary-card"><span className="notification-summary-icon"><CheckCheck size={16} /></span><div><small>UNREAD</small><strong>{unreadCount}</strong><span>from the system</span></div></article>
        <article className="panel notification-summary-card"><span className="notification-summary-icon"><Filter size={16} /></span><div><small>CATEGORIES</small><strong>{categories.length}</strong><span>with saved records</span></div></article>
      </section>

      <section className="panel notification-inbox-panel">
        <div className="notification-inbox-toolbar">
          <div><h2>Inbox</h2><p>{unreadCount ? `${unreadCount} unread notifications` : "No unread notifications"}</p></div>
          <div className="notification-inbox-actions">
            <label className="notification-search"><Filter size={14} /><span className="visually-hidden">Search notifications</span><input placeholder="Search notifications…" value={search} onChange={(event) => setSearch(event.target.value)} /></label>
            <button className="button button--ghost" type="button" disabled={busy || unreadCount === 0} onClick={() => void markAllRead()}><CheckCheck size={14} />Mark all read</button>
          </div>
        </div>
        <div className="notification-filter-row" aria-label="Filter notifications">
          {(["all", "unread", ...categories] as const).map((choice) => (
            <button className={`notification-filter-chip${filter === choice ? " is-selected" : ""}`} type="button" key={choice} aria-pressed={filter === choice} onClick={() => { setPage(0); setFilter(choice); }}>
              {choice === "all" ? "All" : choice === "unread" ? `Unread ${unreadCount}` : choice.replaceAll("_", " ")}
            </button>
          ))}
          <label className="notification-filter-chip">Severity
            <select aria-label="Filter by severity" value={severity} onChange={(event) => { setPage(0); setSeverity(event.target.value); }}>
              <option value="">All</option>
              {["INFO", "SUCCESS", "WARNING", "ERROR", "CRITICAL", "SECURITY"].map((level) => <option key={level} value={level}>{level}</option>)}
            </select>
          </label>
        </div>
        <div className="notification-list">
          {loading
            ? <div className="notification-empty"><Bell size={20} /><strong>Loading notifications</strong></div>
            : visibleItems.map((item) => {
              const isRead = Boolean(item.readAt);
              return (
                <article className={`notification-list-item${isRead ? " is-read" : " is-unread"}`} key={item.id}>
                  <span className="notification-item-indicator" aria-hidden="true" />
                  <div className="notification-item-content">
                    <div className="notification-item-heading"><span className={`notification-severity notification-severity--${item.severity.toLowerCase()}`}>{item.severity}</span><button className="notification-item-title-button" type="button" onClick={() => { setPreferencesOpen(false); setSelectedNotification(item); }}>{item.subject}</button></div>
                    <p>{item.body}</p>
                    <div className="notification-item-meta"><span>{item.category.replaceAll("_", " ")}</span><span>{item.type.replaceAll("_", " ")}</span><time>{formatTime(item.createdAt)}</time></div>
                  </div>
                  <button className="notification-read-button" type="button" disabled={busy || isRead} onClick={() => void markRead(item)} aria-label={`Mark read: ${item.subject}`}>
                    {isRead ? <span>Read</span> : <Check size={14} />}
                  </button>
                </article>
              );
            })}
          {!loading && visibleItems.length === 0 && <div className="notification-empty"><Bell size={20} /><strong>{totalItems ? "No notifications match" : "No notifications yet"}</strong><span>{totalItems ? "Try another filter or search term." : "Notifications will appear here when system events are recorded."}</span></div>}
        </div>
        <div className="notification-inbox-footer"><span>Page {page + 1} of {Math.max(1, Math.ceil(totalItems / 50))} · {totalItems} notifications</span><div><button className="button button--secondary" type="button" disabled={loading || page === 0} onClick={() => setPage((current) => Math.max(0, current - 1))}>Previous</button><button className="button button--secondary" type="button" disabled={loading || (page + 1) * 50 >= totalItems} onClick={() => setPage((current) => current + 1)}>Next</button></div></div>
      </section>
      {preferencesOpen && (
        <div className="notification-drawer-backdrop" onMouseDown={(event) => {
          if (event.target === event.currentTarget) setPreferencesOpen(false);
        }}>
          <aside className="notification-preferences-drawer" role="dialog" aria-modal="true" aria-labelledby="notification-drawer-title">
            <div className="notification-drawer-topbar">
              <div><span className="section-eyebrow">NOTIFICATIONS</span><h2 id="notification-drawer-title">Preferences</h2><p>Configure account notification channels.</p></div>
              <button className="icon-button" type="button" aria-label="Close notification preferences" autoFocus onClick={() => setPreferencesOpen(false)}><X size={17} /></button>
            </div>
            <NotificationSettingsPage />
          </aside>
        </div>
      )}
      {selectedNotification && (
        <div className="notification-drawer-backdrop" onMouseDown={(event) => {
          if (event.target === event.currentTarget) setSelectedNotification(null);
        }}>
          <aside className="notification-detail-drawer" role="dialog" aria-modal="true" aria-labelledby="notification-detail-title">
            <div className="notification-drawer-topbar">
              <div><span className="section-eyebrow">NOTIFICATION DETAIL</span><h2 id="notification-detail-title">{selectedNotification.category.replaceAll("_", " ")}</h2><p>{selectedNotification.id}</p></div>
              <button className="icon-button" type="button" aria-label="Close notification details" autoFocus onClick={() => setSelectedNotification(null)}><X size={17} /></button>
            </div>
            <div className="notification-detail-content">
              <h3>{selectedNotification.subject}</h3>
              <p>{selectedNotification.body}</p>
              <div className="notification-detail-facts">
                <div className="notification-detail-fact"><small>Type</small><strong>{selectedNotification.type.replaceAll("_", " ")}</strong></div>
                <div className="notification-detail-fact"><small>Severity</small><strong>{selectedNotification.severity}</strong></div>
                {selectedNotification.resourceType && <div className="notification-detail-fact"><small>Related resource</small><strong>{selectedNotification.resourceType.replaceAll("_", " ")}{selectedNotification.resourceId ? ` · ${selectedNotification.resourceId}` : ""}</strong></div>}
                <div className="notification-detail-fact"><small>Received</small><strong>{formatTime(selectedNotification.createdAt)}</strong></div>
                <div className="notification-detail-fact"><small>Delivery record</small><strong>{selectedNotification.deliveryState}</strong></div>
                <div className="notification-detail-fact"><small>State</small><strong>{selectedNotification.readAt ? `Read ${formatTime(selectedNotification.readAt)}` : "Unread"}</strong></div>
              </div>
              {selectedActionPath && <a className="button button--secondary" href={selectedActionPath}>Open related page</a>}
              {!selectedNotification.readAt && <button className="button button--secondary" type="button" disabled={busy} onClick={() => void markRead(selectedNotification)}>Mark read</button>}
            </div>
          </aside>
        </div>
      )}
    </>
  );
}
