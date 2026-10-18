import { ValidatedForm } from "../../components/forms/ValidatedForm";
import { getUserMessage } from "../../lib/errors";
import { useCallback, useEffect, useState, type FormEvent } from "react";
import { RotateCw, Settings2 } from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";
import { apiData } from "../../lib/api";

type SandboxInstance = {
  id: string;
  name: string;
  status: string;
};

type SandboxLimits = {
  sandboxId: string;
  requestsPerMinute: number;
  burstRequests: number;
  maxApiKeys: number;
  maxCredentials: number;
  maxWebhookEndpoints: number;
  maxEventsPerMinute: number;
  maxRequestBodyBytes: number;
  maxResponseBodyBytes: number;
  executionTimeoutMs: number;
  maxHistoryEntries: number;
};

type LimitKey = Exclude<keyof SandboxLimits, "sandboxId">;

const limitFields: { key: LimitKey; label: string; min: number; max: number; unit: string }[] = [
  { key: "requestsPerMinute", label: "Requests per minute", min: 1, max: 100_000, unit: "requests/minute" },
  { key: "burstRequests", label: "Burst requests", min: 1, max: 10_000, unit: "requests" },
  { key: "maxApiKeys", label: "Maximum API keys", min: 1, max: 100, unit: "keys" },
  { key: "maxCredentials", label: "Maximum credentials", min: 1, max: 500, unit: "credentials" },
  { key: "maxWebhookEndpoints", label: "Maximum webhook endpoints", min: 1, max: 100, unit: "endpoints" },
  { key: "maxEventsPerMinute", label: "Maximum events per minute", min: 1, max: 100_000, unit: "events/minute" },
  { key: "maxRequestBodyBytes", label: "Maximum request body", min: 1, max: 1_048_576, unit: "bytes" },
  { key: "maxResponseBodyBytes", label: "Maximum response body", min: 1, max: 1_048_576, unit: "bytes" },
  { key: "executionTimeoutMs", label: "Execution timeout", min: 1, max: 30_000, unit: "milliseconds" },
  { key: "maxHistoryEntries", label: "Maximum history entries", min: 1, max: 1_000, unit: "entries" },
];

function errorMessage(error: unknown) {
  return getUserMessage(error, "The system request failed.");
}

