import { useState, type FormEvent } from "react";
import { KeyRound, LoaderCircle, ShieldCheck, X } from "lucide-react";
import { getUserMessage } from "../../lib/errors";
import { requestPasswordChange, verifyPasswordChange } from "../../lib/authApi";

type PasswordChangeChallenge = {
  challengeId: string;
  maskedEmail: string;
  expiresAt: string;
};

export function PasswordSettingsStep() {
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [code, setCode] = useState("");
  const [challenge, setChallenge] = useState<PasswordChangeChallenge | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [attempted, setAttempted] = useState(false);

  async function beginChange(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setAttempted(true);
    setError("");
    setNotice("");
    if (!currentPassword || !newPassword || newPassword !== confirmPassword) return;
    setBusy(true);
    try {
      const result = await requestPasswordChange({ currentPassword, newPassword });
      setChallenge(result);
      setCode("");
    } catch (requestError) {
      setError(getUserMessage(requestError, "A password change code could not be sent."));
    } finally {
      setBusy(false);
    }
  }

  async function confirmChange(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!challenge || !/^\d{6}$/.test(code)) {
      setError("Enter the six-digit code sent to your email.");
      return;
    }
    setBusy(true);
    setError("");
    try {
      await verifyPasswordChange({
        challengeId: challenge.challengeId,
        code,
        currentPassword,
        newPassword,
      });
      setChallenge(null);
      setCurrentPassword("");
      setNewPassword("");
      setConfirmPassword("");
      setNotice("Your password was changed. A confirmation email has been sent.");
    } catch (verificationError) {
      setError(getUserMessage(verificationError, "The password change code is invalid or expired."));
      setCode("");
    } finally {
      setBusy(false);
    }
  }

  async function resendCode() {
    setBusy(true);
    setError("");
    try {
      const result = await requestPasswordChange({ currentPassword, newPassword });
      setChallenge(result);
      setCode("");
    } catch (requestError) {
      setError(getUserMessage(requestError, "A new password change code could not be sent."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <section className="panel account-password-panel" aria-labelledby="account-password-title">
        <div className="panel-heading">
          <div className="account-password-heading">
            <span className="account-password-icon"><KeyRound size={20} /></span>
            <div>
              <h2 id="account-password-title">Password</h2>
              <p>Choose a new password and confirm the change with a code sent to your email.</p>
            </div>
          </div>
          <span className="table-tag">EMAIL VERIFICATION</span>
        </div>

        {error && !challenge && <p className="notification-alert" role="alert">{error}</p>}
        {notice && <p className="notification-success" role="status">{notice}</p>}
        <form className="account-password-form" onSubmit={(event) => void beginChange(event)} noValidate>
          <label>Current password
            <input className="field-control" type="password" autoComplete="current-password" value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} aria-invalid={attempted && !currentPassword || undefined} />
            {attempted && !currentPassword && <small className="public-auth-field-error">Enter your current password.</small>}
          </label>
          <label>New password
            <input className="field-control" type="password" autoComplete="new-password" value={newPassword} onChange={(event) => setNewPassword(event.target.value)} aria-invalid={attempted && !newPassword || undefined} />
            {attempted && !newPassword && <small className="public-auth-field-error">Enter a new password.</small>}
          </label>
          <label>Re-enter new password
            <input className="field-control" type="password" autoComplete="new-password" value={confirmPassword} onChange={(event) => setConfirmPassword(event.target.value)} aria-invalid={attempted && confirmPassword !== newPassword || undefined} />
            {attempted && confirmPassword !== newPassword && <small className="public-auth-field-error">The passwords do not match.</small>}
          </label>
          <div className="account-password-actions">
            <button className="button button--primary" type="submit" disabled={busy}>
              {busy ? <><LoaderCircle className="public-auth-spinner" size={15} />Sending code…</> : "Reset password"}
            </button>
          </div>
        </form>
      </section>

      {challenge && (
        <div className="account-step-up-overlay">
          <section className="account-step-up-dialog" role="dialog" aria-modal="true" aria-labelledby="password-code-title">
            <button className="icon-button account-step-up-close" type="button" aria-label="Close password verification" disabled={busy} onClick={() => setChallenge(null)}><X size={17} /></button>
            <span className="account-password-icon account-password-icon--large"><ShieldCheck size={22} /></span>
            <span className="section-eyebrow">VERIFY PASSWORD CHANGE</span>
            <h2 id="password-code-title">Enter your email code</h2>
            <p>We sent a six-digit code to <strong>{challenge.maskedEmail}</strong>. It expires at {new Date(challenge.expiresAt).toLocaleTimeString()}.</p>
            {error && <p className="notification-alert" role="alert">{error}</p>}
            <form onSubmit={(event) => void confirmChange(event)}>
              <label htmlFor="password-change-code">Verification code</label>
              <input
                id="password-change-code"
                className="field-control account-password-code"
                autoFocus
                autoComplete="one-time-code"
                inputMode="numeric"
                pattern="[0-9]{6}"
                maxLength={6}
                value={code}
                onChange={(event) => setCode(event.target.value.replace(/\D/g, "").slice(0, 6))}
                aria-invalid={Boolean(error) || undefined}
                placeholder="000000"
              />
              <div className="account-password-actions">
                <button className="button button--secondary" type="button" disabled={busy} onClick={() => void resendCode()}>Resend code</button>
                <button className="button button--secondary" type="button" disabled={busy} onClick={() => setChallenge(null)}>Cancel</button>
                <button className="button button--primary" type="submit" disabled={busy || code.length !== 6}>
                  {busy ? "Verifying…" : "Verify and change"}
                </button>
              </div>
            </form>
          </section>
        </div>
      )}
    </>
  );
}
