import { ValidatedForm } from "../../components/forms/ValidatedForm";
import { getUserMessage } from "../../lib/errors";
import { useState, type FormEvent } from "react";
import { Activity, Search } from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";
import { apiData } from "../../lib/api";

type RequestRecord = {
  requestId: string;
  projectId: string;
  environmentId: string;
  endpoint: string;
  method: string;
  statusCode: number;
  latencyMs: number;
  responseBytes: number | null;
  occurredAt: string;
  recordedAt: string;
};

export function DebuggingPage() {
  const [requestId, setRequestId] = useState("");
  const [record, setRecord] = useState<RequestRecord | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  async function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setLoading(true);
    setError("");
    setRecord(null);
    try {
      const result = await apiData<RequestRecord>(
        `/api/v1/usage/requests/${encodeURIComponent(requestId.trim())}`,
      );
      if (!result?.requestId) throw new Error("The request API returned an invalid record.");
      setRecord(result);
    } catch (requestError) {
      setError(getUserMessage(requestError, "Request correlation data could not be loaded."));
    } finally {
      setLoading(false);
    }
  }

  return (
    <>
      <PageHeader
        eyebrow="OBSERVABILITY"
        title="Request correlation"
        description="Look up persisted request telemetry by request ID. Distributed spans are not currently stored by this system."
      />
      <section className="panel">
        <div className="panel-heading"><div><h2>Find a request</h2><p>Search uses organization-scoped API request metadata.</p></div><Activity size={18} /></div>
        <ValidatedForm className="workflow-form" onSubmit={search}>
          <label>Request ID<input required maxLength={64} value={requestId} onChange={(event) => setRequestId(event.target.value)} placeholder="Paste an X-Request-ID value" /></label>
          <div className="workflow-form-actions"><button className="button button--primary" type="submit" disabled={loading || !requestId.trim()}><Search size={14} />{loading ? "Searching…" : "Find request"}</button></div>
        </ValidatedForm>
        {error && <p className="workflow-error" role="alert">{error}</p>}
        {record && <dl className="request-log-details">
          <dt>Request ID</dt><dd><code>{record.requestId}</code></dd>
          <dt>Request</dt><dd>{record.method} <code>{record.endpoint}</code></dd>
          <dt>HTTP status</dt><dd>{record.statusCode}</dd>
          <dt>Latency</dt><dd>{record.latencyMs} ms</dd>
          <dt>Response size</dt><dd>{record.responseBytes ?? "Not recorded"}</dd>
          <dt>Project ID</dt><dd><code>{record.projectId}</code></dd>
          <dt>Environment ID</dt><dd><code>{record.environmentId}</code></dd>
          <dt>Occurred</dt><dd>{new Date(record.occurredAt).toLocaleString()}</dd>
        </dl>}
        <p className="workflow-hint">Request and response bodies, authorization headers, and credential values are not collected or displayed. This view is not a distributed trace or a complete application log.</p>
      </section>
    </>
  );
}
