import { ValidatedForm } from "../../components/forms/ValidatedForm";
import { getUserMessage } from "../../lib/errors";
import { useEffect, useMemo, useState, type FormEvent } from "react";
import {
  ArrowLeft,
  ArrowUpRight,
  BookOpenText,
  Check,
  CircleHelp,
  FilePlus2,
  LifeBuoy,
  MessagesSquare,
  Radio,
  RefreshCw,
  Search,
  Siren,
  TicketCheck,
  X,
} from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";
import { externalLinks } from "../../app/routes";
import { useAuth } from "../../context/AuthContext";
import { apiData } from "../../lib/api";

type TicketCategory =
  | "AUTHENTICATION" | "API" | "WEBHOOKS" | "PROJECTS" | "ENVIRONMENTS"
  | "API_KEYS" | "INTEGRATIONS" | "SECURITY" | "BILLING" | "ACCOUNT"
  | "PERFORMANCE" | "INCIDENT" | "API_ISSUE" | "BUG_REPORT"
  | "ACCOUNT_ACCESS" | "PRODUCTION" | "OTHER";
type TicketPriority = "LOW" | "NORMAL" | "HIGH" | "URGENT";
type SupportTicket = {
  id: string;
  category: TicketCategory;
  subject: string;
  description: string;
  priority: TicketPriority;
  status: string;
  createdAt: string;
  updatedAt: string;
  environment?: string | null;
  endpoint?: string | null;
  httpStatus?: number | null;
  requestId?: string | null;
  deliveryId?: string | null;
  eventType?: string | null;
  authenticationMethod?: string | null;
  errorCode?: string | null;
};
type SupportArticle = {
  id: string;
  title: string;
  slug: string;
  summary: string;
  content: string;
  category: string;
  updatedAt: string;
  tags: string[];
  relatedArticleIds: string[];
  relatedDocumentationUrls: string[];
  relatedErrorCodes: string[];
  relatedApiEndpoints: string[];
};
type SearchResult = {
  type: "HELP_ARTICLE" | "SUPPORT_TICKET";
  id: string;
  title: string;
  summary: string;
  category: string;
  href: string;
};
type SupportCategoryInfo = { id: string; name: string };
type SupportHome = {
  documentationUrl: string;
  statusUrl: string;
  communityUrl: string;
  categories: SupportCategoryInfo[];
  latestArticles: SupportArticle[];
};
type ArticlePage = { articles: SupportArticle[]; page: number; pageSize: number; totalItems: number; totalPages: number };
type TicketPage = { tickets: SupportTicket[]; page: number; pageSize: number; totalItems: number; totalPages: number };
type PlatformIncident = {
  id: string;
  title: string;
  severity: string;
  status: string;
  affectedServices: string[];
  summary: string;
  startedAt: string;
  resolvedAt: string | null;
  updates: Array<{ id: string; message: string; createdAt: string }>;
};
type PlatformStatus = {
  overallStatus: string;
  components: Array<{ service: string; status: string }>;
  maintenanceMode: boolean;
  maintenanceMessage: string;
  estimatedRecoveryAt: string | null;
  activeIncidents: PlatformIncident[];
};
type Tab = "overview" | "help" | "tickets" | "status" | "announcements" | "community";
type TabInfo = { id: Tab; label: string };

const tabs: TabInfo[] = [
  { id: "overview", label: "Overview" },
  { id: "help", label: "Help Center" },
  { id: "tickets", label: "Tickets" },
  { id: "status", label: "System status" },
  { id: "announcements", label: "Announcements" },
  { id: "community", label: "Community" },
];
const categoryLabels: Record<TicketCategory, string> = {
  AUTHENTICATION: "Authentication",
  API: "API",
  WEBHOOKS: "Webhooks",
  PROJECTS: "Projects",
  ENVIRONMENTS: "Environments",
  API_KEYS: "API keys",
  INTEGRATIONS: "Integrations",
  SECURITY: "Security",
  BILLING: "Billing",
  ACCOUNT: "Account",
  PERFORMANCE: "Performance",
  INCIDENT: "Incident",
  API_ISSUE: "API issue",
  BUG_REPORT: "Bug report",
  ACCOUNT_ACCESS: "Account access",
  PRODUCTION: "Production",
  OTHER: "Other",
};
const priorityLabels: Record<TicketPriority, string> = {
  LOW: "Low",
  NORMAL: "Normal",
  HIGH: "High",
  URGENT: "Urgent",
};
const categories: TicketCategory[] = [
  "AUTHENTICATION", "API", "WEBHOOKS", "PROJECTS", "ENVIRONMENTS",
  "API_KEYS", "INTEGRATIONS", "SECURITY", "BILLING", "ACCOUNT",
  "PERFORMANCE", "INCIDENT", "OTHER",
];

function ticketTabFromPath(path: string): Tab {
  if (path.startsWith("/support/help")) return "help";
  if (path.startsWith("/support/tickets")) return "tickets";
  if (path.startsWith("/support/status")) return "status";
  if (path.startsWith("/support/announcements")) return "announcements";
  if (path.startsWith("/support/community")) return "community";
  return "overview";
}

function articleSlugFromPath(path: string): string | null {
  const match = path.match(/^\/support\/help\/([^/]+)\/?$/);
  if (!match) return null;
  try {
    return decodeURIComponent(match[1]);
  } catch {
    return null;
  }
}

function isTicket(value: unknown): value is SupportTicket {
  if (typeof value !== "object" || value === null) return false;
  const ticket = value as Partial<SupportTicket>;
  return typeof ticket.id === "string"
    && typeof ticket.subject === "string"
    && typeof ticket.description === "string"
    && typeof ticket.category === "string"
    && typeof ticket.priority === "string"
    && typeof ticket.status === "string"
    && typeof ticket.createdAt === "string"
    && typeof ticket.updatedAt === "string";
}

