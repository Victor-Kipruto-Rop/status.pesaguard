import { useEffect, useMemo, useState } from "react";
import { ArrowRight, Check, Circle, RotateCcw, Workflow } from "lucide-react";
import type { PageId } from "../../app/routes";
import { PageHeader } from "../../components/ui/PageHeader";
import { PreviewBadge } from "../../components/ui/PreviewBadge";

type LifecyclePhase = "Discover" | "Build & test" | "Go live" | "Operate & secure";

type LifecycleStep = {
  id: string;
  phase: LifecyclePhase;
  title: string;
  description: string;
  destination: PageId;
  actionLabel: string;
};

const lifecycleSteps: LifecycleStep[] = [
  { id: "discover-api", phase: "Discover", title: "Discover API", description: "Browse API products, endpoint methods, scopes, and request examples.", destination: "api-catalog", actionLabel: "Browse APIs" },
  { id: "create-project", phase: "Discover", title: "Create project", description: "Set up a project to organize your integration and team.", destination: "projects", actionLabel: "Open projects" },
  { id: "create-environment", phase: "Discover", title: "Create environment", description: "Choose Sandbox for development before requesting Production.", destination: "environments", actionLabel: "Open environments" },
  { id: "generate-sandbox-key", phase: "Build & test", title: "Generate sandbox API key", description: "Create a restricted sandbox credential and store it securely.", destination: "api-keys", actionLabel: "Open API keys" },
  { id: "configure-auth", phase: "Build & test", title: "Configure authentication", description: "Add the authorization header and required scopes to your requests.", destination: "api-explorer", actionLabel: "View API examples" },
  { id: "sandbox-request", phase: "Build & test", title: "Make sandbox request", description: "Use API Explorer to make a request to a supported system endpoint.", destination: "api-explorer", actionLabel: "Open API Explorer" },
  { id: "inspect-response", phase: "Build & test", title: "Inspect response", description: "Review the example response, schema, and status code.", destination: "api-explorer", actionLabel: "Inspect response examples" },
  { id: "debug-errors", phase: "Build & test", title: "Debug errors", description: "Use request logs and debugging tools to investigate failures.", destination: "debugging", actionLabel: "Open debugging" },
  { id: "configure-webhooks", phase: "Build & test", title: "Configure webhooks", description: "Choose event subscriptions, endpoint settings, and a retry policy.", destination: "webhooks", actionLabel: "Configure webhooks" },
  { id: "test-webhooks", phase: "Build & test", title: "Webhook delivery", description: "Review persisted delivery attempts and replay failed deliveries from the Webhooks workspace.", destination: "webhooks", actionLabel: "Review deliveries" },
  { id: "monitor-usage", phase: "Go live", title: "Monitor usage", description: "Review request volume, error rates, latency, and rate-limit estimates.", destination: "usage", actionLabel: "Review usage" },
  { id: "configure-security", phase: "Go live", title: "Configure security", description: "Review MFA, roles, allowlists, sessions, and credential handling.", destination: "security-center", actionLabel: "Review security" },
  { id: "request-production", phase: "Go live", title: "Create a production environment", description: "Provision a separate live environment; production access review applies only when enabled by your organization or deployment.", destination: "environments", actionLabel: "Add environment" },
  { id: "production-credentials", phase: "Go live", title: "Generate production credentials", description: "Create a key bound to the live environment and store it in your trusted server.", destination: "api-keys", actionLabel: "Open API keys" },
  { id: "deploy-integration", phase: "Go live", title: "Launch integration", description: "Complete system readiness checks and safely launch your Production integration.", destination: "go-live", actionLabel: "Open Go-Live" },
  { id: "monitor-production", phase: "Operate & secure", title: "Monitor production", description: "Review production health, usage, logs, and incidents when connected.", destination: "go-live", actionLabel: "Open Go-Live" },
  { id: "rotate-credentials", phase: "Operate & secure", title: "Rotate credentials", description: "Replace credentials on schedule or when exposure is suspected.", destination: "api-keys", actionLabel: "Review credentials" },
  { id: "revoke-credentials", phase: "Operate & secure", title: "Revoke credentials", description: "Revoke exposed or retired credentials using an authorized system action.", destination: "api-keys", actionLabel: "Manage credentials" },
];

const phases: LifecyclePhase[] = ["Discover", "Build & test", "Go live", "Operate & secure"];
const storageKey = "pesaguard.developer.api-lifecycle.completed";

