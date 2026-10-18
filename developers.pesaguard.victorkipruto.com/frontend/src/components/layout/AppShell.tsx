import { getUserMessage } from "../../lib/errors";
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode, type KeyboardEvent as ReactKeyboardEvent } from "react";
import {
  Activity,
  ArrowRight,
  Bell,
  BookOpenText,
  ChevronDown,
  Clock,
  CircleHelp,
  Command,
  FileText,
  FolderKanban,
  KeyRound,
  LogOut,
  Moon,
  ExternalLink,
  Globe,
  LayoutDashboard,
  LifeBuoy,
  Menu,
  Network,
  PanelLeftClose,
  PanelLeftOpen,
  RotateCw,
  Search,
  Sparkles,
  ShieldCheck,
  UserRound,
  X,
  Webhook,
  type LucideIcon,
} from "lucide-react";
import {
  apiEndpointSearchIndex,
  externalLinks,
  navigationItems,
  type NavigationDrawer,
  type PageId,
} from "../../app/routes";
import { useAuth } from "../../context/AuthContext";
import { apiData, refreshSession } from "../../lib/api";
import { isFeatureEnabled, featureForPage } from "../../lib/runtimeFeatures";
import { useToast } from "../ui/ToastProvider";
import {
  readActiveEnvironmentContext,
  readActiveEnvironmentId,
  readActiveProjectId,
  setActiveEnvironment,
  setActiveProject,
} from "../../lib/activeEnvironment";

type NotificationItem = {
  id: string;
  subject: string;
  body: string;
  category: string;
  createdAt: string;
};
type NotificationInbox = { items: NotificationItem[]; unreadCount: number };
type HeaderProject = { id: string; name: string };
type HeaderEnvironment = { id: string; projectId: string; name: string; type: string; status: string; baseUrl: string };
type HeaderProjectList = { items: HeaderProject[] };
type WorkspaceSearchResult = { id: string; type: "PROJECT" | "ENVIRONMENT" | "API_KEY" | "WEBHOOK" | "LOG"; label: string; detail: string; projectId: string | null };
type ChangelogEntry = {
  id: string;
  version: string;
  title: string;
  body: string;
  category: string;
  publishedAt: string;
};

const SESSION_IDLE_TIMEOUT_MS = 30 * 60_000;

interface AppShellProps {
  activePage: PageId;
  onNavigate: (page: PageId) => void;
  features: Record<string, boolean>;
  children: ReactNode;
}

type CommandAction =
  | { kind: "navigate"; page: PageId; intent?: string }
  | { kind: "external"; href: string };

type CommandGroup = "Commands" | "Pages" | "Endpoints" | "Resources" | "Help" | "Recent" | "Suggested";

interface CommandEntry {
  id: string;
  label: string;
  detail: string;
  keywords: string[];
  group: CommandGroup;
  icon: LucideIcon;
  action: CommandAction;
  previewOnly?: boolean;
}

type PageUsage = { page: PageId; visits: number; lastVisited: number };

function readPageUsage(storageKey: string): PageUsage[] {
  try {
    const stored = window.localStorage.getItem(storageKey);
    const parsed: unknown = stored ? JSON.parse(stored) : [];
    if (!Array.isArray(parsed)) return [];
    return parsed.filter((item): item is PageUsage =>
      Boolean(item)
      && typeof item === "object"
      && "page" in item
      && typeof item.page === "string"
      && "visits" in item
      && typeof item.visits === "number"
      && "lastVisited" in item
      && typeof item.lastVisited === "number",
    );
  } catch {
    return [];
  }
}

function readSidebarCollapsed(storageKey: string): boolean {
  try {
    return window.localStorage.getItem(storageKey) === "true";
  } catch {
    return false;
  }
}

function recommendationScore(entry: CommandEntry, query: string, usage: PageUsage | undefined) {
  const normalize = (value: string) => value.toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
  const normalizedQuery = normalize(query);
  const label = normalize(entry.label);
  const searchable = normalize([entry.label, entry.detail, ...entry.keywords].join(" "));
  const tokens = normalizedQuery.split(/\s+/).filter(Boolean);
  const matchedTokens = tokens.filter((token) => searchable.includes(token));
  if (!matchedTokens.length) return 0;

  let score = matchedTokens.length / tokens.length * 20;
  if (label === normalizedQuery) score += 40;
  else if (label.startsWith(normalizedQuery)) score += 24;
  else if (label.includes(normalizedQuery)) score += 16;
  score += matchedTokens.filter((token) => label.split(" ").some((word) => word.startsWith(token))).length * 4;
  if (usage) {
    const ageInDays = Math.max(0, (Date.now() - usage.lastVisited) / 86_400_000);
    score += Math.min(8, Math.log2(usage.visits + 1) * 2);
    score += 5 / (1 + ageInDays / 7);
  }
  return score;
}

const shortcutPages: Record<string, PageId> = {
  d: "overview",
  p: "projects",
  a: "api-catalog",
  w: "webhooks",
  l: "logs",
};

