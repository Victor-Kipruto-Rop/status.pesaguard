import { getUserMessage } from "../../lib/errors";
import { useEffect, useState } from "react";
import { Bell, Check, Mail, RotateCcw } from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";
import { apiData } from "../../lib/api";

type Channel = "IN_APP" | "EMAIL";
type PreferenceResponse = {
  enabledChannels: Record<string, Channel[]>;
  emailRequiredCategories: string[];
  unavailableChannels?: string[];
};
type Draft = Record<string, Record<Channel, boolean>>;

const CHANNELS: { id: Channel; label: string; description: string; icon: typeof Bell }[] = [
  { id: "IN_APP", label: "In-app", description: "Save to your account inbox", icon: Bell },
  { id: "EMAIL", label: "Email", description: "Send to your verified email address", icon: Mail },
];

const CATEGORY_LABELS: Record<string, string> = {
  CREDENTIAL: "Credentials",
  WEBHOOK: "Webhooks",
  USAGE: "Usage",
  PRODUCTION: "Production access",
  SECURITY: "Security",
};

function toDraft(response: PreferenceResponse): Draft {
  return Object.fromEntries(Object.entries(response.enabledChannels).map(([category, channels]) => [
    category,
    { IN_APP: channels.includes("IN_APP"), EMAIL: channels.includes("EMAIL") },
  ]));
}

