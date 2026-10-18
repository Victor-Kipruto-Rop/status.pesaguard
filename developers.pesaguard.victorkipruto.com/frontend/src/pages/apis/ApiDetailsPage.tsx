import { ArrowRight, BookOpenText } from "lucide-react";
import type { PageId } from "../../app/routes";
import { PageHeader } from "../../components/ui/PageHeader";

export function ApiDetailsPage({ onNavigate }: { onNavigate: (page: PageId) => void }) {
  return (
    <>
      <PageHeader
        eyebrow="API"
        title="API details"
        description="Product-level API release details are not exposed by the system."
      />
      <section className="panel organization-live-empty">
        <BookOpenText size={18} />
        <div>
          <strong>No product release record is available.</strong>
          <p>This page does not invent endpoint lists, service targets, lifecycle states, or version claims. The API catalog shows scope and event-contract data returned by the system.</p>
          <button className="button button--secondary" type="button" onClick={() => onNavigate("api-catalog")}>Open API catalog<ArrowRight size={14} /></button>
        </div>
      </section>
    </>
  );
}