const commandEntries: CommandEntry[] = [
  { id: "create-project", label: "Create project", detail: "Start a new project from a template.", keywords: ["new", "project", "template"], group: "Commands", icon: FolderKanban, action: { kind: "navigate", page: "projects", intent: "create-project" } },
  { id: "create-api-key", label: "Create API key", detail: "Open the connected API credential manager.", keywords: ["new", "credential", "token", "api key"], group: "Commands", icon: KeyRound, action: { kind: "navigate", page: "api-keys" } },
  { id: "create-webhook", label: "Create webhook", detail: "Open connected webhook management.", keywords: ["new", "endpoint", "events", "webhook"], group: "Commands", icon: Webhook, action: { kind: "navigate", page: "webhooks" } },
  { id: "open-api-explorer", label: "Open API Explorer", detail: "Browse endpoint request examples.", keywords: ["api", "endpoints", "explorer", "request"], group: "Commands", icon: Network, action: { kind: "navigate", page: "api-explorer" } },
  { id: "view-api-logs", label: "View API logs", detail: "Search request, response, auth, event, webhook, and audit logs.", keywords: ["logs", "requests", "usage", "analytics", "activity"], group: "Commands", icon: Activity, action: { kind: "navigate", page: "logs" } },
  { id: "rotate-credential", label: "Rotate credential", detail: "Open credentials to rotate an existing API key.", keywords: ["api key", "rotate", "secret", "credential"], group: "Commands", icon: RotateCw, action: { kind: "navigate", page: "api-keys" } },
  { id: "open-golive", label: "Open Go-Live", detail: "Verify Production readiness and launch an integration.", keywords: ["production", "go-live", "readiness", "launch", "deploy"], group: "Commands", icon: ShieldCheck, action: { kind: "navigate", page: "go-live" } },
  { id: "open-documentation", label: "Open documentation", detail: "Open the PesaGuard developer documentation.", keywords: ["docs", "help", "guides", "reference"], group: "Commands", icon: BookOpenText, action: { kind: "external", href: externalLinks.docs } },
  { id: "report-issue", label: "Contact PesaGuard Support", detail: "Search help articles or submit a support ticket from the Support Center.", keywords: ["bug", "feedback", "support", "problem", "ticket"], group: "Commands", icon: FileText, action: { kind: "navigate", page: "support-hub" } },
  { id: "search-dashboard", label: "Dashboard", detail: "Open the workspace command overview.", keywords: ["home", "overview", "dashboard"], group: "Pages", icon: LayoutDashboard, action: { kind: "navigate", page: "overview" } },
  { id: "search-apis", label: "APIs", detail: "Browse API products and versions.", keywords: ["api", "products", "catalog"], group: "Pages", icon: Network, action: { kind: "navigate", page: "api-catalog" } },
  { id: "search-api-lifecycle", label: "API lifecycle", detail: "Follow an integration from discovery to production operations.", keywords: ["api lifecycle", "integration", "onboarding", "production", "deploy"], group: "Pages", icon: Activity, action: { kind: "navigate", page: "api-lifecycle" } },
  { id: "search-projects", label: "Projects", detail: "Search and manage projects.", keywords: ["project", "workspace"], group: "Pages", icon: FolderKanban, action: { kind: "navigate", page: "projects" } },
  { id: "search-environments", label: "Environments", detail: "Browse project environments.", keywords: ["environment", "sandbox", "staging", "production"], group: "Pages", icon: Globe, action: { kind: "navigate", page: "environments" } },
  { id: "search-api-keys", label: "API keys", detail: "Open credentials.", keywords: ["api key", "keys", "credential", "secret"], group: "Pages", icon: KeyRound, action: { kind: "navigate", page: "api-keys" } },
  { id: "search-oauth", label: "OAuth applications", detail: "Browse OAuth applications.", keywords: ["oauth", "applications", "clients"], group: "Pages", icon: KeyRound, action: { kind: "navigate", page: "oauth-applications" } },
  { id: "search-webhooks", label: "Webhooks", detail: "Browse webhook setup and delivery guidance.", keywords: ["webhook", "delivery", "events"], group: "Pages", icon: Webhook, action: { kind: "navigate", page: "webhooks" } },
  { id: "search-events", label: "Event platform", detail: "Browse event schemas, versions, subscriptions, delivery, and lineage.", keywords: ["event", "catalog", "schema", "version", "explorer", "replay", "subscription", "delivery", "lineage", "activity", "stream"], group: "Pages", icon: Activity, action: { kind: "navigate", page: "events" } },
  { id: "search-logs", label: "Logs", detail: "Search request logs and inspect individual log details.", keywords: ["logs", "requests", "usage", "analytics", "audit"], group: "Pages", icon: FileText, action: { kind: "navigate", page: "logs" } },
  { id: "search-debugging", label: "Request debugging", detail: "Inspect request traces, spans, dependencies, and errors.", keywords: ["debug", "debugging", "trace", "tracing", "request id", "correlation", "span"], group: "Pages", icon: Activity, action: { kind: "navigate", page: "debugging" } },
  { id: "search-audit", label: "Audit logs", detail: "Open organization audit records.", keywords: ["audit", "logs", "security", "history"], group: "Pages", icon: FileText, action: { kind: "navigate", page: "audit-logs" } },
  { id: "search-docs", label: "Documentation", detail: "Open the API reference and developer guides.", keywords: ["docs", "help", "guides", "reference"], group: "Help", icon: BookOpenText, action: { kind: "external", href: externalLinks.docs } },
  { id: "open-support-hub", label: "Open support", detail: "Find documentation, developer support, status, tickets, and community links.", keywords: ["support", "help center", "tickets", "incidents", "community", "announcements"], group: "Help", icon: LifeBuoy, action: { kind: "navigate", page: "support-hub" } },
];