export function NotificationSettingsPage({ standalone = false }: { standalone?: boolean } = {}) {
  const [saved, setSaved] = useState<Draft>({});
  const [draft, setDraft] = useState<Draft>({});
  const [emailRequiredCategories, setEmailRequiredCategories] = useState<string[]>([]);
  const [unavailableChannels, setUnavailableChannels] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  async function load() {
    setLoading(true);
    setError("");
    try {
      const response = await apiData<PreferenceResponse>("/api/v1/notifications/preferences");
      if (!response.enabledChannels || typeof response.enabledChannels !== "object"
          || !Array.isArray(response.emailRequiredCategories)) {
        throw new Error("The notification preferences API returned an invalid response.");
      }
      const next = toDraft(response);
      setEmailRequiredCategories(response.emailRequiredCategories);
      setUnavailableChannels(Array.isArray(response.unavailableChannels) ? response.unavailableChannels : []);
      setSaved(next);
      setDraft(next);
    } catch (requestError) {
      setError(getUserMessage(requestError, "Could not load notification preferences."));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  const categories = Object.keys(draft);
  const isDirty = JSON.stringify(saved) !== JSON.stringify(draft);

  function changePreference(category: string, channel: Channel, enabled: boolean) {
    setDraft((current) => ({
      ...current,
      [category]: { ...current[category], [channel]: enabled },
    }));
    setError("");
    setNotice("");
  }

  async function savePreferences() {
    setSaving(true);
    setError("");
    setNotice("");
    try {
      const disabledChannels = Object.fromEntries(Object.entries(draft)
        .map(([category, channels]) => [
          category,
          CHANNELS.filter(({ id }) => !channels[id]).map(({ id }) => id),
        ])
        .filter(([, disabled]) => (disabled as Channel[]).length > 0));
      const response = await apiData<PreferenceResponse>("/api/v1/notifications/preferences", {
        method: "PUT",
        body: JSON.stringify({ disabledChannels }),
      });
      if (!response.enabledChannels || typeof response.enabledChannels !== "object"
          || !Array.isArray(response.emailRequiredCategories)) {
        throw new Error("The notification preferences API returned an invalid response.");
      }
      const next = toDraft(response);
      setEmailRequiredCategories(response.emailRequiredCategories);
      setUnavailableChannels(Array.isArray(response.unavailableChannels) ? response.unavailableChannels : []);
      setSaved(next);
      setDraft(next);
      setNotice("Notification preferences saved.");
    } catch (requestError) {
      setError(getUserMessage(requestError, "Could not save notification preferences."));
    } finally {
      setSaving(false);
    }
  }

  async function resetPreferences() {
    setSaving(true);
    setError("");
    setNotice("");
    try {
      const response = await apiData<PreferenceResponse>("/api/v1/notifications/preferences", {
        method: "PUT",
        body: JSON.stringify({ disabledChannels: {} }),
      });
      if (!response.enabledChannels || typeof response.enabledChannels !== "object"
          || !Array.isArray(response.emailRequiredCategories)) {
        throw new Error("The notification preferences API returned an invalid response.");
      }
      const next = toDraft(response);
      setEmailRequiredCategories(response.emailRequiredCategories);
      setUnavailableChannels(Array.isArray(response.unavailableChannels) ? response.unavailableChannels : []);
      setSaved(next);
      setDraft(next);
      setNotice("Notification preferences restored to defaults.");
    } catch (requestError) {
      setError(getUserMessage(requestError, "Could not restore notification preferences."));
    } finally {
      setSaving(false);
    }
  }

  return (
    <>
    {standalone && <PageHeader eyebrow="PERSONAL PREFERENCES" title="Notification preferences" description="Choose delivery channels for supported event categories. Mandatory security notifications remain enabled." />}
    <section className={`notification-settings notification-settings--premium${standalone ? " notification-settings--standalone" : ""}`}>
      <div className="notification-settings-intro">
        <div><h3>Delivery channels</h3><p>Choose how each type of notification is delivered. Security notifications may require in-app or email delivery.</p></div>
        <button className="button button--ghost" type="button" disabled={saving || loading} onClick={() => void resetPreferences()}><RotateCcw size={14} />Restore defaults</button>
      </div>
      {error && <p className="notification-alert" role="alert">{error}</p>}
      {notice && <p className="notification-success" role="status">{notice}</p>}
      {unavailableChannels.length > 0 && <p className="notification-alert" role="status">
        Not configured yet: {unavailableChannels.map((channel) => channel.replaceAll("_", " ").toLowerCase()).join(", ")}.
        These channels cannot be enabled until a delivery provider is configured.
      </p>}
      {loading
        ? <div className="notification-empty"><Bell size={20} /><strong>Loading saved preferences</strong></div>
        : categories.length === 0
          ? <div className="notification-empty"><Bell size={20} /><strong>No notification categories are configured</strong></div>
          : (
            <>
              <div className="notification-channel-heading"><span>Category</span>{CHANNELS.map(({ id, label }) => <span key={id}>{label}</span>)}</div>
              <div className="notification-preference-list">
                {categories.map((category) => (
                  <div className="notification-preference-row" key={category}>
                    <div className="notification-preference-category"><strong>{CATEGORY_LABELS[category] ?? category.replaceAll("_", " ")}</strong><span>Account notification category</span>{emailRequiredCategories.includes(category) && <small className="notification-required-mark">Security email required</small>}</div>
                    {CHANNELS.map(({ id, label, icon: Icon }) => {
                      const mandatory = id === "IN_APP" || (id === "EMAIL" && emailRequiredCategories.includes(category));
                      return (
                        <label className="notification-channel-toggle" key={id} title={mandatory ? `${label} delivery is required for this category` : undefined}>
                          <input type="checkbox" checked={draft[category][id]} disabled={mandatory || saving} onChange={(event) => changePreference(category, id, event.target.checked)} />
                          <span className="notification-toggle-ui"><Icon size={14} /></span>
                          <span className="visually-hidden">{label} for {CATEGORY_LABELS[category] ?? category}</span>
                        </label>
                      );
                    })}
                  </div>
                ))}
              </div>
              <div className="notification-settings-footer"><p>Changes are saved to your account on the system.</p><button className="button button--primary" type="button" disabled={saving || !isDirty} onClick={() => void savePreferences()}><Check size={14} />{saving ? "Saving…" : "Save preferences"}</button></div>
            </>
          )}
    </section>
    </>
  );
}