function readCompletedSteps(): string[] {
  try {
    const value: unknown = JSON.parse(window.localStorage.getItem(storageKey) ?? "[]");
    if (!Array.isArray(value) || !value.every((item): item is string => typeof item === "string")) return [];
    return value.filter((id) => lifecycleSteps.some((step) => step.id === id));
  } catch (error) {
    console.warn("Unable to load API lifecycle progress.");
    return [];
  }
}

export function ApiLifecyclePage({ onNavigate }: { onNavigate: (page: PageId) => void }) {
  const [completedSteps, setCompletedSteps] = useState(readCompletedSteps);
  const [storageWarning, setStorageWarning] = useState("");

  const completedCount = completedSteps.length;
  const progress = Math.round((completedCount / lifecycleSteps.length) * 100);
  const nextStep = useMemo(() => lifecycleSteps.find((step) => !completedSteps.includes(step.id)), [completedSteps]);

  useEffect(() => {
    try {
      window.localStorage.setItem(storageKey, JSON.stringify(completedSteps));
      setStorageWarning("");
    } catch (error) {
      console.warn("Unable to save API lifecycle progress.");
      setStorageWarning("Progress could not be saved in this browser. Your checked steps may not persist after leaving the page.");
    }
  }, [completedSteps]);

  function toggleStep(id: string) {
    setCompletedSteps((current) => current.includes(id)
      ? current.filter((stepId) => stepId !== id)
      : [...current, id]);
  }

  function clearProgress() {
    setCompletedSteps([]);
  }

  return (
    <>
      <PageHeader
        eyebrow="BUILD · SHIP · OPERATE"
        title="API lifecycle"
        description="Follow the integration journey from API discovery through production operations and credential retirement."
        action={<button className="button button--secondary" type="button" onClick={clearProgress} disabled={completedCount === 0}><RotateCcw size={14} />Reset progress</button>}
      />
      <div className="preview-notice api-lifecycle-notice"><span className="notice-icon"><Workflow size={16} /></span><p><strong>Personal lifecycle tracker</strong> — Checked stages are saved in this browser only. API requests, key generation, production access, deployment, rotation, and revocation are not executed by this tracker.</p><PreviewBadge>Preview</PreviewBadge></div>

      <section className="panel api-lifecycle-progress-panel" aria-label="API lifecycle progress">
        <div className="api-lifecycle-progress-heading"><div><strong>{completedCount} of {lifecycleSteps.length} stages complete</strong><span>{nextStep ? `Next: ${nextStep.title}` : "All stages marked complete"}</span></div><strong>{progress}%</strong></div>
        <div className="checklist-meter"><span style={{ width: `${progress}%` }} /></div>
      </section>

      {storageWarning && <p className="api-lifecycle-storage-warning" role="status">{storageWarning}</p>}

      <div className="api-lifecycle-phases">
        {phases.map((phase) => {
          const steps = lifecycleSteps.filter((step) => step.phase === phase);
          const phaseDone = steps.filter((step) => completedSteps.includes(step.id)).length;
          return (
            <section className="panel api-lifecycle-phase" key={phase} aria-label={`${phase} lifecycle stages`}>
              <div className="panel-heading"><div><h2>{phase}</h2><p>{phaseDone} of {steps.length} stages complete</p></div></div>
              <ol className="api-lifecycle-steps">
                {steps.map((step) => {
                  const isComplete = completedSteps.includes(step.id);
                  return <li className={`api-lifecycle-step-card${isComplete ? " api-lifecycle-step-card--complete" : ""}`} key={step.id}>
                    <button type="button" className="api-lifecycle-step-check" aria-label={`${isComplete ? "Mark incomplete" : "Mark complete"}: ${step.title}`} aria-pressed={isComplete} onClick={() => toggleStep(step.id)}>
                      {isComplete ? <Check size={16} /> : <Circle size={16} />}
                    </button>
                    <div className="api-lifecycle-step-copy"><strong>{step.title}</strong><span>{step.description}</span></div>
                    <button type="button" className="text-button api-lifecycle-step-action" onClick={() => onNavigate(step.destination)}>{step.actionLabel}<ArrowRight size={13} /></button>
                  </li>;
                })}
              </ol>
            </section>
          );
        })}
      </div>
      <p className="api-lifecycle-tracker-note">This tracker coordinates your workflow; it does not create or delete resources, send API requests, activate Production, or change credentials.</p>
    </>
  );
}