function isArticle(value: unknown): value is SupportArticle {
  if (typeof value !== "object" || value === null) return false;
  const article = value as Partial<SupportArticle>;
  return typeof article.id === "string"
    && typeof article.title === "string"
    && typeof article.slug === "string"
    && typeof article.summary === "string"
    && typeof article.content === "string"
    && typeof article.category === "string"
    && Array.isArray(article.tags)
    && Array.isArray(article.relatedArticleIds)
    && Array.isArray(article.relatedDocumentationUrls)
    && Array.isArray(article.relatedErrorCodes)
    && Array.isArray(article.relatedApiEndpoints);
}

function isSupportHome(value: unknown): value is SupportHome {
  if (typeof value !== "object" || value === null) return false;
  const home = value as Partial<SupportHome>;
  return typeof home.documentationUrl === "string"
    && typeof home.statusUrl === "string"
    && typeof home.communityUrl === "string"
    && Array.isArray(home.categories)
    && Array.isArray(home.latestArticles)
    && home.latestArticles.every(isArticle);
}

function formatCategory(category: string) {
  return categoryLabels[category as TicketCategory] ?? category.replaceAll("_", " ");
}

function safeDocumentationUrl(value: string) {
  try {
    const url = new URL(value);
    return url.protocol === "https:" && url.hostname === "docs.pesaguard.co.ke"
      ? url.toString()
      : externalLinks.docs;
  } catch {
    return externalLinks.docs;
  }
}

