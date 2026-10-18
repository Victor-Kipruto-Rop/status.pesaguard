import { Wrench } from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";

export function MaintenancePage({ message, estimatedRecoveryAt, onSignOut }: {
  message: string;
  estimatedRecoveryAt: string | null;
  onSignOut: () => void;
}) {
  const recovery = estimatedRecoveryAt ? new Date(estimatedRecoveryAt) : null;
  return (
    <>
      <PageHeader eyebrow="MAINTENANCE" title="Platform maintenance" description={message} />
      <section className="panel center-panel">
        <div className="empty-state-box">
          <Wrench size={48} />
          <h3>Be right back</h3>
          <p>{message}</p>
          {recovery && !Number.isNaN(recovery.valueOf()) && <p>Estimated return: {recovery.toLocaleString()}</p>}
          <button type="button" className="button button-secondary" onClick={onSignOut}>Sign out</button>
        </div>
      </section>
    </>
  );
}
