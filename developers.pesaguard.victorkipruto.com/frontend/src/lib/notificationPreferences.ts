export const notificationChannels = ["in_app", "email", "webhook", "slack", "teams"] as const;
export type NotificationChannel = (typeof notificationChannels)[number];

export const notificationEvents = [
  { id: "api_failures", label: "API errors", detail: "Elevated errors or repeated request failures", severity: "Critical" },
  { id: "credential_expiration", label: "Credential expiration", detail: "API keys approaching expiration", severity: "High" },
  { id: "rate_limit_warnings", label: "Rate-limit warnings", detail: "Quota and rate-limit thresholds", severity: "Medium" },
  { id: "webhook_failures", label: "Webhook failures", detail: "Repeated delivery failures or disabled endpoints", severity: "High" },
  { id: "security_events", label: "Security alerts", detail: "Suspicious activity and credential compromise", severity: "Critical", mandatoryEmail: true },
  { id: "production_approval", label: "Production events", detail: "Production access, readiness, and configuration changes", severity: "Low" },
  { id: "deployment_events", label: "Deployment events", detail: "Deployment starts, completions, and failures", severity: "High" },
  { id: "team_invitations", label: "Team invitations", detail: "Invitations and membership changes", severity: "Low" },
  { id: "system_incidents", label: "System incidents", detail: "Platform incident updates and resolution", severity: "Critical" },
] as const;

export type NotificationEventId = (typeof notificationEvents)[number]["id"];
export type Severity = "Critical" | "High" | "Medium" | "Low";
export type EventChannels = Record<NotificationEventId, Record<NotificationChannel, boolean>>;

export interface NotificationScopeOverride {
  projectId: string;
  projectName: string;
  environmentId: string;
  environmentName: string;
  channels: EventChannels;
  severity: Record<Severity, boolean>;
}

export interface NotificationPreferences {
  channels: EventChannels;
  severity: Record<Severity, boolean>;
  quietHours: {
    enabled: boolean;
    start: string;
    end: string;
    timezone: string;
  };
  scopeOverrides: NotificationScopeOverride[];
}

export const notificationStorageKey = "pesaguard.notification-preferences.v1";

export function defaultNotificationPreferences(): NotificationPreferences {
  const channels = Object.fromEntries(notificationEvents.map((event) => [
    event.id,
    {
      in_app: true,
      email: true,
      webhook: false,
      slack: false,
      teams: false,
    },
  ])) as EventChannels;

  return {
    channels,
    severity: { Critical: true, High: true, Medium: true, Low: true },
    quietHours: { enabled: false, start: "22:00", end: "08:00", timezone: "Africa/Nairobi" },
    scopeOverrides: [],
  };
}

export function normalizeNotificationPreferences(value: unknown): NotificationPreferences {
  const defaults = defaultNotificationPreferences();
  if (typeof value !== "object" || value === null) return defaults;
  const saved = value as Partial<NotificationPreferences>;
  const mergeChannels = (candidate: unknown): EventChannels => {
    const merged = defaultNotificationPreferences().channels;
    if (typeof candidate !== "object" || candidate === null) return merged;
    const savedChannels = candidate as Partial<EventChannels>;
    for (const event of notificationEvents) {
      const row = savedChannels[event.id];
      if (!row || typeof row !== "object") continue;
      for (const channel of notificationChannels) {
        if (typeof row[channel] === "boolean") merged[event.id][channel] = row[channel];
      }
      merged[event.id].in_app = true;
      if ("mandatoryEmail" in event && event.mandatoryEmail) merged[event.id].email = true;
    }
    return merged;
  };
  const mergeSeverity = (candidate: unknown): Record<Severity, boolean> => {
    const merged = { ...defaults.severity };
    if (typeof candidate !== "object" || candidate === null) return merged;
    const savedSeverity = candidate as Partial<Record<Severity, unknown>>;
    for (const level of Object.keys(merged) as Severity[]) {
      if (typeof savedSeverity[level] === "boolean") merged[level] = savedSeverity[level] as boolean;
    }
    return merged;
  };
  const quiet = typeof saved.quietHours === "object" && saved.quietHours !== null
    ? saved.quietHours
    : defaults.quietHours;
  const overrides = Array.isArray(saved.scopeOverrides) ? saved.scopeOverrides.flatMap((entry) => {
    if (typeof entry !== "object" || entry === null) return [];
    const override = entry as Partial<NotificationScopeOverride>;
    if (typeof override.projectId !== "string" || typeof override.projectName !== "string"
      || typeof override.environmentId !== "string" || typeof override.environmentName !== "string") return [];
    return [{
      projectId: override.projectId,
      projectName: override.projectName,
      environmentId: override.environmentId,
      environmentName: override.environmentName,
      channels: mergeChannels(override.channels),
      severity: mergeSeverity(override.severity),
    }];
  }) : [];

  return {
    channels: mergeChannels(saved.channels),
    severity: mergeSeverity(saved.severity),
    quietHours: {
      enabled: typeof quiet.enabled === "boolean" ? quiet.enabled : defaults.quietHours.enabled,
      start: typeof quiet.start === "string" && /^\d{2}:\d{2}$/.test(quiet.start) ? quiet.start : defaults.quietHours.start,
      end: typeof quiet.end === "string" && /^\d{2}:\d{2}$/.test(quiet.end) ? quiet.end : defaults.quietHours.end,
      timezone: typeof quiet.timezone === "string" ? quiet.timezone : defaults.quietHours.timezone,
    },
    scopeOverrides: overrides,
  };
}