export function SandboxSettingsPage() {
  const [sandboxes, setSandboxes] = useState<SandboxInstance[]>([]);
  const [selectedSandboxId, setSelectedSandboxId] = useState("");
  const [limitsRefresh, setLimitsRefresh] = useState(0);
  const [limits, setLimits] = useState<SandboxLimits | null>(null);
  const [loadingSandboxes, setLoadingSandboxes] = useState(true);
  const [loadingLimits, setLoadingLimits] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const loadSandboxes = useCallback(async (signal?: AbortSignal) => {
    setLoadingSandboxes(true);
    setError("");
    setNotice("");
    try {
      const result = await apiData<SandboxInstance[]>("/api/v1/sandboxes", { signal });
      if (!Array.isArray(result)) throw new Error("The sandbox API returned an invalid list.");
      setSandboxes(result);
      setSelectedSandboxId((current) => result.some((sandbox) => sandbox.id === current)
        ? current
        : result[0]?.id ?? "");
    } catch (requestError) {
      if (signal?.aborted) return;
      setError(errorMessage(requestError));
      setSandboxes([]);
      setSelectedSandboxId("");
    } finally {
      if (!signal?.aborted) setLoadingSandboxes(false);
    }
  }, []);

  async function refresh() {
    await loadSandboxes();
    setLimitsRefresh((current) => current + 1);
  }

  useEffect(() => {
    const controller = new AbortController();
    void loadSandboxes(controller.signal);
    return () => controller.abort();
  }, [loadSandboxes]);

  useEffect(() => {
    if (!selectedSandboxId) {
      setLimits(null);
      setLoadingLimits(false);
      return;
    }
    const controller = new AbortController();
    setLoadingLimits(true);
    setError("");
    setNotice("");
    setLimits(null);
    void apiData<SandboxLimits>(`/api/v1/sandboxes/${encodeURIComponent(selectedSandboxId)}/limits`, {
      signal: controller.signal,
    })
      .then(setLimits)
      .catch((requestError: unknown) => {
        if (!controller.signal.aborted) setError(errorMessage(requestError));
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoadingLimits(false);
      });
    return () => controller.abort();
  }, [selectedSandboxId, limitsRefresh]);

  async function saveLimits(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!limits || !selectedSandboxId) return;
    setSaving(true);
    setError("");
    setNotice("");
    const request = {
      requestsPerMinute: limits.requestsPerMinute,
      burstRequests: limits.burstRequests,
      maxApiKeys: limits.maxApiKeys,
      maxCredentials: limits.maxCredentials,
      maxWebhookEndpoints: limits.maxWebhookEndpoints,
      maxEventsPerMinute: limits.maxEventsPerMinute,
      maxRequestBodyBytes: limits.maxRequestBodyBytes,
      maxResponseBodyBytes: limits.maxResponseBodyBytes,
      executionTimeoutMs: limits.executionTimeoutMs,
      maxHistoryEntries: limits.maxHistoryEntries,
    };
    try {
      const updated = await apiData<SandboxLimits>(
        `/api/v1/sandboxes/${encodeURIComponent(selectedSandboxId)}/limits`,
        { method: "PUT", body: JSON.stringify(request) },
      );
      setLimits(updated);
      setNotice("Sandbox limits were saved.");
    } catch (requestError) {
      setError(errorMessage(requestError));
    } finally {
      setSaving(false);
    }
  }

  const selectedSandbox = sandboxes.find((sandbox) => sandbox.id === selectedSandboxId);

  return (
    <>
      <PageHeader
        eyebrow="CONFIG"
        title="Sandbox settings"
        description="View and update quotas returned by the system for a saved sandbox instance."
        action={<button className="button button--secondary" type="button" disabled={loadingSandboxes || loadingLimits || saving} onClick={() => void refresh()}><RotateCw size={14} />Refresh</button>}
      />

      {error && <p className="notification-alert" role="alert">{error}</p>}
      {notice && <p className="security-feedback" role="status">{notice}</p>}

      {loadingSandboxes
        ? <section className="panel"><p className="security-empty-state">Loading sandbox instances from the system…</p></section>
        : sandboxes.length === 0
          ? <section className="panel organization-live-empty" role={error ? "alert" : "status"}>
            <Settings2 size={18} />
            <div>
              <strong>{error ? "Sandbox settings are unavailable." : "No sandbox instances were returned."}</strong>
              <p>{error ? "The API request failed; no default limits are displayed." : "Create a sandbox in the Sandbox laboratory before viewing or changing its system-managed limits."}</p>
            </div>
          </section>
          : <>
            <section className="panel security-controls-panel">
              <div className="panel-heading"><div><h2>Sandbox instance</h2><p>Select an instance to load its saved quotas.</p></div><Settings2 size={17} className="heading-icon" /></div>
              <label className="settings-field"><span>Saved sandbox</span>
                <select className="field-control" value={selectedSandboxId} onChange={(event) => setSelectedSandboxId(event.target.value)}>
                  {sandboxes.map((sandbox) => <option key={sandbox.id} value={sandbox.id}>{sandbox.name} · {sandbox.status}</option>)}
                </select>
              </label>
            </section>

            <section className="panel security-controls-panel">
              <div className="panel-heading">
                <div><h2>Sandbox quotas</h2><p>{selectedSandbox ? `${selectedSandbox.name} · ${selectedSandbox.status}` : "Values are fetched from the system for the selected sandbox."}</p></div>
              </div>
              {loadingLimits
                ? <p className="security-empty-state">Loading system-configured sandbox limits…</p>
                : limits
                  ? <ValidatedForm className="settings-groups" onSubmit={(event) => void saveLimits(event)}>
                    {limitFields.map((field) => (
                      <label className="settings-field" key={field.key}>
                        <span>{field.label} <small>({field.unit})</small></span>
                        <input
                          className="field-control"
                          type="number"
                          required
                          min={field.min}
                          max={field.max}
                          step={1}
                          value={limits[field.key]}
                          onChange={(event) => {
                            const value = Number(event.target.value);
                            setLimits((current) => current ? { ...current, [field.key]: value } : current);
                          }}
                        />
                      </label>
                    ))}
                    <button className="button button--primary" type="submit" disabled={saving || loadingLimits}>
                      {saving ? "Saving…" : "Save sandbox limits"}
                    </button>
                  </ValidatedForm>
                  : <p className="security-empty-state">{error ? "Could not load limits for this sandbox." : "No limit data returned for this sandbox."}</p>}
            </section>
          </>}
    </>
  );
}
