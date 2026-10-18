import { useEffect, useRef, useState } from "react";
import { AppShell } from "../components/layout/AppShell";
import { UsagePage } from "../pages/analytics/UsagePage";
import { DebuggingPage } from "../pages/debugging/DebuggingPage";
import { LogsPage } from "../pages/logs/LogsPage";
import { ApiCatalogPage } from "../pages/apis/ApiCatalogPage";
import { ApiLifecyclePage } from "../pages/apis/ApiLifecyclePage";
import { ApiDetailsPage } from "../pages/apis/ApiDetailsPage";
import { ApiVersionsPage } from "../pages/apis/ApiVersionsPage";
import { EndpointPage } from "../pages/apis/EndpointPage";
import { AuditDetailsPage } from "../pages/audit/AuditDetailsPage";
import { AuditLogsPage } from "../pages/audit/AuditLogsPage";
import { ActivityPage } from "../pages/dashboard/ActivityPage";
import { EnvironmentsPage } from "../pages/dashboard/EnvironmentsPage";
import { OverviewPage } from "../pages/dashboard/OverviewPage";
import { ProjectsPage } from "../pages/dashboard/ProjectsPage";
import { SecurityPage } from "../pages/dashboard/SecurityPage";
import { ApiKeysPage } from "../pages/credentials/ApiKeysPage";
import { OAuthApplicationsPage } from "../pages/credentials/OAuthApplicationsPage";
import { ServiceAccountsPage } from "../pages/credentials/ServiceAccountsPage";
import { EnvironmentPage } from "../pages/environments/EnvironmentPage";
import { EnvironmentSettingsPage } from "../pages/environments/EnvironmentSettingsPage";
import { ApiExplorerPage } from "../pages/explorer/ApiExplorerPage";
import { NotFoundPage } from "../pages/errors/NotFoundPage";
import { ForbiddenPage } from "../pages/errors/ForbiddenPage";
import { SupportPage } from "../pages/support/SupportPage";
import { GoLivePage, goLiveSections, type GoLiveSectionId } from "../pages/golive/GoLivePage";
import { ProjectActivityPage } from "../pages/projects/ProjectActivityPage";
import { ProjectOverviewPage } from "../pages/projects/ProjectOverviewPage";
import { ProjectSettingsPage } from "../pages/projects/ProjectSettingsPage";
import { SandboxActivityPage } from "../pages/sandbox/SandboxActivityPage";
import { SandboxPage } from "../pages/sandbox/SandboxPage";
import { SandboxSettingsPage } from "../pages/sandbox/SandboxSettingsPage";
import { SecurityCenterPage } from "../pages/security/SecurityCenterPage";
import { SecurityEventsPage } from "../pages/security/SecurityEventsPage";
import { SessionsPage } from "../pages/security/SessionsPage";
import { AccountSettingsPage } from "../pages/settings/AccountSettingsPage";
import { WebhooksPage } from "../pages/webhooks/WebhooksPage";
import { LoadTestingPage } from "../pages/load-testing/LoadTestingPage";
import { EventsPage } from "../pages/events/EventsPage";
import { DeveloperOnboardingPage } from "../pages/onboarding/DeveloperOnboardingPage";
import { LoginPage, RegisterPage } from "../pages/auth/AuthPages";
import { DeveloperFaqPage } from "../pages/onboarding/DeveloperFaqPage";
import { DeveloperLegalPage } from "../pages/legal/DeveloperLegalPage";
import { PublicHomePage } from "../pages/public/PublicHomePage";
import { MaintenancePage } from "../pages/errors/MaintenancePage";
import { useAuth } from "../context/AuthContext";
import { apiData } from "../lib/api";
import { confirmLeavingUnsavedForm, installUnsavedChangesWarning } from "../lib/unsavedChanges";
import { featureForPage, isFeatureEnabled, type PlatformRuntimeStatus } from "../lib/runtimeFeatures";
import { pageIds, type PageId } from "./routes";