export function SupportPage() {
  const { isAuthenticated, user } = useAuth();
  const [tab, setTab] = useState<Tab>(() => ticketTabFromPath(window.location.pathname));
  const [routeVersion, setRouteVersion] = useState(0);
  const [home, setHome] = useState<SupportHome | null>(null);
  const [homeError, setHomeError] = useState("");
  const [platformStatus, setPlatformStatus] = useState<PlatformStatus | null>(null);
  const [platformStatusError, setPlatformStatusError] = useState("");
  const [articles, setArticles] = useState<SupportArticle[]>([]);
  const [articleError, setArticleError] = useState("");
  const [articleRefreshVersion, setArticleRefreshVersion] = useState(0);
  const [selectedArticle, setSelectedArticle] = useState<SupportArticle | null>(null);
  const [search, setSearch] = useState(() => new URLSearchParams(window.location.search).get("q") ?? "");
  const [searchResults, setSearchResults] = useState<SearchResult[]>([]);
  const [searchError, setSearchError] = useState("");
  const [searchLoading, setSearchLoading] = useState(false);
  const [categoryFilter, setCategoryFilter] = useState("");
  const [tickets, setTickets] = useState<SupportTicket[]>([]);
  const [focusedTicket, setFocusedTicket] = useState<SupportTicket | null>(null);
  const [selectedTicketId, setSelectedTicketId] = useState(() => new URLSearchParams(window.location.search).get("ticket") ?? "");
  const [ticketsLoading, setTicketsLoading] = useState(false);
  const [ticketsError, setTicketsError] = useState("");
  const [refreshVersion, setRefreshVersion] = useState(0);
  const [ticketPage, setTicketPage] = useState(0);
  const [ticketTotal, setTicketTotal] = useState(0);
  const [ticketTotalPages, setTicketTotalPages] = useState(0);
  const [formOpen, setFormOpen] = useState(window.location.pathname.endsWith("/new"));
  const [category, setCategory] = useState<TicketCategory>("OTHER");
  const [priority, setPriority] = useState<TicketPriority>("NORMAL");
  const [subject, setSubject] = useState("");
  const [description, setDescription] = useState("");
  const [environment, setEnvironment] = useState("");
  const [endpoint, setEndpoint] = useState("");
  const [httpStatus, setHttpStatus] = useState("");
  const [requestId, setRequestId] = useState("");
  const [deliveryId, setDeliveryId] = useState("");
  const [eventType, setEventType] = useState("");
  const [authenticationMethod, setAuthenticationMethod] = useState("");
  const [errorCode, setErrorCode] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [formMessage, setFormMessage] = useState("");
  const [feedbackMessage, setFeedbackMessage] = useState("");
  const [feedbackSubmitting, setFeedbackSubmitting] = useState(false);
  const [ticketActionId, setTicketActionId] = useState("");
  const [ticketActionError, setTicketActionError] = useState("");
  const articleSlug = useMemo(() => articleSlugFromPath(window.location.pathname), [routeVersion, tab, selectedArticle]);

  useEffect(() => {
    const syncRoute = () => {
      setTab(ticketTabFromPath(window.location.pathname));
      setSelectedArticle(null);
      setFormOpen(window.location.pathname.endsWith("/new"));
      setSelectedTicketId(new URLSearchParams(window.location.search).get("ticket") ?? "");
      setRouteVersion((current) => current + 1);
    };
    window.addEventListener("popstate", syncRoute);
    return () => window.removeEventListener("popstate", syncRoute);
  }, []);

  useEffect(() => {
    if (tab !== "status") return;
    let active = true;
    apiData<PlatformStatus>("/api/v1/status", { anonymous: true })
      .then((result) => {
        if (!Array.isArray(result.components) || !Array.isArray(result.activeIncidents)) {
          throw new Error("Status service returned an incomplete response.");
        }
        if (active) {
          setPlatformStatus(result);
          setPlatformStatusError("");
        }
      })
      .catch((error: unknown) => {
        if (active) setPlatformStatusError(getUserMessage(error, "Status service is unavailable."));
      });
    return () => { active = false; };
  }, [tab]);

  function pushSupportPath(path: string) {
    if (`${window.location.pathname}${window.location.search}` !== path) {
      window.history.pushState(null, "", path);
    }
    setRouteVersion((current) => current + 1);
  }

  useEffect(() => {
    let active = true;
    if (!selectedTicketId) {
      setFocusedTicket(null);
      return () => { active = false; };
    }
    if (!isAuthenticated) {
      if (active) setTicketsError("Sign in to view this support ticket.");
      return () => { active = false; };
    }
    apiData<SupportTicket>(`/api/v1/support/tickets/${encodeURIComponent(selectedTicketId)}`)
      .then((response) => {
        if (!isTicket(response)) throw new Error("Support service returned an invalid ticket.");
        if (active) setFocusedTicket(response);
      })
      .catch((error: unknown) => {
        if (active) setTicketsError(getUserMessage(error, "The selected ticket could not be loaded."));
      });
    return () => { active = false; };
  }, [selectedTicketId, isAuthenticated]);

  useEffect(() => {
    let active = true;
    apiData<SupportHome>("/api/v1/support")
      .then((response) => {
        if (!isSupportHome(response)) throw new Error("Support Center returned an invalid response.");
        if (active) {
          setHome(response);
          setHomeError("");
        }
      })
      .catch((error: unknown) => {
        if (active) {
          setHome(null);
          setHomeError(getUserMessage(error, "The Support Center could not be reached."));
        }
      });
    return () => { active = false; };
  }, []);

  useEffect(() => {
    let active = true;
    const loadTickets = async () => {
      setTicketsError("");
      if (!isAuthenticated) {
        if (active) {
          setTickets([]);
          setTicketTotal(0);
          setTicketTotalPages(0);
          setTicketsError("Sign in to view or submit support tickets.");
        }
        return;
      }

      setTicketsLoading(true);
      try {
        const response = await apiData<TicketPage>(`/api/v1/support/tickets?page=${ticketPage}&pageSize=20`);
        if (!Array.isArray(response.tickets) || !response.tickets.every(isTicket)) {
          throw new Error("Support response did not contain a valid ticket list.");
        }
        if (active) {
          setTickets(response.tickets);
          setTicketTotal(response.totalItems);
          setTicketTotalPages(response.totalPages);
        }
      } catch (error) {
        if (active) setTicketsError(getUserMessage(error, "Support tickets could not be loaded."));
      } finally {
        if (active) setTicketsLoading(false);
      }
    };
    void loadTickets();
    return () => { active = false; };
  }, [isAuthenticated, refreshVersion, ticketPage]);

  useEffect(() => {
    let active = true;
    const slug = articleSlugFromPath(window.location.pathname);
    if (!slug) {
      setSelectedArticle(null);
      if (tab === "help" && search.trim().length < 2) {
        setArticleError("");
        setArticles([]);
        const params = new URLSearchParams({ pageSize: "50" });
        if (categoryFilter) params.set("category", categoryFilter);
        apiData<ArticlePage>(`/api/v1/support/articles?${params.toString()}`)
          .then((response) => {
            if (!Array.isArray(response.articles) || !response.articles.every(isArticle)) {
              throw new Error("Help Center returned an invalid article list.");
            }
            if (active) {
              setArticles(response.articles);
              setArticleError("");
            }
          })
          .catch((error: unknown) => {
            if (active) {
              setArticles([]);
              setArticleError(getUserMessage(error, "The Help Center could not be reached."));
            }
          });
      }
      return () => { active = false; };
    }

    apiData<SupportArticle>(`/api/v1/support/articles/${encodeURIComponent(slug)}`)
      .then((response) => {
        if (!isArticle(response)) throw new Error("Help article returned an invalid response.");
        if (active) {
          setSelectedArticle(response);
          setArticleError("");
        }
      })
      .catch((error: unknown) => {
        if (active) {
          setSelectedArticle(null);
          setArticleError(getUserMessage(error, "The requested article could not be loaded."));
        }
      });
    return () => { active = false; };
  }, [articleSlug, tab, categoryFilter, search, articleRefreshVersion]);

  useEffect(() => {
    let active = true;
    const query = search.trim();
    if (query.length < 2) {
      setSearchResults([]);
      setSearchError("");
      setSearchLoading(false);
      return () => { active = false; };
    }
    setSearchLoading(true);
    setSearchError("");
    const timer = window.setTimeout(() => {
      const request = isAuthenticated
        ? apiData<SearchResult[]>(`/api/v1/support/search?q=${encodeURIComponent(query)}`)
            .then((response) => response)
        : apiData<ArticlePage>(`/api/v1/support/articles?q=${encodeURIComponent(query)}&pageSize=20`)
            .then((response) => response.articles.map((article): SearchResult => ({
              type: "HELP_ARTICLE",
              id: article.id,
              title: article.title,
              summary: article.summary,
              category: article.category,
              href: `/support/help/${article.slug}`,
            })));
      request.then((results) => {
        if (active) setSearchResults(results);
      }).catch((error: unknown) => {
        if (active) {
          setSearchResults([]);
          setSearchError(getUserMessage(error, "Support search is unavailable."));
        }
      }).finally(() => {
        if (active) setSearchLoading(false);
      });
    }, 250);
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [search, isAuthenticated]);

  function navigateTab(nextTab: Tab) {
    setTab(nextTab);
    setSelectedArticle(null);
    setSelectedTicketId("");
    setFocusedTicket(null);
    setFormOpen(false);
    setFormMessage("");
    const suffix = nextTab === "overview" ? "" : `/${nextTab === "help" ? "help" : nextTab === "tickets" ? "tickets" : nextTab}`;
    const nextPath = `/support${suffix}`;
    pushSupportPath(nextPath);
  }

  function openArticle(article: SupportArticle) {
    setSelectedArticle(article);
    setTab("help");
    const path = `/support/help/${encodeURIComponent(article.slug)}`;
    pushSupportPath(path);
    window.scrollTo({ top: 0, behavior: "smooth" });
  }

  function openArticleSlug(slug: string) {
    setSelectedArticle(null);
    setTab("help");
    pushSupportPath(`/support/help/${encodeURIComponent(slug)}`);
    window.scrollTo({ top: 0, behavior: "smooth" });
  }

  function openSearchResult(result: SearchResult) {
    if (result.type === "SUPPORT_TICKET") {
      setTab("tickets");
      setSelectedTicketId(result.id);
      const path = `/support/tickets?ticket=${encodeURIComponent(result.id)}`;
      pushSupportPath(path);
      window.scrollTo({ top: 0, behavior: "smooth" });
      return;
    }
    const found = [...articles, ...recentArticles].find((article) => article.id === result.id);
    if (found) {
      openArticle(found);
      return;
    }
    setTab("help");
    setSelectedArticle(null);
    pushSupportPath(result.href);
    window.scrollTo({ top: 0, behavior: "smooth" });
  }

  function startTicketForm(nextCategory: TicketCategory = "OTHER") {
    setCategory(nextCategory);
    setFormMessage("");
    setFormOpen(true);
    setTab("tickets");
    setSelectedTicketId("");
    setFocusedTicket(null);
    const path = "/support/tickets/new";
    pushSupportPath(path);
    window.setTimeout(() => document.getElementById("support-ticket-subject")?.focus(), 0);
  }

  async function submitTicket(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!isAuthenticated) {
      setFormMessage("Sign in before submitting a support request.");
      return;
    }
    setSubmitting(true);
    setFormMessage("");
    const request = {
      category,
      subject: subject.trim(),
      description: description.trim(),
      priority,
      environment: environment || null,
      endpoint: endpoint.trim() || null,
      httpStatus: httpStatus ? Number(httpStatus) : null,
      requestId: requestId.trim() || null,
      deliveryId: deliveryId.trim() || null,
      eventType: eventType.trim() || null,
      authenticationMethod: authenticationMethod.trim() || null,
      errorCode: errorCode.trim() || null,
    };
    try {
      const created = await apiData<SupportTicket>("/api/v1/support/tickets", {
        method: "POST",
        body: JSON.stringify(request),
      });
      if (!isTicket(created)) throw new Error("The support service returned an invalid ticket.");
      if (ticketPage !== 0) setTicketPage(0);
      setTickets((current) => [created, ...current].slice(0, 20));
      setTicketTotal((current) => current + 1);
      setTicketTotalPages(() => Math.ceil((ticketTotal + 1) / 20));
      setSubject("");
      setDescription("");
      setEnvironment("");
      setEndpoint("");
      setHttpStatus("");
      setRequestId("");
      setDeliveryId("");
      setEventType("");
      setAuthenticationMethod("");
      setErrorCode("");
      setPriority("NORMAL");
      setFormOpen(false);
      setFormMessage(`Support request ${created.id} submitted. Track its status below.`);
      window.history.replaceState(null, "", "/support/tickets");
      setRouteVersion((current) => current + 1);
    } catch (error) {
      console.warn("Unable to submit the support request.");
      setFormMessage(getUserMessage(error, "We couldn't create your ticket. Your message has not been lost. Try again."));
    } finally {
      setSubmitting(false);
    }
  }

  async function sendArticleFeedback(helpful: boolean) {
    if (!selectedArticle || !isAuthenticated) return;
    setFeedbackSubmitting(true);
    setFeedbackMessage("");
    try {
      await apiData<void>(`/api/v1/support/articles/${encodeURIComponent(selectedArticle.slug)}/feedback`, {
        method: "POST",
        body: JSON.stringify({ helpful }),
      });
      setFeedbackMessage("Thanks for helping us improve this article.");
    } catch (error) {
      setFeedbackMessage(getUserMessage(error, "We couldn't save your feedback. Try again."));
    } finally {
      setFeedbackSubmitting(false);
    }
  }

  async function updateTicketStatus(ticket: SupportTicket) {
    if (!isAuthenticated) {
      setTicketActionError("Sign in to update support tickets.");
      return;
    }
    const action = ticket.status === "CLOSED" ? "reopen" : "close";
    setTicketActionId(ticket.id);
    setTicketActionError("");
    try {
      const updated = await apiData<SupportTicket>(`/api/v1/support/tickets/${encodeURIComponent(ticket.id)}/${action}`, {
        method: "POST",
      });
      if (!isTicket(updated)) throw new Error("The support service returned an invalid ticket.");
      setTickets((current) => current.map((item) => item.id === ticket.id ? updated : item));
      if (focusedTicket?.id === ticket.id) setFocusedTicket(updated);
    } catch (error) {
      setTicketActionError(getUserMessage(error, `The ticket could not be ${action === "close" ? "closed" : "reopened"}.`));
    } finally {
      setTicketActionId("");
    }
  }

  const recentArticles = home?.latestArticles ?? [];
  const openTickets = tickets.filter((ticket) => !["CLOSED", "RESOLVED"].includes(ticket.status));
  const showSearchResults = search.trim().length >= 2;
  const statusUrl = home?.statusUrl ?? externalLinks.status;
  const docsUrl = home?.documentationUrl ?? externalLinks.docs;
  const communityUrl = home?.communityUrl ?? "https://github.com/Victor-Kipruto-Rop/status.pesaguard.victorkipruto.com/discussions";

  return (
    <>
      <PageHeader
        eyebrow="DEVELOPER RESOURCES"
        title="Support"
        description="One place to troubleshoot, find guidance, check service health, and get help from PesaGuard."
      />

      <nav className="support-tabs" aria-label="Support Center sections">
        {tabs.map((item) => (
          <button
            className={tab === item.id ? "is-active" : ""}
            type="button"
            key={item.id}
            aria-current={tab === item.id ? "page" : undefined}
            onClick={() => navigateTab(item.id)}
          >
            {item.label}
          </button>
        ))}
      </nav>

      {homeError && <div className="support-error" role="alert"><span>{homeError}</span><button type="button" onClick={() => window.location.reload()}>Retry</button></div>}
      {formMessage && !formOpen && <p className="workflow-success support-feedback" role="status">{formMessage}</p>}

      {tab === "overview" && <>
        <section className="support-search-hero" aria-labelledby="support-search-title">
          <span className="section-eyebrow">PESAGUARD SUPPORT CENTER</span>
          <h2 id="support-search-title">How can we help?</h2>
          <p>Search setup guides, API errors, webhook troubleshooting, and your support requests.</p>
          <label className="support-search-box">
            <Search size={19} aria-hidden="true" />
            <input
              aria-label="Search support"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              onKeyDown={(event) => { if (event.key === "Enter") navigateTab("help"); }}
              placeholder="Search docs, APIs, errors, webhooks, authentication..."
            />
            {search && <button type="button" aria-label="Clear search" onClick={() => setSearch("")}><X size={16} /></button>}
            <kbd>Enter</kbd>
          </label>
          {showSearchResults && <SearchResults
            results={searchResults}
            loading={searchLoading}
            error={searchError}
            onOpen={openSearchResult}
          />}
        </section>

        <section className="support-section" aria-labelledby="support-quick-help">
          <div className="support-section-heading"><div><span className="section-eyebrow">START HERE</span><h2 id="support-quick-help">Quick help</h2></div></div>
          <div className="support-quick-grid">
            <QuickLink icon={BookOpenText} title="Documentation" detail="Integration guides and API references" href={docsUrl} external />
            <QuickButton icon={CircleHelp} title="Help Center" detail="Search practical troubleshooting guides" onClick={() => navigateTab("help")} />
            <QuickButton icon={Radio} title="System status" detail="Current service health and incidents" onClick={() => navigateTab("status")} />
            <QuickButton icon={TicketCheck} title="My tickets" detail="Review requests and their status" onClick={() => navigateTab("tickets")} />
            <QuickButton icon={LifeBuoy} title="Contact support" detail="Tell us what is not working" onClick={() => startTicketForm()} />
            <QuickLink icon={MessagesSquare} title="Developer community" detail="Join developer discussions" href={communityUrl} external />
          </div>
        </section>

        <div className="support-overview-columns">
          <section className="panel support-overview-panel">
            <div className="panel-heading"><div><span className="section-eyebrow">LIVE SERVICE</span><h2>System status</h2><p>Health and incident updates are maintained by PesaGuard Status.</p></div><Radio size={18} /></div>
            <a className="support-inline-link" href={statusUrl} target="_blank" rel="noreferrer">Open live system status<ArrowUpRight size={14} /></a>
          </section>
          <section className="panel support-overview-panel">
            <div className="panel-heading">            <div><span className="section-eyebrow">YOUR SUPPORT</span><h2>Your open tickets</h2><p>{ticketTotal ? `${ticketTotal} request${ticketTotal === 1 ? "" : "s"} in your ticket history.` : "No open support requests."}</p></div><TicketCheck size={18} /></div>
            {openTickets.slice(0, 3).map((ticket) => (
              <button className="support-ticket-compact" type="button" key={ticket.id} onClick={() => navigateTab("tickets")}>
                <span><strong>{ticket.subject}</strong><small>{ticket.id} · {ticket.status.replaceAll("_", " ")}</small></span><ArrowUpRight size={14} />
              </button>
            ))}
            {openTickets.length === 0 && <button className="support-inline-link" type="button" onClick={() => navigateTab("tickets")}>View ticket history<ArrowUpRight size={14} /></button>}
          </section>
        </div>

        <section className="support-section">
          <div className="support-section-heading"><div><span className="section-eyebrow">SELF-SERVICE</span><h2>Latest help articles</h2></div><button className="support-inline-link" type="button" onClick={() => navigateTab("help")}>Browse Help Center<ArrowUpRight size={14} /></button></div>
          {recentArticles.length ? <ArticleList articles={recentArticles} onOpen={openArticle} />
            : homeError ? null : <p className="support-muted">Loading help articles…</p>}
        </section>

        <section className="support-contact-banner">
          <div><span className="section-eyebrow">HUMAN SUPPORT</span><h2>Still need help?</h2><p>Share the request ID and steps to reproduce, or email <a href="mailto:support@pesaguard.co.ke">support@pesaguard.co.ke</a>. Never include credentials or customer secrets.</p></div>
          <button className="button button--primary" type="button" onClick={() => startTicketForm()}><FilePlus2 size={15} />Contact PesaGuard Support</button>
        </section>
      </>}

      {tab === "help" && <section className="support-section">
        {selectedArticle ? <>
          <button type="button" className="support-back-link" onClick={() => { setSelectedArticle(null); pushSupportPath("/support/help"); }}><ArrowLeft size={15} />Back to Help Center</button>
          <article className="panel support-article">
            <span className="section-eyebrow">{formatCategory(selectedArticle.category)}</span>
            <h2>{selectedArticle.title}</h2>
            <p className="support-article-summary">{selectedArticle.summary}</p>
            <p className="support-article-updated">Updated {new Date(selectedArticle.updatedAt).toLocaleDateString()}</p>
            <div className="support-article-content">{selectedArticle.content.split(/\n{2,}/).map((paragraph, index) => <p key={index}>{paragraph}</p>)}</div>
            {selectedArticle.relatedErrorCodes.length > 0 && <div className="support-article-related"><strong>Related error codes</strong><div>{selectedArticle.relatedErrorCodes.map((code) => <code key={code}>{code}</code>)}</div></div>}
            {selectedArticle.relatedApiEndpoints.length > 0 && <div className="support-article-related"><strong>Related API endpoints</strong><div>{selectedArticle.relatedApiEndpoints.map((endpoint) => <code key={endpoint}>{endpoint}</code>)}</div></div>}
            {selectedArticle.relatedArticleIds.length > 0 && <div className="support-article-related"><strong>Related articles</strong><div>{selectedArticle.relatedArticleIds.map((slug) => <button type="button" className="support-related-article-link" key={slug} onClick={() => openArticleSlug(slug)}>{slug.replaceAll("-", " ")}<ArrowUpRight size={13} /></button>)}</div></div>}
            {selectedArticle.relatedDocumentationUrls.length > 0 && <div className="support-article-related"><strong>Related documentation</strong>{selectedArticle.relatedDocumentationUrls.map((url) => <a key={url} href={safeDocumentationUrl(url)} target="_blank" rel="noreferrer">{safeDocumentationUrl(url)}<ArrowUpRight size={13} /></a>)}</div>}
            <div className="support-article-feedback"><strong>Was this helpful?</strong><button type="button" disabled={!isAuthenticated || feedbackSubmitting} onClick={() => void sendArticleFeedback(true)}><Check size={14} />Yes</button><button type="button" disabled={!isAuthenticated || feedbackSubmitting} onClick={() => void sendArticleFeedback(false)}>No</button><span aria-live="polite">{feedbackMessage || (!isAuthenticated ? "Sign in to submit feedback." : "")}</span></div>
          </article>
          <section className="support-contact-banner compact"><div><h2>Still need help?</h2><p>Contact support and include the relevant request ID.</p></div><button className="button button--primary" type="button" onClick={() => startTicketForm()}>Contact Support</button></section>
        </> : <>
          <div className="support-section-heading"><div><span className="section-eyebrow">SELF-SERVICE</span><h2>Help Center</h2><p>Search common integration questions and troubleshooting guidance.</p></div></div>
          <label className="support-search-box support-search-box--small"><Search size={17} /><input aria-label="Search help articles" value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Search by topic, error code, or endpoint" /></label>
          {home?.categories.length ? <div className="support-category-filter" aria-label="Filter by support category">
            <button type="button" className={!categoryFilter ? "is-active" : ""} onClick={() => setCategoryFilter("")}>All topics</button>
            {home.categories.map((item) => <button type="button" className={categoryFilter === item.id ? "is-active" : ""} key={item.id} onClick={() => setCategoryFilter(categoryFilter === item.id ? "" : item.id)}>{item.name}</button>)}
          </div> : null}
          {showSearchResults ? <SearchResults
            results={searchResults}
            loading={searchLoading}
            error={searchError}
            onOpen={openSearchResult}
          />           : articleError ? <div className="support-error" role="alert"><span>We couldn't load the Help Center. {articleError}</span><button type="button" onClick={() => setArticleRefreshVersion((current) => current + 1)}>Retry</button></div>
            : <ArticleList articles={articles} onOpen={openArticle} />}
        </>}
      </section>}

      {tab === "tickets" && <section className="panel support-tickets-panel" id="support-ticket-list" aria-labelledby="support-ticket-list-title">
        <div className="panel-heading">
          <div><span className="section-eyebrow">SUPPORT INBOX</span><h2 id="support-ticket-list-title">My tickets</h2><p>{isAuthenticated ? "Requests submitted from this account." : "Sign in to access the persisted support inbox."}</p></div>
          <div className="support-ticket-actions">{isAuthenticated && <button className="button button--secondary" type="button" disabled={ticketsLoading} onClick={() => setRefreshVersion((current) => current + 1)}><RefreshCw size={14} />Refresh</button>}<button className="button button--primary" type="button" disabled={!isAuthenticated} onClick={() => startTicketForm()}><FilePlus2 size={14} />New ticket</button></div>
        </div>
        {!isAuthenticated && <p className="support-preview-note">Support tickets are persisted by the system and require an authenticated account.</p>}
        {ticketsError && <p className="notification-alert" role="alert">{ticketsError}</p>}
        {focusedTicket && <article className="support-ticket-focused"><span className="support-ticket-id">{focusedTicket.id}</span><h3>{focusedTicket.subject}</h3><span className="support-ticket-status">{focusedTicket.status.replaceAll("_", " ")}</span><p>{focusedTicket.description}</p><button type="button" className="support-inline-link" disabled={ticketActionId === focusedTicket.id} onClick={() => void updateTicketStatus(focusedTicket)}>{focusedTicket.status === "CLOSED" ? "Reopen ticket" : "Close ticket"}</button></article>}
        {ticketsLoading ? <p className="support-tickets-empty">Loading your support requests…</p>
          : tickets.length === 0 ? <div className="support-tickets-empty"><TicketCheck size={20} /><strong>You don't have any support tickets yet.</strong><span>If something isn't working, we're here to help.</span><button className="support-inline-link" type="button" onClick={() => startTicketForm()}>Contact Support<ArrowUpRight size={14} /></button></div>
            : <div className="support-ticket-list">{tickets.map((ticket) => (
              <details className="support-ticket-row" key={ticket.id}>
                <summary>
                  <span className="support-ticket-id">{ticket.id}</span>
                  <span className="support-ticket-summary"><strong>{ticket.subject}</strong><small>{formatCategory(ticket.category)} · {priorityLabels[ticket.priority] ?? ticket.priority}</small></span>
                  <span className="support-ticket-status">{ticket.status.replaceAll("_", " ")}</span>
                  <time dateTime={ticket.createdAt}>{new Date(ticket.createdAt).toLocaleDateString()}</time>
                </summary>
                <div className="support-ticket-detail"><p>{ticket.description}</p>{(ticket.environment || ticket.endpoint || ticket.httpStatus || ticket.requestId || ticket.deliveryId || ticket.eventType || ticket.authenticationMethod || ticket.errorCode) && <div className="support-ticket-context">
                  {ticket.environment && <span>Environment: {ticket.environment}</span>}
                  {ticket.endpoint && <span>Endpoint: {ticket.endpoint}</span>}
                  {ticket.httpStatus && <span>HTTP status: {ticket.httpStatus}</span>}
                  {ticket.requestId && <span>Request ID: {ticket.requestId}</span>}
                  {ticket.deliveryId && <span>Delivery ID: {ticket.deliveryId}</span>}
                  {ticket.eventType && <span>Event: {ticket.eventType}</span>}
                  {ticket.authenticationMethod && <span>Auth method: {ticket.authenticationMethod}</span>}
                  {ticket.errorCode && <span>Error code: {ticket.errorCode}</span>}
                </div>}<span>Last updated {new Date(ticket.updatedAt).toLocaleString()}</span>{ticketActionError && <p className="support-form-message" role="alert">{ticketActionError}</p>}<div className="support-ticket-detail-actions"><button type="button" className="support-inline-link" disabled={ticketActionId === ticket.id} onClick={() => void updateTicketStatus(ticket)}>{ticketActionId === ticket.id ? "Updating…" : ticket.status === "CLOSED" ? "Reopen ticket" : "Close ticket"}</button></div></div>
              </details>
            ))}</div>}
        {ticketTotalPages > 1 && <div className="support-pagination"><span>Page {ticketPage + 1} of {ticketTotalPages} · {ticketTotal} tickets</span><div><button className="button button--secondary" type="button" disabled={ticketPage === 0 || ticketsLoading} onClick={() => setTicketPage((current) => Math.max(0, current - 1))}>Previous</button><button className="button button--secondary" type="button" disabled={ticketPage + 1 >= ticketTotalPages || ticketsLoading} onClick={() => setTicketPage((current) => Math.min(ticketTotalPages - 1, current + 1))}>Next</button></div></div>}
      </section>}

      {tab === "status" && <section className="panel support-destination">
        <div className="support-destination-icon"><Radio size={22} /></div><span className="section-eyebrow">LIVE SERVICE INFORMATION</span><h2>System status</h2>
        {platformStatusError && <p className="support-form-message" role="alert">Status service is unavailable: {platformStatusError}</p>}
        {platformStatus && <>
          <p>Overall platform status: <strong>{platformStatus.overallStatus.replaceAll("_", " ")}</strong></p>
          <div className="support-status-components">{platformStatus.components.map((component) => <span key={component.service}>{component.service}: {component.status}</span>)}</div>
          {platformStatus.maintenanceMode && <p>{platformStatus.maintenanceMessage}{platformStatus.estimatedRecoveryAt ? ` Estimated recovery: ${new Date(platformStatus.estimatedRecoveryAt).toLocaleString()}.` : ""}</p>}
          {platformStatus.activeIncidents.length > 0 && <div className="support-status-incidents">{platformStatus.activeIncidents.map((incident) => <article key={incident.id}><strong>{incident.title}</strong><small>{incident.severity} · {incident.status.replaceAll("_", " ")} · {incident.affectedServices.join(", ")}</small><p>{incident.summary}</p>{incident.updates.map((update) => <p key={update.id}><small>{new Date(update.createdAt).toLocaleString()}</small> {update.message}</p>)}</article>)}</div>}
          {platformStatus.activeIncidents.length === 0 && <p>No active public incidents are reported.</p>}
        </>}
        <p>Current service health, planned maintenance, and incident history are also maintained on PesaGuard's status platform.</p>
        <div className="support-destination-actions"><a className="button button--primary" href={statusUrl} target="_blank" rel="noreferrer">Open live status page<ArrowUpRight size={14} /></a><a className="support-inline-link" href={`${statusUrl}/history`} target="_blank" rel="noreferrer">View incident history<ArrowUpRight size={14} /></a></div>
      </section>}

      {tab === "announcements" && <section className="panel support-destination">
        <div className="support-destination-icon"><Siren size={22} /></div><span className="section-eyebrow">PLATFORM UPDATES</span><h2>Announcements</h2>
        <p>Service incidents and operational notices are published by PesaGuard Status. Product and API updates are available in the documentation changelog.</p>
        <div className="support-destination-actions"><a className="button button--primary" href={statusUrl} target="_blank" rel="noreferrer">Service announcements<ArrowUpRight size={14} /></a><a className="support-inline-link" href={docsUrl} target="_blank" rel="noreferrer">Documentation and changelog<ArrowUpRight size={14} /></a></div>
      </section>}

      {tab === "community" && <section className="panel support-destination">
        <div className="support-destination-icon"><MessagesSquare size={22} /></div><span className="section-eyebrow">DEVELOPER COMMUNITY</span><h2>Build with the community</h2>
        <p>Ask implementation questions, compare integration approaches, and learn from other PesaGuard developers.</p>
        <a className="button button--primary" href={communityUrl} target="_blank" rel="noreferrer">Join community discussions<ArrowUpRight size={14} /></a>
      </section>}

      {formOpen && <section className="panel support-ticket-form-panel" aria-labelledby="support-ticket-form-title">
        <div className="panel-heading">
          <div><span className="section-eyebrow">SUPPORT REQUEST</span><h2 id="support-ticket-form-title">Create a support ticket</h2><p>{isAuthenticated ? `Replies will be associated with ${user?.email ?? "your account"}.` : "Sign in to submit this request to support."}</p></div>
          <button className="icon-button" type="button" aria-label="Close support form" onClick={() => { setFormOpen(false); pushSupportPath("/support/tickets"); }}><X size={16} /></button>
        </div>
        <ValidatedForm className="support-ticket-form" onSubmit={(event) => void submitTicket(event)}>
          <div className="support-ticket-form-grid">
            <label>Request type<select value={category} onChange={(event) => setCategory(event.target.value as TicketCategory)}>{categories.map((value) => <option value={value} key={value}>{categoryLabels[value]}</option>)}</select></label>
            <label>Priority<select value={priority} onChange={(event) => setPriority(event.target.value as TicketPriority)}>{Object.entries(priorityLabels).filter(([value]) => value !== "URGENT").map(([value, label]) => <option value={value} key={value}>{label}</option>)}</select><small>Urgent priority requires a support entitlement.</small></label>
            <label className="support-ticket-form-wide">Contact email<input type="email" value={user?.email ?? ""} readOnly disabled={!isAuthenticated} /></label>
            <label className="support-ticket-form-wide">Subject<input id="support-ticket-subject" required minLength={4} maxLength={180} value={subject} onChange={(event) => setSubject(event.target.value)} placeholder="Briefly summarize the issue" /></label>
            {["WEBHOOKS", "API", "AUTHENTICATION"].includes(category) && <label className="support-ticket-form-wide">Environment<select value={environment} onChange={(event) => setEnvironment(event.target.value)}><option value="">Select an environment</option><option value="SANDBOX">Sandbox</option><option value="PRODUCTION">Production</option></select></label>}
            {(category === "API" || category === "WEBHOOKS") && <label className="support-ticket-form-wide">Endpoint (optional)<input value={endpoint} onChange={(event) => setEndpoint(event.target.value)} maxLength={200} pattern="/(?!/)[A-Za-z0-9_{}./-]{1,200}" placeholder="/v1/transactions" /></label>}
            {(category === "API" || category === "WEBHOOKS") && <label>HTTP status (optional)<input type="number" min={100} max={599} value={httpStatus} onChange={(event) => setHttpStatus(event.target.value)} placeholder="401" /></label>}
            {(category === "API" || category === "AUTHENTICATION") && <label>Request ID (optional)<input value={requestId} onChange={(event) => setRequestId(event.target.value)} maxLength={84} pattern="req_[A-Za-z0-9_-]{4,80}" placeholder="req_..." /></label>}
            {category === "WEBHOOKS" && <label>Delivery ID (optional)<input value={deliveryId} onChange={(event) => setDeliveryId(event.target.value)} maxLength={100} pattern="[A-Za-z0-9_.:-]{0,100}" placeholder="A non-sensitive delivery reference" /></label>}
            {category === "WEBHOOKS" && <label className="support-ticket-form-wide">Event type (optional)<input value={eventType} onChange={(event) => setEventType(event.target.value)} maxLength={100} pattern="[A-Za-z0-9_.:-]{0,100}" placeholder="transaction.completed" /></label>}
            {category === "AUTHENTICATION" && <label>Authentication method (optional)<input value={authenticationMethod} onChange={(event) => setAuthenticationMethod(event.target.value)} maxLength={60} pattern="[A-Za-z0-9 _.-]{0,60}" placeholder="API key, OAuth..." /></label>}
            {category === "AUTHENTICATION" && <label>Error code (optional)<input value={errorCode} onChange={(event) => setErrorCode(event.target.value)} maxLength={80} pattern="[A-Za-z0-9_.:-]{0,80}" placeholder="INVALID_API_KEY" /></label>}
            <label className="support-ticket-form-wide">What do you need help with?<textarea required minLength={10} maxLength={8000} rows={6} value={description} onChange={(event) => setDescription(event.target.value)} placeholder="Describe what happened, what you expected, and any non-sensitive request IDs that could help us investigate." /></label>
          </div>
          <p className="support-sensitive-reminder">Never include passwords, API keys, tokens, authorization headers, customer payment data, or webhook secrets.</p>
          {formMessage && <p className="support-form-message" role="alert">{formMessage}</p>}
          <div className="support-form-footer"><span>{isAuthenticated ? "Your request is scoped to your account." : "Sign in is required to submit this ticket."}</span><button className="button button--primary" type="submit" disabled={submitting || !isAuthenticated}>{submitting ? <><RefreshCw size={14} className="spin" />Submitting…</> : <><FilePlus2 size={14} />Submit ticket</>}</button></div>
        </ValidatedForm>
      </section>}
    </>
  );
}

