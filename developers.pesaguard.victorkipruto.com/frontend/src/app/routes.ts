import type { LucideIcon } from "lucide-react";
import {
  Activity,
  BookOpenText,
  ChartNoAxesCombined,
  Code2,
  FileText,
  KeyRound,
  LifeBuoy,
  Rocket,
  Webhook,
} from "lucide-react";

export type PageId =
  | "overview"
  | "projects"
  | "project-overview"
  | "project-settings"
  | "project-activity"
  | "environments"
  | "environment-detail"
  | "environment-settings"
  | "api-explorer"
  | "api-lifecycle"
  | "go-live"
  | "api-catalog"
  | "api-details"
  | "api-versions"
  | "api-endpoint"
  | "api-keys"
  | "oauth-applications"
  | "service-accounts"
  | "webhooks"
  | "events"
  | "load-testing"
  | "usage"
  | "logs"
  | "debugging"
  | "activity"
  | "security"
  | "security-center"
  | "security-events"
  | "sessions"
  | "production"
  | "production-access"
  | "production-requirements"
  | "verification"
  | "audit-logs"
  | "audit-details"
  | "notifications"
  | "notification-settings"
  | "support-hub"
  | "sandbox"
  | "sandbox-activity"
  | "sandbox-settings"
  | "account-settings"
  | "danger-zone"
  | "not-found"
  | "unauthorized"
  | "forbidden"
  | "server-error"
  | "maintenance";

export const pageIds = [
  "overview",
  "projects",
  "project-overview",
  "project-settings",
  "project-activity",
  "environments",
  "environment-detail",
  "environment-settings",
  "api-explorer",
  "api-lifecycle",
  "go-live",
  "api-catalog",
  "api-details",
  "api-versions",
  "api-endpoint",
  "api-keys",
  "oauth-applications",
  "service-accounts",
  "webhooks",
  "events",
  "load-testing",
  "usage",
  "logs",
  "debugging",
  "activity",
  "production",
  "production-access",
  "production-requirements",
  "verification",
  "audit-logs",
  "audit-details",
  "support-hub",
  "sandbox",
  "sandbox-activity",
  "sandbox-settings",
  "account-settings",
  "not-found",
  "unauthorized",
  "forbidden",
  "server-error",
  "maintenance",
] as const satisfies readonly PageId[];

export type NavigationGroup = "Overview" | "Build" | "Monitor" | "Resources";
export type NavigationDrawer = "changelog";

export type NavigationTarget =
  | { kind: "page"; page: PageId }
  | { kind: "drawer"; drawer: NavigationDrawer }
  | { kind: "external"; href: string };

export interface NavigationItem {
  id: string;
  label: string;
  icon: LucideIcon;
  group: NavigationGroup;
  target: NavigationTarget;
}

export const navigationItems: NavigationItem[] = [
  { id: "workspace", label: "Workspace", icon: Activity, group: "Overview", target: { kind: "page", page: "overview" } },
  { id: "projects", label: "Projects", icon: Code2, group: "Overview", target: { kind: "page", page: "projects" } },
  { id: "environments", label: "Environments", icon: Activity, group: "Overview", target: { kind: "page", page: "environments" } },
  { id: "api-explorer", label: "API Explorer", icon: Code2, group: "Build", target: { kind: "page", page: "api-explorer" } },
  { id: "api-keys", label: "API Keys", icon: KeyRound, group: "Build", target: { kind: "page", page: "api-keys" } },
  { id: "webhooks", label: "Webhooks", icon: Webhook, group: "Build", target: { kind: "page", page: "webhooks" } },
  { id: "load-testing", label: "Load Testing", icon: Activity, group: "Build", target: { kind: "page", page: "load-testing" } },
  { id: "usage", label: "Usage", icon: ChartNoAxesCombined, group: "Monitor", target: { kind: "page", page: "usage" } },
  { id: "activity", label: "Activity", icon: Activity, group: "Monitor", target: { kind: "page", page: "activity" } },
  { id: "go-live", label: "Go-Live", icon: Rocket, group: "Build", target: { kind: "page", page: "go-live" } },
  { id: "documentation", label: "Documentation", icon: BookOpenText, group: "Resources", target: { kind: "external", href: "https://docs.pesaguard.co.ke" } },
  { id: "api-reference", label: "API Reference", icon: Code2, group: "Resources", target: { kind: "page", page: "api-catalog" } },
  { id: "changelog", label: "Changelog", icon: FileText, group: "Resources", target: { kind: "drawer", drawer: "changelog" } },
  { id: "support", label: "Support", icon: LifeBuoy, group: "Resources", target: { kind: "page", page: "support-hub" } },
];

export const apiEndpointSearchIndex = [
  { method: "GET", path: "/v1/transactions", title: "List transactions" },
  { method: "POST", path: "/v1/reconciliation/runs", title: "Create reconciliation run" },
  { method: "GET", path: "/v1/accounts/{account_id}", title: "Retrieve account" },
  { method: "POST", path: "/v1/transactions", title: "Create transaction" },
  { method: "GET", path: "/v1/transactions/{transaction_id}", title: "Retrieve transaction" },
  { method: "GET", path: "/v1/accounts", title: "List accounts" },
  { method: "GET", path: "/v1/reconciliation/runs", title: "List reconciliation runs" },
  { method: "GET", path: "/v1/reconciliation/runs/{run_id}", title: "Retrieve reconciliation run" },
  { method: "GET", path: "/v1/webhooks/endpoints", title: "List webhook endpoints" },
  { method: "POST", path: "/v1/webhooks/endpoints", title: "Create webhook endpoint" },
];

export const externalLinks = {
  docs: "https://docs.pesaguard.co.ke",
  status: "https://status.pesaguard.co.ke",
  publicSite: "https://pesaguard.co.ke",
};

export const helpLinks = [
  { label: "Documentation", href: externalLinks.docs, icon: BookOpenText },
];