function pageFromLocation(): { page: PageId; logId: string | null; goLiveSection?: GoLiveSectionId; apiKeyId?: string } {
  const pathname = window.location.pathname;
  const queryParams = new URLSearchParams(window.location.search);
  const apiKeyId = queryParams.get("apiKeyId") ?? undefined;
  const goLiveMatch = pathname.match(/^\/go-live(?:\/([^/]+))?\/?$/);
  if (goLiveMatch) {
    const section = goLiveSections.find(([id]) => id === goLiveMatch[1])?.[0] ?? "readiness";
    return { page: "go-live", logId: null, goLiveSection: section };
  }
  const pageParam = new URLSearchParams(window.location.search).get("page");
  if (pageParam && pageIds.includes(pageParam as (typeof pageIds)[number])) {
    return pageParam === "go-live"
      ? { page: "go-live", logId: null, goLiveSection: "readiness" }
      : { page: pageParam as PageId, logId: null, ...(apiKeyId ? { apiKeyId } : {}) };
  }
  if (pathname === "/support" || pathname.startsWith("/support/")) {
    return { page: "support-hub", logId: null };
  }
  if (pathname === "/debugging") return { page: "debugging", logId: null };
  if (pathname === "/load-testing" || pathname === "/load-testing/") return { page: "load-testing", logId: null };
  if (pathname === "/events" || pathname === "/events/") return { page: "events", logId: null };
  if (pathname === "/logs" || pathname === "/logs/")   return { page: "logs", logId: null, ...(apiKeyId ? { apiKeyId } : {}) };
  const logMatch = pathname.match(/^\/logs\/([^/]+)\/?$/);
  if (logMatch) {
    try {
      return { page: "logs", logId: decodeURIComponent(logMatch[1]), ...(apiKeyId ? { apiKeyId } : {}) };
    } catch {
      return { page: "logs", logId: logMatch[1], ...(apiKeyId ? { apiKeyId } : {}) };
    }
  }
  return { page: "overview", logId: null };
}

const publicResourceRedirects: Record<string, string> = {
  "/docs": "https://docs.pesaguard.co.ke/",
  "/api-reference": "https://docs.pesaguard.co.ke/api-reference/",
  "/pricing": "https://pesaguard.co.ke/pricing",
  "/changelog": "https://docs.pesaguard.co.ke/changelog/",
  "/status": "https://status.pesaguard.co.ke/",
  "/support": "https://docs.pesaguard.co.ke/support/",
};

