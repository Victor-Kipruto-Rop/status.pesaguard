import { useState } from "react";
import "./DeveloperDashboardAccessLoader.css";

type DeveloperDashboardAccessLoaderProps = {
  error?: "connection" | "workspace";
  onRetry?: () => Promise<void>;
  onSignOut?: () => void;
};

export function DeveloperDashboardAccessLoader({
  error,
  onRetry,
  onSignOut,
}: DeveloperDashboardAccessLoaderProps) {
  const [retrying, setRetrying] = useState(false);

  async function retry() {
    if (!onRetry || retrying) return;
    setRetrying(true);
    try {
      await onRetry();
    } finally {
      setRetrying(false);
    }
  }

  return (
    <main className="developer-access-transition">
      <section
        className="developer-access-transition-content"
        role={error ? "alert" : "status"}
        aria-live={error ? "assertive" : "polite"}
        aria-busy={!error}
      >
        <div className={`developer-access-brand-mark${error ? " is-error" : ""}`} aria-hidden="true">
          <img src="/pesaguard-brand-mark.svg" alt="" />
          {!error && (
            <svg viewBox="0 0 176 190" focusable="false">
              <path
                className="developer-access-stroke-track"
                d="M72 2 139 27v49c0 36-25 63-67 82C30 139 5 112 5 76V27z"
                pathLength="1000"
                transform="translate(16 15)"
              />
              <path
                className="developer-access-stroke-progress"
                d="M72 2 139 27v49c0 36-25 63-67 82C30 139 5 112 5 76V27z"
                pathLength="1000"
                transform="translate(16 15)"
              />
            </svg>
          )}
        </div>
        {error ? (
          <>
            <h1>{error === "connection" ? "Your connection was interrupted" : "Unable to load your workspace"}</h1>
            <p>We couldn’t prepare your developer dashboard.</p>
            <div className="developer-access-actions">
              <button type="button" onClick={() => void retry()} disabled={retrying}>{retrying ? "Retrying…" : "Try again"}</button>
              <button type="button" className="developer-access-signout" onClick={onSignOut}>Sign out</button>
            </div>
          </>
        ) : (
          <>
            <h1>Preparing your workspace</h1>
            <p>Securely loading your developer dashboard…</p>
          </>
        )}
      </section>
    </main>
  );
}
