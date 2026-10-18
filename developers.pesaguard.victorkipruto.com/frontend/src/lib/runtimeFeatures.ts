import type { PageId } from "../app/routes";

export type PlatformRuntimeStatus = {
  maintenanceMode: boolean;
  maintenanceMessage: string;
  maintenanceStartsAt: string | null;
  estimatedRecoveryAt: string | null;
  features: Record<string, boolean>;
};

export function featureForPage(page: PageId): string | null {
  if (["webhooks", "events"].includes(page)) return page;
  if (["usage", "logs", "debugging"].includes(page)) return "usage";
  if (page === "oauth-applications") return "oauth";
  if (["sandbox", "sandbox-activity", "sandbox-settings"].includes(page)) return "sandbox";
  if (["go-live", "production", "production-access", "production-requirements", "verification"].includes(page)) return "productionAccess";
  if (page === "notifications") return "notifications";
  if (page === "support-hub") return "support";
  if (["activity", "project-activity", "audit-logs", "audit-details", "security-events"].includes(page)) return "audit";
  return null;
}

export function isFeatureEnabled(features: Record<string, boolean>, feature: string | null): boolean {
  if (feature === "billing") return false;
  return feature === null || features[feature] !== false;
}