function SearchResults(props: {
  results: SearchResult[];
  loading: boolean;
  error: string;
  onOpen: (result: SearchResult) => void;
}) {
  if (props.loading) return <div className="support-search-results" aria-live="polite">Searching support resources…</div>;
  if (props.error) return <div className="support-search-results is-error" role="alert">Search is temporarily unavailable. {props.error}</div>;
  return <div className="support-search-results" aria-live="polite">
    {props.results.length ? props.results.map((result) => (
      <button type="button" className="support-search-result" key={`${result.type}-${result.id}`} onClick={() => props.onOpen(result)}>
        <span className="section-eyebrow">{result.type === "SUPPORT_TICKET" ? `MY TICKET · ${result.id}` : `HELP CENTER · ${formatCategory(result.category)}`}</span>
        <strong>{result.title}</strong><span>{result.summary}</span>
      </button>
    )) : <p>No matching help articles or tickets. Try an error code such as 401 or 429, or contact support.</p>}
  </div>;
}

function ArticleList({ articles, onOpen }: { articles: SupportArticle[]; onOpen: (article: SupportArticle) => void }) {
  if (articles.length === 0) return <p className="support-tickets-empty">No articles match this topic yet.</p>;
  return <div className="support-article-list">
    {articles.map((article) => (
      <button type="button" className="support-article-row" key={article.id} onClick={() => onOpen(article)}>
        <span className="section-eyebrow">{formatCategory(article.category)}</span>
        <strong>{article.title}</strong><span>{article.summary}</span><ArrowUpRight size={15} />
      </button>
    ))}
  </div>;
}

function QuickButton({ icon: Icon, title, detail, onClick }: { icon: typeof Search; title: string; detail: string; onClick: () => void }) {
  return <button type="button" className="support-quick-link" onClick={onClick}><span className="support-quick-icon"><Icon size={18} /></span><strong>{title}</strong><span>{detail}</span><ArrowUpRight size={14} /></button>;
}

function QuickLink({ icon: Icon, title, detail, href, external }: { icon: typeof Search; title: string; detail: string; href: string; external?: boolean }) {
  return <a className="support-quick-link" href={href} target={external ? "_blank" : undefined} rel={external ? "noreferrer" : undefined}><span className="support-quick-icon"><Icon size={18} /></span><strong>{title}</strong><span>{detail}</span><ArrowUpRight size={14} /></a>;
}
