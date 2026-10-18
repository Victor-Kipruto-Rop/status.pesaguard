import { useEffect, useState, type FormEvent } from "react";
import { AlertTriangle, Clock3, LoaderCircle, PauseCircle, Trash2 } from "lucide-react";
import { getUserMessage } from "../../lib/errors";
import {
  deactivateAccount,
  cancelAccountDeletion,
  getAccountLifecycle,
  requestAccountDeletion,
  type AccountLifecycle,
  type AccountPauseDuration,
} from "../../lib/accountApi";
import { useAuth } from "../../context/AuthContext";

const pauseDurations: Array<{ days: AccountPauseDuration; label: string }> = [
  { days: 7, label: "7 days" },
  { days: 30, label: "30 days" },
  { days: 90, label: "90 days" },
];

export function DangerZonePage() {
  const { logout } = useAuth();
  const [durationDays, setDurationDays] = useState<AccountPauseDuration>(30);
  const [pausePassword, setPausePassword] = useState("");
  const [pauseMfaCode, setPauseMfaCode] = useState("");
  const [deletionPassword, setDeletionPassword] = useState("");
  const [deletionMfaCode, setDeletionMfaCode] = useState("");
  const [cancelPassword, setCancelPassword] = useState("");
  const [cancelMfaCode, setCancelMfaCode] = useState("");
  const [confirmDeletion, setConfirmDeletion] = useState("");
  const [lifecycle, setLifecycle] = useState<AccountLifecycle | null>(null);
  const [busyAction, setBusyAction] = useState<"pause" | "delete" | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  useEffect(() => {
    const controller = new AbortController();
    void getAccountLifecycle(controller.signal)
      .then(setLifecycle)
      .catch((requestError: unknown) => {
        if (!controller.signal.aborted) setError(getUserMessage(requestError, "Account status could not be loaded."));
      });
    return () => controller.abort();
  }, []);

  async function pauseAccount(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusyAction("pause");
    setError("");
    setNotice("");
    try {
      await deactivateAccount({
        currentPassword: pausePassword,
        mfaCode: pauseMfaCode || undefined,
        durationDays,
      });
      await logout();
      window.location.replace("/login?account=paused");
    } catch (requestError) {
      setError(getUserMessage(requestError, "The account could not be paused."));
      setBusyAction(null);
    }
  }

  async function deleteAccount(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (confirmDeletion !== "DELETE") return;
    setBusyAction("delete");
    setError("");
    setNotice("");
    try {
      const lifecycle = await requestAccountDeletion({
        currentPassword: deletionPassword,
        mfaCode: deletionMfaCode || undefined,
      });
      setLifecycle(lifecycle);
      const completesAt = lifecycle.deletionCompletesAt
        ? new Date(lifecycle.deletionCompletesAt).toLocaleString()
        : "after the 30-day review period";
      setNotice(`Your account deletion request is scheduled. The request can be cancelled before ${completesAt}.`);
      setDeletionPassword("");
      setConfirmDeletion("");
    } catch (requestError) {
      setError(getUserMessage(requestError, "The account deletion request could not be completed."));
    } finally {
      setBusyAction(null);
    }
  }

  async function cancelDeletion(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusyAction("delete");
    setError("");
    setNotice("");
    try {
      const result = await cancelAccountDeletion({
        currentPassword: cancelPassword,
        mfaCode: cancelMfaCode || undefined,
      });
      setLifecycle(result);
      setCancelPassword("");
      setNotice("The account deletion request was cancelled.");
    } catch (requestError) {
      setError(getUserMessage(requestError, "The deletion request could not be cancelled."));
    } finally {
      setBusyAction(null);
    }
  }

  return (
    <div className="account-danger-zone">
      <section className="panel danger-zone-action danger-zone-pause">
        <div className="panel-heading">
          <div className="account-danger-heading">
            <span className="account-danger-icon"><PauseCircle size={19} /></span>
            <div>
              <h2>Pause account</h2>
              <p>Temporarily deactivate sign-in and revoke active credentials.</p>
            </div>
          </div>
          <span className="table-tag">REVERSIBLE</span>
        </div>
        <p className="text-muted">Your account will reactivate automatically after the selected period. You will be signed out after the pause begins.</p>
        <form className="account-danger-form" onSubmit={(event) => void pauseAccount(event)}>
          <label>Pause duration
            <select className="field-control" value={durationDays} onChange={(event) => setDurationDays(Number(event.target.value) as AccountPauseDuration)}>
              {pauseDurations.map((duration) => <option key={duration.days} value={duration.days}>{duration.label}</option>)}
            </select>
          </label>
          <label>Confirm with your current password
            <input className="field-control" type="password" autoComplete="current-password" required value={pausePassword} onChange={(event) => setPausePassword(event.target.value)} />
          </label>
          <label>MFA code, if enabled
            <input className="field-control" inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={pauseMfaCode} onChange={(event) => setPauseMfaCode(event.target.value.replace(/\D/g, "").slice(0, 6))} />
          </label>
          <button className="button button--secondary" type="submit" disabled={busyAction !== null || !pausePassword}>
            {busyAction === "pause" ? <><LoaderCircle className="public-auth-spinner" size={15} />Pausing…</> : "Pause account"}
          </button>
        </form>
      </section>

      <section className="panel danger-zone-action danger-zone-delete">
        <div className="panel-heading">
          <div className="account-danger-heading">
            <span className="account-danger-icon account-danger-icon--delete"><Trash2 size={18} /></span>
            <div>
              <h2>Delete account</h2>
              <p>Schedule deletion with a 30-day cancellation period.</p>
            </div>
          </div>
          <span className="table-tag table-tag--danger">PERMANENT</span>
        </div>
        {lifecycle?.status === "PENDING_DELETION" ? <>
          <div className="account-deletion-notice">
            <Clock3 size={16} />
            <p>Deletion is pending until {lifecycle.deletionCompletesAt ? new Date(lifecycle.deletionCompletesAt).toLocaleString() : "the end of the 30-day review period"}. Cancel it here before then if you changed your mind.</p>
          </div>
          <form className="account-danger-form" onSubmit={(event) => void cancelDeletion(event)}>
            <label>Confirm with your current password
              <input className="field-control" type="password" autoComplete="current-password" required value={cancelPassword} onChange={(event) => setCancelPassword(event.target.value)} />
            </label>
            <label>MFA code, if enabled
              <input className="field-control" inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={cancelMfaCode} onChange={(event) => setCancelMfaCode(event.target.value.replace(/\D/g, "").slice(0, 6))} />
            </label>
            <button className="button button--secondary" type="submit" disabled={busyAction !== null || !cancelPassword}>
              {busyAction === "delete" ? "Cancelling…" : "Cancel deletion request"}
            </button>
          </form>
        </> : <>
        <div className="account-deletion-notice">
          <Clock3 size={16} />
          <p>Deletion starts after a 30-day cancellation period. After that, your account, all linked workspaces, their data, and their members’ workspace records will be permanently removed.</p>
        </div>
        <form className="account-danger-form" onSubmit={(event) => void deleteAccount(event)}>
          <label>Confirm with your current password
            <input className="field-control" type="password" autoComplete="current-password" required value={deletionPassword} onChange={(event) => setDeletionPassword(event.target.value)} />
          </label>
          <label>MFA code, if enabled
            <input className="field-control" inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={deletionMfaCode} onChange={(event) => setDeletionMfaCode(event.target.value.replace(/\D/g, "").slice(0, 6))} />
          </label>
          <label>Type <strong>DELETE</strong> to continue
            <input className="field-control" autoComplete="off" required value={confirmDeletion} onChange={(event) => setConfirmDeletion(event.target.value)} />
          </label>
          <button className="button button--danger" type="submit" disabled={!lifecycle || busyAction !== null || !deletionPassword || confirmDeletion !== "DELETE"}>
            {busyAction === "delete" ? <><LoaderCircle className="public-auth-spinner" size={15} />Scheduling…</> : <><AlertTriangle size={15} />Request account deletion</>}
          </button>
        </form>
        </>}
      </section>

      {error && <p className="notification-alert" role="alert">{error}</p>}
      {notice && <p className="notification-success" role="status">{notice}</p>}
    </div>
  );
}
