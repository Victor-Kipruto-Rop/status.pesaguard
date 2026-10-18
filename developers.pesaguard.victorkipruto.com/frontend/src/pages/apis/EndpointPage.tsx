import { PageHeader } from "../../components/ui/PageHeader";

export function EndpointPage() {
  return (
    <>
      <PageHeader eyebrow="ENDPOINT" title="GET /v1/transactions" description="Retrieve a list of recent transactions for an account or workspace." />
      <section className="panel table-panel"><div className="panel-heading"><div><h2>Request contract</h2><p>Endpoint metadata and response fields</p></div><span className="table-tag">AUTH REQUIRED</span></div><div className="table-scroll"><table className="data-table"><thead><tr><th>FIELD</th><th>VALUE</th></tr></thead><tbody><tr><td>Method</td><td>GET</td></tr><tr><td>Path</td><td>/v1/transactions</td></tr><tr><td>Auth</td><td>Bearer token</td></tr><tr><td>Response</td><td>JSON array</td></tr></tbody></table></div></section>
    </>
  );
}