export default function App() {
  const { status, hasPermission, permissionsLoaded, logout } = useAuth();
  const [runtimeStatus, setRuntimeStatus] = useState<PlatformRuntimeStatus | null>(null);
  const [location, setLocation] = useState(pageFromLocation);
  const historyPosition = useRef(
    typeof window.history.state?.pesaguardHistoryIndex === "number"
      ? window.history.state.pesaguardHistoryIndex as number
      : 0,
  );
  const restoringHistory = useRef(false);
  const lastKnownUrl = useRef(window.location.href);
  const { page: activePage, logId, apiKeyId, goLiveSection = "readiness" } = location;

  useEffect(() => {
    document.documentElement.dataset.theme = "light";
    const state = window.history.state && typeof window.history.state === "object"
      ? window.history.state
      : {};
    window.history.replaceState(
      { ...state, pesaguardHistoryIndex: historyPosition.current },
      "",
      window.location.href,
    );
    return installUnsavedChangesWarning();
  }, []);

  useEffect(() => {
    let cancelled = false;
    apiData<PlatformRuntimeStatus>("/api/v1/platform/status", { anonymous: true })
      .then((runtime) => { if (!cancelled) setRuntimeStatus(runtime); })
      .catch(() => {
        if (!cancelled) setRuntimeStatus({
          maintenanceMode: false,
          maintenanceMessage: "The developer platform is temporarily unavailable while updates are being applied.",
          maintenanceStartsAt: null,
          estimatedRecoveryAt: null,
          features: {},
        });
      });
    return () => { cancelled = true; };
  }, []);
  const resourcePath = window.location.pathname.replace(/\/$/, "") || "/";
  const legalDocument = resourcePath === "/terms" ? "terms"
    : resourcePath === "/privacy" ? "privacy"
      : null;
  const publicRedirect = resourcePath === "/support" && status === "authenticated"
    ? undefined
    : publicResourceRedirects[resourcePath];

  useEffect(() => {
    if (publicRedirect) window.location.replace(publicRedirect);
  }, [publicRedirect]);

  useEffect(() => {
    const pathname = window.location.pathname.replace(/\/+$/, "") || "/";
    if (status !== "authenticated" && pathname === "/onboarding") {
      window.history.replaceState(window.history.state, "", "/login");
      lastKnownUrl.current = window.location.href;
      return;
    }
    if (status !== "authenticated") return;

    const exemptPath = pathname === "/terms" || pathname === "/privacy"
      || pathname === "/faqs" || pathname === "/accept-invitation";
    const authPath = ["/login", "/register", "/verify-email", "/forgot-password", "/reset-password", "/locked", "/onboarding"]
      .includes(pathname);
    const nextPath = !exemptPath && authPath ? "/" : null;
    if (nextPath && pathname !== nextPath) {
      window.history.replaceState(window.history.state, "", nextPath);
      lastKnownUrl.current = window.location.href;
      setLocation(pageFromLocation());
    }
  }, [status]);

  useEffect(() => {
    function syncPath(event: PopStateEvent) {
      const nextIndex = typeof event.state?.pesaguardHistoryIndex === "number"
        ? event.state.pesaguardHistoryIndex as number
        : null;
      if (restoringHistory.current) {
        restoringHistory.current = false;
        if (nextIndex !== null) historyPosition.current = nextIndex;
        lastKnownUrl.current = window.location.href;
        return;
      }
      if (!confirmLeavingUnsavedForm()) {
        if (nextIndex !== null && nextIndex !== historyPosition.current) {
          restoringHistory.current = true;
          window.history.go(historyPosition.current - nextIndex);
        } else {
          window.history.pushState(
            { pesaguardHistoryIndex: historyPosition.current },
            "",
            lastKnownUrl.current,
          );
        }
        return;
      }
      if (nextIndex !== null) historyPosition.current = nextIndex;
      lastKnownUrl.current = window.location.href;
      setLocation(pageFromLocation());
    }
    window.addEventListener("popstate", syncPath);
    return () => window.removeEventListener("popstate", syncPath);
  }, []);

  useEffect(() => {
    function openSearchedLog(event: Event) {
      const requestId = (event as CustomEvent<string>).detail;
      if (typeof requestId === "string" && requestId.length > 0 && requestId.length <= 64) {
        openLog(requestId);
      }
    }
    window.addEventListener("pesaguard:open-log", openSearchedLog);
    return () => window.removeEventListener("pesaguard:open-log", openSearchedLog);
  }, [apiKeyId]);

  useEffect(() => {
    if (activePage === "go-live") {
      const canonicalPath = `/go-live/${goLiveSection}`;
      if (`${window.location.pathname}${window.location.search}` !== canonicalPath) {
        window.history.replaceState(
          { ...window.history.state, pesaguardHistoryIndex: historyPosition.current },
          "",
          canonicalPath,
        );
        lastKnownUrl.current = window.location.href;
      }
    }
  }, [activePage, goLiveSection]);

  function pushHistoryUrl(nextUrl: string) {
    if (`${window.location.pathname}${window.location.search}` !== nextUrl) {
      const nextIndex = historyPosition.current + 1;
      historyPosition.current = nextIndex;
      window.history.pushState({ pesaguardHistoryIndex: nextIndex }, "", nextUrl);
    }
    lastKnownUrl.current = window.location.href;
  }

  function navigate(page: PageId, keyId?: string) {
    if (!confirmLeavingUnsavedForm()) return;
    const params = new URLSearchParams();
    if (page !== "overview" && page !== "support-hub" && page !== "go-live") {
      params.set("page", page);
    }
    if (keyId && (page === "usage" || page === "logs")) params.set("apiKeyId", keyId);
    const nextUrl = page === "overview" ? "/" : page === "support-hub" ? "/support"
      : page === "go-live" ? "/go-live/readiness" : `/?${params.toString()}`;
    pushHistoryUrl(nextUrl);
    setLocation({
      page,
      logId: null,
      ...(keyId && (page === "usage" || page === "logs") ? { apiKeyId: keyId } : {}),
      ...(page === "go-live" ? { goLiveSection: "readiness" as const } : {}),
    });
  }

  function navigateGoLiveSection(section: GoLiveSectionId) {
    if (!confirmLeavingUnsavedForm()) return;
    const nextUrl = `/go-live/${section}`;
    pushHistoryUrl(nextUrl);
    setLocation({ page: "go-live", logId: null, goLiveSection: section });
  }

  function openLog(logId: string) {
    if (!confirmLeavingUnsavedForm()) return;
    const params = new URLSearchParams();
    if (apiKeyId) params.set("apiKeyId", apiKeyId);
    const nextPath = `/logs/${encodeURIComponent(logId)}${params.size ? `?${params.toString()}` : ""}`;
    pushHistoryUrl(nextPath);
    setLocation({ page: "logs", logId, ...(apiKeyId ? { apiKeyId } : {}) });
  }

  function backToLogs() {
    if (!confirmLeavingUnsavedForm()) return;
    const params = new URLSearchParams({ page: "logs" });
    if (apiKeyId) params.set("apiKeyId", apiKeyId);
    const path = `/?${params.toString()}`;
    pushHistoryUrl(path);
    setLocation({ page: "logs", logId: null, ...(apiKeyId ? { apiKeyId } : {}) });
  }

  function finishInvitation(): Promise<boolean> {
    pushHistoryUrl("/");
    setLocation({ page: "overview", logId: null });
    return Promise.resolve(true);
  }

  const renderPage = () => {
    const requiredPermission: Partial<Record<PageId, string>> = {
      projects: "project:read",
      "project-overview": "project:read",
      "project-settings": "project:update",
      "project-activity": "audit:read",
      environments: "environment:read",
      "environment-detail": "environment:read",
      "environment-settings": "environment:update",
      "api-keys": "credential:read",
      "oauth-applications": "oauth_application:read",
      "service-accounts": "credential:read",
      webhooks: "webhook:read",
      events: "webhook:read",
      usage: "usage:read",
      logs: "usage:read",
      debugging: "usage:read",
      activity: "audit:read",
      "audit-logs": "audit:read",
      "audit-details": "audit:read",
      security: "security:read",
      "security-center": "security:read",
      "security-events": "security:read",
      "sandbox": "sandbox:read",
      "sandbox-activity": "sandbox:read",
      "sandbox-settings": "sandbox:update",
      "danger-zone": "organization:update",
    };
    const required = requiredPermission[activePage];
    if (required && !permissionsLoaded) {
      return <div className="onboarding-loading" role="status">Loading workspace permissions…</div>;
    }
    const createOnlyViewAllowed = activePage === "api-keys"
      ? hasPermission("credential:create")
      : activePage === "webhooks" && hasPermission("webhook:create");
    if (required && !hasPermission(required) && !createOnlyViewAllowed) return <ForbiddenPage />;
    switch (activePage) {
      case "overview":
        return <OverviewPage onNavigate={navigate} />;
      case "projects":
        return <ProjectsPage onNavigate={navigate} />;
      case "project-overview":
        return <ProjectOverviewPage onNavigate={navigate} />;
      case "project-settings":
        return <ProjectSettingsPage />;
      case "project-activity":
        return <ProjectActivityPage />;
      case "environments":
        return <EnvironmentsPage onNavigate={navigate} />;
      case "environment-detail":
        return <EnvironmentPage />;
      case "environment-settings":
        return <EnvironmentSettingsPage />;
      case "api-explorer":
        return <ApiExplorerPage />;
      case "api-lifecycle":
        return <ApiLifecyclePage onNavigate={navigate} />;
      case "api-catalog":
        return <ApiCatalogPage />;
      case "api-details":
        return <ApiDetailsPage onNavigate={navigate} />;
      case "api-versions":
        return <ApiVersionsPage />;
      case "api-endpoint":
        return <EndpointPage />;
      case "api-keys":
        return <ApiKeysPage onNavigate={navigate} />;
      case "oauth-applications":
        return <OAuthApplicationsPage />;
      case "service-accounts":
        return <ServiceAccountsPage />;
      case "webhooks":
        return <WebhooksPage />;
      case "load-testing":
        return <LoadTestingPage />;
      case "events":
        return <EventsPage />;
      case "usage":
        return <UsagePage apiKeyId={apiKeyId} />;
      case "logs":
        return <LogsPage apiKeyId={apiKeyId} logId={logId} onOpenLog={openLog} onBack={backToLogs} />;
      case "debugging":
        return <DebuggingPage />;
      case "activity":
        return <ActivityPage />;
      case "security":
        return <SecurityPage />;
      case "security-center":
        return <SecurityCenterPage />;
      case "security-events":
        return <SecurityEventsPage />;
      case "sessions":
        return <SessionsPage />;
      case "production":
      case "production-access":
      case "production-requirements":
      case "verification":
      case "go-live":
        return <GoLivePage onNavigate={navigate} activeSection={goLiveSection}
          onNavigateSection={navigateGoLiveSection} />;
      case "audit-logs":
        return <AuditLogsPage />;
      case "audit-details":
        return <AuditDetailsPage />;
      case "support-hub":
        return <SupportPage />;
      case "sandbox":
        return <SandboxPage />;
      case "sandbox-activity":
        return <SandboxActivityPage />;
      case "sandbox-settings":
        return <SandboxSettingsPage />;
      case "account-settings":
        return <AccountSettingsPage />;
      case "not-found":
      case "unauthorized":
      case "forbidden":
      case "server-error":
      case "maintenance":
        return <NotFoundPage />;
      default:
        return <NotFoundPage />;
    }
  };

  // Sign-in is enforced unconditionally. The previous `VITE_AUTH_ENABLED` flag let
  // the dashboard open with no credential, which is not a supported
  // configuration: every request it made failed with 401, so the UI looked
  // populated while displaying nothing real.
  if (legalDocument) {
    return <DeveloperLegalPage kind={legalDocument} />;
  }

  if (resourcePath === "/faqs") {
    return <DeveloperFaqPage />;
  }

  if (status === "checking") {
    return <div className="onboarding-loading" aria-live="polite">Checking your secure session…</div>;
  }

  if (publicRedirect) return <div className="onboarding-loading" aria-live="polite">Opening public PesaGuard resources…</div>;

  const pathname = window.location.pathname.replace(/\/+$/, "") || "/";
  const authRouteViews: Record<string, "verify" | "forgot" | "reset" | "locked"> = {
    "/verify-email": "verify",
    "/forgot-password": "forgot",
    "/reset-password": "reset",
    "/locked": "locked",
  };
  const authRouteView = authRouteViews[pathname];

  const invitationRoute = pathname === "/accept-invitation";
  if (invitationRoute) {
    return <DeveloperOnboardingPage
      onComplete={finishInvitation}
      initialStep="invitation"
    />;
  }

  if (status !== "authenticated") {
    const verifyToken = new URLSearchParams(window.location.hash.slice(1)).get("verify-email");
    if (pathname === "/" && !new URLSearchParams(window.location.search).has("page") && !verifyToken) {
      return <PublicHomePage />;
    }
    if (pathname === "/register") return <RegisterPage />;
    return <LoginPage initialView={authRouteView ?? (verifyToken ? "verify" : "login")} />;
  }

  if (runtimeStatus?.maintenanceMode) {
    return <MaintenancePage
      message={runtimeStatus.maintenanceMessage}
      estimatedRecoveryAt={runtimeStatus.estimatedRecoveryAt}
      onSignOut={() => { void logout(); }}
    />;
  }

  if (!runtimeStatus) {
    return <div className="onboarding-loading" aria-live="polite">Checking platform availability…</div>;
  }

  const feature = featureForPage(activePage);
  if (!isFeatureEnabled(runtimeStatus?.features ?? {}, feature)) {
    return <main className="page-content" role="status">
      <section className="panel center-panel">
        <div className="empty-state-box">
          <h2>Feature temporarily unavailable</h2>
          <p>This area has been temporarily disabled by the platform operator. Please try again later.</p>
        </div>
      </section>
    </main>;
  }

  return <AppShell
    activePage={activePage}
    onNavigate={navigate}
    features={runtimeStatus?.features ?? {}}
  >{renderPage()}</AppShell>;
}