export function AppShell({ activePage, onNavigate, features, children }: AppShellProps) {
  const { status, user, organization, hasPermission, permissionsLoaded, logout, welcomePending, consumeWelcome } = useAuth();
  const { showToast } = useToast();
  const connected = status === "authenticated";
  const profileName = user?.displayName || user?.email || "Developer";
  const welcomeName = user?.displayName?.trim().split(/\s+/)[0] || "developer";
  const [welcomeVisible, setWelcomeVisible] = useState(false);
  const [sessionRemainingMs, setSessionRemainingMs] = useState<number | null>(null);
  const [sessionExtending, setSessionExtending] = useState(false);
  const [sessionExtendError, setSessionExtendError] = useState("");
  const lastActivityAtRef = useRef<number | null>(null);
  const idleExpiredRef = useRef(false);
  const sessionActivityStorageKey = `pesaguard.last-activity.${user?.id ?? "guest"}`;
  const previousSessionActivityStorageKeyRef = useRef(sessionActivityStorageKey);
  const sidebarStorageKey = `pesaguard.sidebar-collapsed.${user?.id ?? "guest"}`;
  const activeNavigationLabel = navigationItems.find((item) => item.target.kind === "page" && item.target.page === activePage)?.label
    ?? activePage.replace(/-/g, " ");
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(() => readSidebarCollapsed(sidebarStorageKey));
  const [selectorProjects, setSelectorProjects] = useState<HeaderProject[]>([]);
  const [selectorEnvironments, setSelectorEnvironments] = useState<HeaderEnvironment[]>([]);
  const [selectedProjectId, setSelectedProjectId] = useState(readActiveProjectId);
  const [selectedEnvironmentId, setSelectedEnvironmentId] = useState(
    () => readActiveEnvironmentContext().environmentId,
  );
  const [selectorLoading, setSelectorLoading] = useState(false);
  const [selectorError, setSelectorError] = useState("");
  const [profileMenuOpen, setProfileMenuOpen] = useState(false);
  const profileMenuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!welcomePending) return;
    consumeWelcome();
    setWelcomeVisible(true);
  }, [consumeWelcome, welcomePending]);

  useEffect(() => {
    if (!welcomeVisible) return;
    const timeout = window.setTimeout(() => setWelcomeVisible(false), 4500);
    return () => window.clearTimeout(timeout);
  }, [welcomeVisible]);

  useEffect(() => {
    document.title = `${activeNavigationLabel} | PesaGuard Developer Platform`;
  }, [activeNavigationLabel]);

  const markSessionActivity = useCallback((force = false) => {
    if (!connected || (!force && idleExpiredRef.current)) return;
    const now = Date.now();
    if (!force && now - (lastActivityAtRef.current ?? 0) < 15_000) return;
    lastActivityAtRef.current = now;
    idleExpiredRef.current = false;
    window.localStorage.setItem(sessionActivityStorageKey, String(now));
    setSessionRemainingMs(SESSION_IDLE_TIMEOUT_MS);
    setSessionExtendError("");
  }, [connected, sessionActivityStorageKey]);

  useEffect(() => {
    if (!connected) {
      lastActivityAtRef.current = null;
      idleExpiredRef.current = false;
      setSessionRemainingMs(null);
      if (status === "guest") {
        window.localStorage.removeItem(previousSessionActivityStorageKeyRef.current);
        window.localStorage.removeItem(sessionActivityStorageKey);
        previousSessionActivityStorageKeyRef.current = sessionActivityStorageKey;
      }
      return;
    }

    if (previousSessionActivityStorageKeyRef.current !== sessionActivityStorageKey) {
      window.localStorage.removeItem(previousSessionActivityStorageKeyRef.current);
    }
    previousSessionActivityStorageKeyRef.current = sessionActivityStorageKey;

    const now = Date.now();
    const storedActivityAt = Number(window.localStorage.getItem(sessionActivityStorageKey));
    const initialActivityAt = Number.isFinite(storedActivityAt) && storedActivityAt > 0 && storedActivityAt <= now
      ? storedActivityAt
      : now;
    lastActivityAtRef.current = initialActivityAt;
    if (!Number.isFinite(storedActivityAt) || storedActivityAt <= 0 || storedActivityAt > now) {
      window.localStorage.setItem(sessionActivityStorageKey, String(initialActivityAt));
    }

    const updateRemaining = () => {
      const currentTime = Date.now();
      const latestActivityAt = Number(window.localStorage.getItem(sessionActivityStorageKey));
      const lastActivityAt = Number.isFinite(latestActivityAt) && latestActivityAt > 0 && latestActivityAt <= currentTime
        ? latestActivityAt
        : lastActivityAtRef.current ?? currentTime;
      lastActivityAtRef.current = lastActivityAt;
      const remaining = Math.max(0, SESSION_IDLE_TIMEOUT_MS - (currentTime - lastActivityAt));
      idleExpiredRef.current = remaining === 0;
      setSessionRemainingMs(remaining);
    };
    const activityEvents = ["pointerdown", "pointermove", "keydown", "wheel", "touchstart"] as const;
    const onActivity = () => markSessionActivity();
    const onStorage = (event: StorageEvent) => {
      if (event.key === sessionActivityStorageKey) updateRemaining();
    };
    const onVisibilityChange = () => {
      if (document.visibilityState === "visible") updateRemaining();
    };
    activityEvents.forEach((eventName) => window.addEventListener(eventName, onActivity, { passive: true }));
    window.addEventListener("storage", onStorage);
    document.addEventListener("visibilitychange", onVisibilityChange);
    updateRemaining();
    const interval = window.setInterval(updateRemaining, 1000);

    return () => {
      activityEvents.forEach((eventName) => window.removeEventListener(eventName, onActivity));
      window.removeEventListener("storage", onStorage);
      document.removeEventListener("visibilitychange", onVisibilityChange);
      window.clearInterval(interval);
    };
  }, [connected, markSessionActivity, sessionActivityStorageKey, status]);

  useEffect(() => {
    setSidebarCollapsed(readSidebarCollapsed(sidebarStorageKey));
  }, [sidebarStorageKey]);

  useEffect(() => {
    const desktopQuery = window.matchMedia("(min-width: 851px)");
    function closeMobileNavigation(event: MediaQueryListEvent) {
      if (event.matches) setSidebarOpen(false);
    }
    desktopQuery.addEventListener("change", closeMobileNavigation);
    return () => desktopQuery.removeEventListener("change", closeMobileNavigation);
  }, []);

  useEffect(() => {
    function closeProfileMenu(event: KeyboardEvent) {
      if (event.key === "Escape") setProfileMenuOpen(false);
    }
    function closeOnOutsideClick(event: PointerEvent) {
      if (event.target instanceof Node && !profileMenuRef.current?.contains(event.target)) {
        setProfileMenuOpen(false);
      }
    }
    window.addEventListener("keydown", closeProfileMenu);
    document.addEventListener("pointerdown", closeOnOutsideClick);
    return () => {
      window.removeEventListener("keydown", closeProfileMenu);
      document.removeEventListener("pointerdown", closeOnOutsideClick);
    };
  }, []);


  const [paletteOpen, setPaletteOpen] = useState(false);
  const [shortcutHelpOpen, setShortcutHelpOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [resourceEntries, setResourceEntries] = useState<CommandEntry[]>([]);
  const [selectedIndex, setSelectedIndex] = useState(0);
  const [shortcutHint, setShortcutHint] = useState("");
  const [noticeOpen, setNoticeOpen] = useState(false);
  const [notificationUnreadCount, setNotificationUnreadCount] = useState<number | null>(null);
  const [notificationItems, setNotificationItems] = useState<NotificationItem[]>([]);
  const [selectedNotification, setSelectedNotification] = useState<NotificationItem | null>(null);
  const [notificationBusy, setNotificationBusy] = useState(false);
  const [notificationError, setNotificationError] = useState("");
  const [navigationDrawer, setNavigationDrawer] = useState<NavigationDrawer | null>(null);
  const [pageUsage, setPageUsage] = useState<PageUsage[]>([]);
  const pageUsageStorageKey = `pesaguard.search.page-usage.${organization?.id ?? "default"}`;
  const sideNavRef = useRef<HTMLElement>(null);
  const [navigationIndicator, setNavigationIndicator] = useState<{ top: number; left: number; width: number; height: number } | null>(null);
  const [changelogEntries, setChangelogEntries] = useState<ChangelogEntry[]>([]);
  const [changelogLoading, setChangelogLoading] = useState(false);
  const [changelogError, setChangelogError] = useState("");
  const [changelogReload, setChangelogReload] = useState(0);
  const gSequenceTimer = useRef<number | null>(null);

  useEffect(() => {
    const usage = readPageUsage(pageUsageStorageKey);
    const previous = usage.find((item) => item.page === activePage);
    const next = [
      { page: activePage, visits: (previous?.visits ?? 0) + 1, lastVisited: Date.now() },
      ...usage.filter((item) => item.page !== activePage),
    ].slice(0, 40);
    setPageUsage(next);
    try {
      window.localStorage.setItem(pageUsageStorageKey, JSON.stringify(next));
    } catch {
      // Local personalization is optional when browser storage is unavailable.
    }
  }, [activePage, pageUsageStorageKey]);

  useLayoutEffect(() => {
    const nav = sideNavRef.current;
    if (!nav) return;

    const updateIndicator = () => {
      const activeItem = nav.querySelector<HTMLElement>(".nav-item--active");
      if (!activeItem || nav.clientWidth === 0) {
        setNavigationIndicator(null);
        return;
      }
      const navRect = nav.getBoundingClientRect();
      const itemRect = activeItem.getBoundingClientRect();
      setNavigationIndicator({
        top: itemRect.top - navRect.top + nav.scrollTop,
        left: itemRect.left - navRect.left,
        width: itemRect.width,
        height: itemRect.height,
      });
    };

    updateIndicator();
    const observer = typeof ResizeObserver === "undefined" ? null : new ResizeObserver(updateIndicator);
    observer?.observe(nav);
    const activeItem = nav.querySelector<HTMLElement>(".nav-item--active");
    if (activeItem) observer?.observe(activeItem);
    window.addEventListener("resize", updateIndicator);
    return () => {
      observer?.disconnect();
      window.removeEventListener("resize", updateIndicator);
    };
  }, [activePage, connected, features, navigationDrawer, permissionsLoaded, sidebarOpen]);

  useEffect(() => {
    if (!connected) {
      setSelectorProjects([]);
      setSelectorEnvironments([]);
      setSelectedProjectId("");
      setSelectedEnvironmentId("");
      return;
    }
    const controller = new AbortController();
    setSelectorLoading(true);
    setSelectorError("");
    apiData<HeaderProjectList>("/api/v1/projects?page=0&size=100", { signal: controller.signal })
      .then((result) => {
        if (!Array.isArray(result.items)) throw new Error("The projects API returned an invalid list.");
        if (controller.signal.aborted) return;
        setSelectorProjects(result.items);
        const savedProjectId = readActiveProjectId();
        const project = result.items.find((item) => item.id === savedProjectId)
          ?? result.items[0];
        if (!project) {
          setSelectorEnvironments([]);
          setSelectedProjectId("");
          setSelectedEnvironmentId("");
          return;
        }
        setSelectedProjectId(project.id);
        setActiveProject(project);
      })
      .catch((cause: unknown) => {
        if (!controller.signal.aborted) {
          setSelectorError(getUserMessage(cause, "Projects could not be loaded."));
          setSelectorProjects([]);
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setSelectorLoading(false);
      });
    return () => controller.abort();
  }, [connected, organization?.id]);

  useEffect(() => {
    const project = selectorProjects.find((item) => item.id === selectedProjectId);
    if (!connected || !project) {
      setSelectorEnvironments([]);
      return;
    }
    const controller = new AbortController();
    setSelectorLoading(true);
    setSelectorError("");
    apiData<HeaderEnvironment[]>(
      `/api/v1/projects/${encodeURIComponent(project.id)}/environments`,
      { signal: controller.signal },
    )
      .then((result) => {
        if (!Array.isArray(result)) throw new Error("The environments API returned an invalid list.");
        if (controller.signal.aborted) return;
        setSelectorEnvironments(result);
        const savedEnvironmentId = readActiveEnvironmentId(project.id);
        const environment = result.find((item) => item.id === savedEnvironmentId)
          ?? result.find((item) => item.type === "SANDBOX" && item.status === "ACTIVE")
          ?? result.find((item) => item.status === "ACTIVE")
          ?? result[0];
        if (!environment) {
          setSelectedEnvironmentId("");
          return;
        }
        setSelectedEnvironmentId(environment.id);
        setActiveEnvironment(project, environment);
      })
      .catch((cause: unknown) => {
        if (!controller.signal.aborted) {
          setSelectorError(getUserMessage(cause, "Project environments could not be loaded."));
          setSelectorEnvironments([]);
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setSelectorLoading(false);
      });
    return () => controller.abort();
  }, [connected, selectorProjects, selectedProjectId]);

  useEffect(() => {
    function syncActiveContext() {
      const context = readActiveEnvironmentContext();
      setSelectedProjectId(context.projectId);
      setSelectedEnvironmentId(context.environmentId);
    }
    window.addEventListener("pesaguard:active-context-changed", syncActiveContext);
    window.addEventListener("pesaguard:environment-selected", syncActiveContext);
    return () => {
      window.removeEventListener("pesaguard:active-context-changed", syncActiveContext);
      window.removeEventListener("pesaguard:environment-selected", syncActiveContext);
    };
  }, []);

  useEffect(() => {
    const html = document.documentElement;
    const body = document.body;
    const previousHtmlOverflow = html.style.overflow;
    const previousHtmlScrollBehavior = html.style.scrollBehavior;
    const previousBodyOverflow = body.style.overflow;
    const previousBodyPosition = body.style.position;
    const previousBodyTop = body.style.top;
    const previousBodyLeft = body.style.left;
    const previousBodyRight = body.style.right;
    const previousBodyWidth = body.style.width;
    const previousOverscrollBehavior = body.style.overscrollBehavior;
    let locked = false;
    let lockedScrollY = 0;

    function restoreScrollLock() {
      html.style.overflow = previousHtmlOverflow;
      body.style.overflow = previousBodyOverflow;
      body.style.position = previousBodyPosition;
      body.style.top = previousBodyTop;
      body.style.left = previousBodyLeft;
      body.style.right = previousBodyRight;
      body.style.width = previousBodyWidth;
      body.style.overscrollBehavior = previousOverscrollBehavior;
      html.style.scrollBehavior = "auto";
      window.scrollTo(0, lockedScrollY);
      html.style.scrollBehavior = previousHtmlScrollBehavior;
    }

    function syncModalScrollLock() {
      const hasModal = body.querySelector('[role="dialog"][aria-modal="true"], [role="alertdialog"][aria-modal="true"]') !== null;
      if (hasModal === locked) return;
      locked = hasModal;
      if (locked) {
        lockedScrollY = window.scrollY;
        html.style.overflow = "hidden";
        body.style.overflow = "hidden";
        body.style.position = "fixed";
        body.style.top = `-${lockedScrollY}px`;
        body.style.left = "0";
        body.style.right = "0";
        body.style.width = "100%";
        body.style.overscrollBehavior = "none";
      } else {
        restoreScrollLock();
      }
    }

    const observer = new MutationObserver(syncModalScrollLock);
    observer.observe(body, {
      attributes: true,
      attributeFilter: ["aria-modal", "role"],
      childList: true,
      subtree: true,
    });
    syncModalScrollLock();

    return () => {
      observer.disconnect();
      if (locked) restoreScrollLock();
    };
  }, []);

  useEffect(() => {
    if (!connected) {
      setNotificationUnreadCount(null);
      setNotificationItems([]);
      setSelectedNotification(null);
      setNotificationError("");
      return;
    }
    let active = true;
    let loading = false;
    const loadNotifications = () => {
      if (!active || loading || document.visibilityState === "hidden") return;
      loading = true;
      void apiData<NotificationInbox>("/api/v1/notifications?unreadOnly=true&page=0&size=100")
        .then((inbox) => {
          if (!Array.isArray(inbox.items) || typeof inbox.unreadCount !== "number"
            || inbox.items.some((item) => typeof item.id !== "string"
              || typeof item.subject !== "string" || typeof item.body !== "string"
              || typeof item.category !== "string" || typeof item.createdAt !== "string")) {
            throw new Error("The notifications API returned an invalid inbox response.");
          }
          if (active) {
            setNotificationItems(inbox.items);
            setNotificationUnreadCount(inbox.unreadCount);
            setNotificationError("");
          }
        })
        .catch((error: unknown) => {
          if (active) {
            setNotificationUnreadCount(null);
            setNotificationItems([]);
            setNotificationError(getUserMessage(error, "Could not load notifications."));
          }
        })
        .finally(() => { loading = false; });
    };
    const refreshWhenVisible = () => {
      if (document.visibilityState === "visible") loadNotifications();
    };
    const refreshAfterInboxChange = () => loadNotifications();
    loadNotifications();
    const timer = window.setInterval(loadNotifications, 30_000);
    document.addEventListener("visibilitychange", refreshWhenVisible);
    window.addEventListener("pesaguard:notifications-updated", refreshAfterInboxChange);
    return () => {
      active = false;
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", refreshWhenVisible);
      window.removeEventListener("pesaguard:notifications-updated", refreshAfterInboxChange);
    };
  }, [connected]);

  useEffect(() => {
    if (!noticeOpen) return;
    function closePopover(event: MouseEvent | KeyboardEvent) {
      if (event instanceof KeyboardEvent && event.key === "Escape") {
        setNoticeOpen(false);
        setSelectedNotification(null);
      } else if (event instanceof MouseEvent
        && event.target instanceof Node
        && !document.querySelector(".notice-wrap")?.contains(event.target)) {
        setNoticeOpen(false);
        setSelectedNotification(null);
      }
    }
    document.addEventListener("mousedown", closePopover);
    document.addEventListener("keydown", closePopover);
    return () => {
      document.removeEventListener("mousedown", closePopover);
      document.removeEventListener("keydown", closePopover);
    };
  }, [noticeOpen]);

  async function markNotificationRead(item: NotificationItem) {
    if (notificationBusy) return;
    setNotificationBusy(true);
    setNotificationError("");
    try {
      await apiData<NotificationItem>(`/api/v1/notifications/${encodeURIComponent(item.id)}/read`, {
        method: "POST",
      });
      setNotificationItems((current) => current.filter((currentItem) => currentItem.id !== item.id));
      setNotificationUnreadCount((count) => count === null ? null : Math.max(0, count - 1));
      setSelectedNotification(null);
      window.dispatchEvent(new Event("pesaguard:notifications-updated"));
    } catch (error) {
      setNotificationError(getUserMessage(error, "The notification could not be removed."));
    } finally {
      setNotificationBusy(false);
    }
  }

  useEffect(() => {
    if (navigationDrawer !== "changelog") return;
    let active = true;
    setChangelogLoading(true);
    setChangelogError("");
    apiData<ChangelogEntry[]>("/api/v1/changelog", { anonymous: true })
      .then((entries) => { if (active) setChangelogEntries(entries); })
      .catch((cause: unknown) => {
        if (active) setChangelogError(getUserMessage(cause, "Unable to load product updates."));
      })
      .finally(() => { if (active) setChangelogLoading(false); });
    return () => { active = false; };
  }, [navigationDrawer, changelogReload]);

  function changeActiveProject(projectId: string) {
    const project = selectorProjects.find((item) => item.id === projectId);
    if (!project) return;
    setSelectedProjectId(project.id);
    setSelectedEnvironmentId("");
    setSelectorEnvironments([]);
    setActiveProject(project);
    showToast({ kind: "success", title: "Project changed", message: `Active project: ${project.name}` });
  }

  function changeActiveEnvironment(environmentId: string) {
    const project = selectorProjects.find((item) => item.id === selectedProjectId);
    const environment = selectorEnvironments.find((item) => item.id === environmentId);
    if (!project || !environment) return;
    setSelectedEnvironmentId(environment.id);
    setActiveEnvironment(project, environment);
    showToast({ kind: "success", title: "Environment changed", message: `Active environment: ${environment.name}` });
  }

  useEffect(() => {
    function onKeyDown(event: globalThis.KeyboardEvent) {
      const target = event.target;
      const isTyping = target instanceof HTMLElement
        && (target.isContentEditable || ["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName));
      if (event.key === "?" && !isTyping && !paletteOpen) {
        event.preventDefault();
        setShortcutHelpOpen(true);
        setShortcutHint("");
        return;
      }
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        setPaletteOpen((open) => !open);
        setQuery("");
        setSelectedIndex(0);
        setShortcutHint("");
        return;
      }
      if (event.key === "Escape") {
        setPaletteOpen(false);
        setShortcutHelpOpen(false);
        setSidebarOpen(false);
        setNoticeOpen(false);
        setNavigationDrawer(null);
        setShortcutHint("");
        return;
      }

      if (isTyping || paletteOpen || event.altKey || event.ctrlKey || event.metaKey) return;

      if (gSequenceTimer.current !== null) {
        window.clearTimeout(gSequenceTimer.current);
        gSequenceTimer.current = null;
        const page = shortcutPages[event.key.toLowerCase()];
        if (page) {
          event.preventDefault();
          onNavigate(page);
          setShortcutHint("");
          return;
        }
        setShortcutHint("");
      }

      if (event.key.toLowerCase() === "g") {
        event.preventDefault();
        setShortcutHint("G · choose D, P, A, W, S, or L");
        gSequenceTimer.current = window.setTimeout(() => {
          gSequenceTimer.current = null;
          setShortcutHint("");
        }, 1200);
      }
    }

    window.addEventListener("keydown", onKeyDown);
    return () => {
      window.removeEventListener("keydown", onKeyDown);
      if (gSequenceTimer.current !== null) window.clearTimeout(gSequenceTimer.current);
    };
  }, [onNavigate, paletteOpen]);

  const endpointEntries = useMemo<CommandEntry[]>(() =>
    apiEndpointSearchIndex.map((endpoint) => ({
      id: `endpoint-${endpoint.method}-${endpoint.path}`,
      label: `${endpoint.method} · ${endpoint.title}`,
      detail: `${endpoint.path} · example endpoint`,
      keywords: ["api", "endpoint", endpoint.path, endpoint.title, endpoint.method],
      group: "Endpoints",
      icon: Network,
      action: { kind: "navigate", page: "api-explorer", intent: `${endpoint.method} ${endpoint.path}` },
      previewOnly: true,
    })),
  []);

  useEffect(() => {
    const search = query.trim();
    if (!connected || search.length < 2) {
      setResourceEntries([]);
      return;
    }
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      void apiData<WorkspaceSearchResult[]>(`/api/v1/search?q=${encodeURIComponent(search)}`, { signal: controller.signal })
        .then((results) => {
          if (!Array.isArray(results)) return;
          setResourceEntries(results.map((result) => ({
            id: `resource-${result.type.toLowerCase()}-${result.id}`,
            label: result.label,
            detail: `${result.type.replaceAll("_", " ")} · ${result.detail}`,
            keywords: [result.type.toLowerCase(), result.detail, result.projectId ?? ""],
            group: "Resources",
            icon: result.type === "PROJECT" ? FolderKanban : result.type === "ENVIRONMENT" ? Globe : result.type === "API_KEY" ? KeyRound : result.type === "WEBHOOK" ? Webhook : Activity,
            action: result.type === "LOG"
              ? { kind: "navigate", page: "logs", intent: `open-log:${result.id}` }
              : { kind: "navigate", page: result.type === "PROJECT" ? "projects" : result.type === "ENVIRONMENT" ? "environments" : result.type === "API_KEY" ? "api-keys" : "webhooks" },
          })));
        })
        .catch(() => { if (!controller.signal.aborted) setResourceEntries([]); });
    }, 180);
    return () => { window.clearTimeout(timer); controller.abort(); };
  }, [connected, query]);

  const filteredEntries = useMemo(() => {
    const search = query.trim().toLowerCase();
    const entries = [...commandEntries, ...endpointEntries, ...resourceEntries];
    if (!search) {
      const pages = commandEntries.filter((entry) => entry.group === "Pages");
      const usageByPage = new Map(pageUsage.map((usage) => [usage.page, usage]));
      const recentPages = pageUsage
        .slice()
        .sort((left, right) => right.lastVisited - left.lastVisited)
        .slice(0, 4)
        .map((usage) => usage.page);
      const recent = recentPages
        .map((page) => pages.find((entry) => entry.action.kind === "navigate" && entry.action.page === page))
        .filter((entry): entry is CommandEntry => Boolean(entry))
        .map((entry) => ({ ...entry, group: "Recent" as const }));
      const recentPagesSet = new Set(recentPages);
      const suggested = pages
        .filter((entry) => entry.action.kind !== "navigate" || !recentPagesSet.has(entry.action.page))
        .map((entry) => {
          const page = entry.action.kind === "navigate" ? entry.action.page : null;
          const usage = page ? usageByPage.get(page) : undefined;
          const ageInDays = usage ? Math.max(0, (Date.now() - usage.lastVisited) / 86_400_000) : 30;
          const score = usage ? usage.visits * 3 + 10 / (1 + ageInDays) : 0;
          return { entry, score };
        })
        .sort((left, right) => right.score - left.score)
        .slice(0, 5)
        .map(({ entry }) => ({ ...entry, group: "Suggested" as const }));
      return [
        ...commandEntries.filter((entry) => entry.group === "Commands"),
        ...recent,
        ...suggested,
        ...commandEntries.filter((entry) => entry.group === "Help"),
      ];
    }
    const usageByPage = new Map(pageUsage.map((usage) => [usage.page, usage]));
    return entries
      .map((entry) => {
        const page = entry.action.kind === "navigate" ? entry.action.page : null;
        const score = recommendationScore(entry, search, page ? usageByPage.get(page) : undefined);
        return { entry, score };
      })
      .filter(({ score }) => score > 0)
      .sort((left, right) => right.score - left.score)
      .slice(0, 16)
      .map(({ entry }) => ({
        ...entry,
        group: entry.group === "Commands" ? "Commands" as const : "Suggested" as const,
      }));
  }, [endpointEntries, pageUsage, query, resourceEntries]);

  const entryGroups = useMemo(() => {
    const groups: CommandGroup[] = ["Commands", "Recent", "Suggested", "Pages", "Resources", "Endpoints", "Help"];
    return groups.map((group) => ({
      group,
      entries: filteredEntries.filter((entry) => entry.group === group),
    })).filter(({ entries }) => entries.length > 0);
  }, [filteredEntries]);

  useEffect(() => {
    setSelectedIndex(0);
  }, [query, paletteOpen]);

  function navigate(page: PageId) {
    onNavigate(page);
    setSidebarOpen(false);
    setPaletteOpen(false);
    setNoticeOpen(false);
    setQuery("");
  }

  function toggleSidebarCollapsed() {
    setSidebarCollapsed((collapsed) => {
      const next = !collapsed;
      try {
        window.localStorage.setItem(sidebarStorageKey, String(next));
      } catch {
        // Keep the toggle usable for this visit if browser storage is unavailable.
      }
      return next;
    });
  }

  function openNavigationTarget(target: (typeof navigationItems)[number]["target"]) {
    if (target.kind === "page") {
      navigate(target.page);
      setNavigationDrawer(null);
      return;
    }
    if (target.kind === "external") {
      window.open(target.href, "_blank", "noopener,noreferrer");
      setSidebarOpen(false);
      return;
    }
    setNavigationDrawer(target.drawer);
    setSidebarOpen(false);
  }

  function runCommand(entry: CommandEntry) {
    if (entry.action.kind === "external") {
      window.open(entry.action.href, "_blank", "noopener,noreferrer");
      setPaletteOpen(false);
      setQuery("");
      return;
    }

    const logIntent = entry.action.intent?.startsWith("open-log:") ? entry.action.intent.slice("open-log:".length) : null;
    if (entry.action.intent && !logIntent) {
      window.sessionStorage.setItem("pesaguard.command.intent", entry.action.intent);
    }
    navigate(entry.action.page);
    if (logIntent) window.dispatchEvent(new CustomEvent("pesaguard:open-log", { detail: logIntent }));
    else if (entry.action.intent) window.dispatchEvent(new Event("pesaguard:command-intent"));
  }

  function onPaletteKeyDown(event: ReactKeyboardEvent<HTMLInputElement>) {
    if (event.key === "ArrowDown") {
      event.preventDefault();
      setSelectedIndex((index) => Math.min(index + 1, filteredEntries.length - 1));
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      setSelectedIndex((index) => Math.max(0, index - 1));
    } else if (event.key === "Enter") {
      event.preventDefault();
      const entry = filteredEntries[selectedIndex];
      if (entry) runCommand(entry);
    }
  }

  const sessionTimeoutVisible = connected
    && sessionRemainingMs !== null
    && sessionRemainingMs === 0;

  async function keepSessionActive() {
    setSessionExtending(true);
    setSessionExtendError("");
    markSessionActivity(true);
    try {
      await refreshSession();
      showToast({ kind: "success", title: "Session extended", message: "You can continue working." });
    } catch (error) {
      idleExpiredRef.current = true;
      setSessionRemainingMs(0);
      setSessionExtendError(getUserMessage(error, "The session could not be extended."));
    } finally {
      setSessionExtending(false);
    }
  }

  return (
    <div className={`app-shell${sidebarCollapsed ? " app-shell--sidebar-collapsed" : ""}`}>
      {sidebarOpen && (
        <button
          type="button"
          className="mobile-scrim"
          aria-label="Close navigation"
          onClick={() => setSidebarOpen(false)}
        />
      )}
      <aside id="developer-sidebar" className={`sidebar${sidebarOpen ? " sidebar--open" : ""}`} aria-label="Developer navigation">
        <div className="sidebar-brand-row">
          <button className="brand-lockup" type="button" onClick={() => navigate("overview")} aria-label="Open workspace overview">
            <img src="/pesaguard-brand-mark.svg" alt="" width="30" height="32" />
            <span className="brand-name">Pesa<span>Guard</span></span>
          </button>
          <button
            className="sidebar-collapse-button"
            type="button"
            aria-label={sidebarCollapsed ? "Expand sidebar" : "Collapse sidebar"}
            aria-pressed={sidebarCollapsed}
            title={sidebarCollapsed ? "Expand sidebar" : "Collapse sidebar"}
            onClick={toggleSidebarCollapsed}
          >
            {sidebarCollapsed ? <PanelLeftOpen size={17} aria-hidden="true" /> : <PanelLeftClose size={17} aria-hidden="true" />}
          </button>
        </div>

        <div className="workspace-switcher-wrap">
          <div className="workspace-switcher" aria-label="Personal account" title={sidebarCollapsed ? profileName : undefined}>
            <span className="workspace-avatar">{profileName.slice(0, 1).toUpperCase()}</span>
            <span className="workspace-copy">
              <span className="workspace-label">Personal account</span>
              <strong>{profileName}</strong>
            </span>
          </div>
        </div>

        <nav className="side-nav" ref={sideNavRef} aria-label="Main navigation">
          {navigationIndicator && <span className="side-nav-active-indicator" style={navigationIndicator} aria-hidden="true" />}
          {(["Overview", "Build", "Monitor", "Resources"] as const).map((group) => (
            <section className="nav-section" key={group} aria-label={group}>
              <p className="nav-section-title">{group}</p>
              {navigationItems
                .filter((item) => item.group === group)
                .filter((item) => item.target.kind !== "page" || isFeatureEnabled(features, featureForPage(item.target.page)))
                .filter((item) => {
                  if (!connected || !permissionsLoaded || item.target.kind !== "page") return true;
                  const page = item.target.page;
                  const required = page === "projects" ? "project:read"
                      : page === "environments" ? "environment:read"
                        : page === "api-keys" ? "credential:read"
                          : page === "webhooks" ? "webhook:read"
                            : page === "usage" ? "usage:read"
                              : page === "activity" ? "audit:read"
                                : page === "security-center" ? "security:read" : null;
                  if (!required) return true;
                  if (page === "api-keys") return hasPermission(required) || hasPermission("credential:create");
                  if (page === "webhooks") return hasPermission(required) || hasPermission("webhook:create");
                  return hasPermission(required);
                })
                .map(({ id, label, icon: Icon, target }) => {
                  const selected = target.kind === "page" && activePage === target.page;
                  return target.kind === "external" ? (
                    <a className="nav-item" key={id} href={target.href} target="_blank" rel="noreferrer" aria-label={label} title={sidebarCollapsed ? label : undefined}>
                      <Icon size={17} strokeWidth={1.8} aria-hidden="true" />
                      <span>{label}</span>
                      <ExternalLink className="nav-external-indicator" size={12} aria-hidden="true" />
                    </a>
                  ) : (
                    <button
                      className={`nav-item${selected || (target.kind === "drawer" && navigationDrawer === target.drawer) ? " nav-item--active" : ""}`}
                      key={id}
                      onClick={() => openNavigationTarget(target)}
                      aria-current={selected ? "page" : undefined}
                      aria-expanded={target.kind === "drawer" ? navigationDrawer === target.drawer : undefined}
                      aria-label={label}
                      title={sidebarCollapsed ? label : undefined}
                    >
                      <Icon size={17} strokeWidth={1.8} aria-hidden="true" />
                      <span>{label}</span>
                    </button>
                  );
                })}
            </section>
          ))}
        </nav>

        <div className="sidebar-bottom">
          <div className="sidebar-status">
            <span className={`status-dot ${connected ? "status-dot--ok" : "status-dot--muted"}`} />
            <span>API service</span>
            <strong>{connected ? "Connected" : "Not connected"}</strong>
          </div>
          <div className="profile-menu-wrap" ref={profileMenuRef}>
            <button className="profile-chip" type="button" aria-haspopup="menu" aria-expanded={profileMenuOpen} aria-label={`Developer profile: ${profileName}`} title={sidebarCollapsed ? profileName : undefined} onClick={() => setProfileMenuOpen((open) => !open)}>
              <span className="profile-avatar">{profileName.slice(0, 2).toUpperCase()}</span>
              <span className="profile-copy">
                <strong>{profileName}</strong>
                <small>{connected ? "Authenticated" : "Developer preview"}</small>
              </span>
              <ChevronDown size={14} aria-hidden="true" />
            </button>
            {profileMenuOpen && <div className="profile-menu" role="menu" aria-label="Developer profile">
              <div className="profile-menu-identity">
                <span className="profile-avatar">{profileName.slice(0, 2).toUpperCase()}</span>
                <span><strong>{profileName}</strong><small>{user?.email}</small></span>
              </div>
              <button type="button" role="menuitem" onClick={() => { setProfileMenuOpen(false); onNavigate("account-settings"); }}><UserRound size={15} />Account settings</button>
              <button type="button" role="menuitem" onClick={() => { setProfileMenuOpen(false); setShortcutHelpOpen(true); }}><CircleHelp size={15} />Keyboard shortcuts</button>
              <button type="button" role="menuitem" disabled aria-disabled="true">
                <Moon size={15} /><span>Dark theme · Always on</span>
              </button>
              <button type="button" role="menuitem" className="profile-menu-logout" disabled={!connected} onClick={() => { setProfileMenuOpen(false); void logout(); }}><LogOut size={15} />Log out</button>
            </div>}
          </div>
        </div>
      </aside>

      <div className="shell-main">
        <header className="topbar">
          <button
            className="icon-button mobile-menu-button"
            aria-label="Open navigation"
            aria-expanded={sidebarOpen}
            aria-controls="developer-sidebar"
            onClick={() => setSidebarOpen(true)}
          >
            <Menu size={19} />
          </button>
          <div className="breadcrumbs">
            <strong>{activeNavigationLabel}</strong>
          </div>
          <div className="topbar-actions">
            <button className="search-trigger" aria-label="Open command center (Command+K or Ctrl+K)" onClick={() => { setPaletteOpen(true); setQuery(""); }}>
              <Search size={15} aria-hidden="true" />
              <span>Command center</span>
              <kbd><Command size={11} aria-hidden="true" /> K <span className="shortcut-separator">/</span> Ctrl K</kbd>
            </button>
            <div className={`environment-switcher${selectorEnvironments.find((environment) => environment.id === selectedEnvironmentId)?.type === "PRODUCTION" ? " environment-switcher--production" : " environment-switcher--nonproduction"}`}>
              <label className="visually-hidden" htmlFor="active-project">Active project</label>
              <select
                id="active-project"
                aria-label="Active project"
                value={selectedProjectId}
                disabled={!connected || selectorLoading || selectorProjects.length === 0}
                onChange={(event) => changeActiveProject(event.target.value)}
              >
                {selectorProjects.length === 0 && <option value="">{connected ? "No projects" : "Sign in"}</option>}
                {selectorProjects.map((project) => <option key={project.id} value={project.id}>{project.name}</option>)}
              </select>
              <span className="environment-switcher-divider" aria-hidden="true" />
              <label className="visually-hidden" htmlFor="active-environment">Active environment</label>
              <select
                id="active-environment"
                aria-label={`Active environment${selectorEnvironments.find((environment) => environment.id === selectedEnvironmentId)?.type === "PRODUCTION" ? " — live production" : ""}`}
                value={selectedEnvironmentId}
                disabled={!connected || selectorLoading || selectorEnvironments.length === 0}
                onChange={(event) => changeActiveEnvironment(event.target.value)}
              >
                {selectorEnvironments.length === 0 && <option value="">Select environment</option>}
                {selectorEnvironments.map((environment) => <option key={environment.id} value={environment.id} disabled={environment.status !== "ACTIVE"}>{environment.name}{environment.type === "PRODUCTION" ? " · LIVE" : ""}</option>)}
              </select>
              {selectorError && <span className="visually-hidden" role="status">{selectorError}</span>}
            </div>
            <div className="notice-wrap">
              <button
                className="icon-button notification-button"
                aria-expanded={noticeOpen}
                aria-label={notificationUnreadCount ? `Notifications, ${notificationUnreadCount} unread` : "Notifications"}
                onClick={() => {
                  setSelectedNotification(null);
                  setNoticeOpen((open) => !open);
                }}
              >
                <Bell size={17} />
                {notificationUnreadCount !== null && notificationUnreadCount > 0 && (
                  <span className="notification-count-badge" aria-hidden="true">
                    {notificationUnreadCount > 99 ? "99+" : notificationUnreadCount}
                  </span>
                )}
              </button>
              {noticeOpen && (
                <div className="notification-popover" role="dialog" aria-label="Notifications">
                  <div className="notification-popover-heading">
                    <div><strong>Notifications</strong><span>{notificationUnreadCount ?? 0} unread</span></div>
                    <button className="icon-button" type="button" aria-label="Close notifications" onClick={() => setNoticeOpen(false)}><X size={15} /></button>
                  </div>
                  {notificationError && <p className="notification-popover-error" role="alert">{notificationError}</p>}
                  {selectedNotification ? (
                    <article className="notification-popover-detail">
                      <button className="notification-popover-back" type="button" onClick={() => setSelectedNotification(null)}>← All notifications</button>
                      <span className="notification-popover-category">{selectedNotification.category.replaceAll("_", " ")}</span>
                      <strong>{selectedNotification.subject}</strong>
                      <time dateTime={selectedNotification.createdAt}>{new Date(selectedNotification.createdAt).toLocaleString()}</time>
                      <p>{selectedNotification.body}</p>
                      <button
                        className="button button--primary"
                        type="button"
                        disabled={notificationBusy}
                        onClick={() => void markNotificationRead(selectedNotification)}
                      >
                        {notificationBusy ? "Removing…" : "Mark as read"}
                      </button>
                    </article>
                  ) : !connected ? (
                    <p className="notification-popover-empty">Sign in to load account notifications.</p>
                  ) : notificationUnreadCount === null ? (
                    <p className="notification-popover-empty">Loading notifications…</p>
                  ) : notificationItems.length === 0 ? (
                    <p className="notification-popover-empty">You’re all caught up.</p>
                  ) : (
                    <div className="notification-popover-list">
                      {notificationItems.map((item) => (
                        <button
                          className="notification-popover-item"
                          key={item.id}
                          type="button"
                          onClick={() => setSelectedNotification(item)}
                        >
                          <span className="notification-popover-item-dot" aria-hidden="true" />
                          <span><strong>{item.subject}</strong><small>{item.body}</small><time dateTime={item.createdAt}>{new Date(item.createdAt).toLocaleString()}</time></span>
                        </button>
                      ))}
                      {notificationUnreadCount > notificationItems.length && (
                        <p className="notification-popover-more">Showing the newest {notificationItems.length} unread notifications.</p>
                      )}
                    </div>
                  )}
                </div>
              )}
            </div>
            <button className="help-button" type="button" onClick={() => navigate("support-hub")}>
              <CircleHelp size={16} aria-hidden="true" />
              <span>Help</span>
            </button>
          </div>
        </header>

        <main className="main-content" id="main-content">
          {children}
        </main>
      </div>

      {shortcutHint && <div className="shortcut-hint" role="status">{shortcutHint}</div>}

      {welcomeVisible && (
        <section className="welcome-toast" role="status" aria-live="polite" aria-label="Welcome to PesaGuard">
          <span className="welcome-toast__icon" aria-hidden="true"><Sparkles size={20} /></span>
          <div className="welcome-toast__copy">
            <span className="welcome-toast__eyebrow">WELCOME TO PESAGUARD</span>
            <strong>Welcome, {welcomeName}</strong>
            <span>Your developer workspace is ready. Let's build with confidence.</span>
          </div>
          <button className="welcome-toast__dismiss" type="button" aria-label="Dismiss welcome message" onClick={() => setWelcomeVisible(false)}>
            <X size={16} />
          </button>
          <span className="welcome-toast__progress" aria-hidden="true" />
        </section>
      )}

      {shortcutHelpOpen && !sessionTimeoutVisible && (
        <div className="dialog-backdrop shortcut-help-backdrop" onMouseDown={(event) => {
          if (event.target === event.currentTarget) setShortcutHelpOpen(false);
        }}>
          <section className="shortcut-help-dialog" role="dialog" aria-modal="true" aria-labelledby="shortcut-help-title">
            <div className="shortcut-help-heading">
              <div><span className="section-eyebrow">QUICK NAVIGATION</span><h2 id="shortcut-help-title">Keyboard shortcuts</h2></div>
              <button className="icon-button" type="button" aria-label="Close keyboard shortcuts" onClick={() => setShortcutHelpOpen(false)}><X size={17} /></button>
            </div>
            <dl className="shortcut-help-list">
              <div><dt>Open global search</dt><dd><kbd>Ctrl</kbd> <span>+</span> <kbd>K</kbd><small>or</small><kbd>⌘</kbd><span>+</span><kbd>K</kbd></dd></div>
              <div><dt>Show this shortcut guide</dt><dd><kbd>?</kbd></dd></div>
              <div><dt>Go to a page</dt><dd><kbd>G</kbd><small>then</small><kbd>D</kbd>, <kbd>P</kbd>, <kbd>A</kbd>, <kbd>W</kbd>, <kbd>S</kbd>, <small>or</small> <kbd>L</kbd></dd></div>
              <div><dt>Close the current menu or dialog</dt><dd><kbd>Esc</kbd></dd></div>
              <div><dt>Move through search results</dt><dd><kbd>↑</kbd> <span>/</span> <kbd>↓</kbd> <small>then</small> <kbd>Enter</kbd></dd></div>
            </dl>
            <div className="shortcut-help-links">
              <a href={externalLinks.docs} target="_blank" rel="noreferrer"><BookOpenText size={15} />Documentation<ExternalLink size={12} /></a>
              <a href={externalLinks.status} target="_blank" rel="noreferrer"><Activity size={15} />Service status<ExternalLink size={12} /></a>
              <button type="button" onClick={() => { setShortcutHelpOpen(false); onNavigate("support-hub"); }}><LifeBuoy size={15} />Support<ArrowRight size={12} /></button>
            </div>
          </section>
        </div>
      )}

      {sessionTimeoutVisible && (
        <div className="dialog-backdrop session-timeout-backdrop">
          <section className="session-timeout-dialog" role="alertdialog" aria-modal="true" aria-labelledby="session-timeout-title" aria-describedby="session-timeout-copy">
            <span className="session-timeout-icon"><Clock size={21} aria-hidden="true" /></span>
            <span className="section-eyebrow">SESSION SECURITY</span>
            <h2 id="session-timeout-title">Your session has expired</h2>
            <p id="session-timeout-copy">
              You were inactive for 30 minutes. Sign in again, or stay signed in to continue working.
            </p>
            {sessionExtendError && <p className="form-error" role="alert">{sessionExtendError}</p>}
            <div className="session-timeout-actions">
              <button className="button button--secondary" type="button" onClick={() => void logout()}>Sign out</button>
              <button className="button primary" type="button" onClick={() => void keepSessionActive()} disabled={sessionExtending}>
                {sessionExtending ? "Extending…" : "Stay signed in"} <RotateCw size={14} aria-hidden="true" />
              </button>
            </div>
          </section>
        </div>
      )}

      {navigationDrawer === "changelog" && (
        <div className="navigation-drawer-backdrop" onMouseDown={(event) => {
          if (event.target === event.currentTarget) setNavigationDrawer(null);
        }}>
          <aside className="navigation-drawer" role="dialog" aria-modal="true" aria-labelledby="navigation-drawer-title">
            <div className="navigation-drawer-heading">
              <div>
                <span className="section-eyebrow">DEVELOPER WORKSPACE</span>
                <h2 id="navigation-drawer-title">
                  Changelog
                </h2>
                <p>
                  Published product changes from the PesaGuard platform.
                </p>
              </div>
              <button className="icon-button" type="button" aria-label="Close navigation drawer" onClick={() => setNavigationDrawer(null)}><X size={17} /></button>
            </div>
            {(
              <div className="navigation-drawer-changelog">
                {changelogLoading ? <p role="status">Loading published updates…</p>
                  : changelogError ? (
                    <div role="alert">
                      <p className="form-error">{changelogError}</p>
                      <button className="button button--secondary" type="button"
                        onClick={() => setChangelogReload((revision) => revision + 1)}>
                        Try again <RotateCw size={14} aria-hidden="true" />
                      </button>
                    </div>
                  )
                    : changelogEntries.length === 0 ? <p className="navigation-drawer-notice">No published product updates are available yet. Drafts remain private until an operator publishes them.</p>
                      : changelogEntries.map((entry) => (
                        <article className="navigation-drawer-changelog-entry" key={entry.id}>
                          <div><span>{entry.version}</span><span>{entry.category.toLowerCase()}</span></div>
                          <h3>{entry.title}</h3>
                          <p>{entry.body}</p>
                          <time dateTime={entry.publishedAt}>{new Date(entry.publishedAt).toLocaleDateString()}</time>
                        </article>
                      ))}
                <div className="navigation-drawer-changelog-links">
                  <a href={`${externalLinks.docs}/changelog/`} target="_blank" rel="noreferrer">Documentation changelog<ExternalLink size={13} /></a>
                  <a href={externalLinks.status} target="_blank" rel="noreferrer">Service status<ExternalLink size={13} /></a>
                </div>
              </div>
            )}
          </aside>
        </div>
      )}

      {paletteOpen && (
        <div className="dialog-backdrop dialog-backdrop--command" onMouseDown={(event) => {
          if (event.target === event.currentTarget) setPaletteOpen(false);
        }}>
          <section className="command-palette" role="dialog" aria-modal="true" aria-label="Global command center">
            <div className="command-search">
              <Search size={18} aria-hidden="true" />
              <input
                autoFocus
                aria-label="Search platform resources and commands"
                aria-activedescendant={filteredEntries[selectedIndex] ? `command-${filteredEntries[selectedIndex].id}` : undefined}
                placeholder="Search APIs, projects, keys, logs, settings…"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                onKeyDown={onPaletteKeyDown}
              />
              <button className="icon-button" aria-label="Close command center" onClick={() => setPaletteOpen(false)}>
                <X size={16} />
              </button>
            </div>
            <div className="command-results">
              {entryGroups.map(({ group, entries }) => (
                <section className="command-group" aria-label={group} key={group}>
                  <span className="command-hint">{group}</span>
                  {entries.map((entry) => {
                    const Icon = entry.icon;
                    const index = filteredEntries.findIndex((item) => item.id === entry.id);
                    return (
                      <button
                        className={`command-result${selectedIndex === index ? " command-result--selected" : ""}${entry.previewOnly ? " command-result--preview" : ""}`}
                        id={`command-${entry.id}`}
                        key={entry.id}
                        role="option"
                        aria-selected={selectedIndex === index}
                        onMouseEnter={() => setSelectedIndex(index)}
                        onClick={() => runCommand(entry)}
                      >
                        <Icon size={16} aria-hidden="true" />
                        <span className="command-result-copy"><strong>{entry.label}</strong><small>{entry.detail}</small></span>
                        {entry.previewOnly && <span className="command-preview-tag">Preview</span>}
                        <ArrowRight className="command-enter" size={14} aria-hidden="true" />
                      </button>
                    );
                  })}
                </section>
              ))}
              {filteredEntries.length === 0 && <p className="command-empty">No matching resources or commands.</p>}
            </div>
          </section>
        </div>
      )}
    </div>
  );
}
