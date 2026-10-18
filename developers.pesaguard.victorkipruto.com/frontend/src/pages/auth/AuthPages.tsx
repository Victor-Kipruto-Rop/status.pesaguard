import { getUserMessage } from "../../lib/errors";
import { useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { ArrowRight, Check, Eye, EyeOff, KeyRound, LoaderCircle, ShieldCheck, UserRoundPlus, X } from "lucide-react";
import { getCountries, getCountryCallingCode, parsePhoneNumberFromString, type CountryCode } from "libphonenumber-js";
import { ApiError } from "../../lib/api";
import * as authApi from "../../lib/authApi";
import { useAuth } from "../../context/AuthContext";
import type { LoginEmailMfaChallenge } from "../../types/auth";
import "../../styles/auth-pages.css";

type AuthView = "login" | "register" | "verify" | "email-mfa" | "forgot" | "reset" | "enroll-mfa" | "locked";

type AuthPageProps = {
  mode: "login" | "register";
  initialView?: AuthView;
};

function SixDigitCodeInput({
  label,
  digits,
  onChange,
  disabled,
  invalid = false,
}: {
  label: string;
  digits: string[];
  onChange: (digits: string[]) => void;
  disabled: boolean;
  invalid?: boolean;
}) {
  const inputRefs = useRef<Array<HTMLInputElement | null>>([]);

  function distributeDigits(value: string) {
    const cleanDigits = value.replace(/\D/g, "").slice(0, 6);
    onChange(Array.from({ length: 6 }, (_, index) => cleanDigits[index] ?? ""));
    inputRefs.current[Math.min(cleanDigits.length, 5)]?.focus();
  }

  return (
    <fieldset className={`public-auth-code-field${invalid ? " is-invalid" : ""}`} disabled={disabled}>
      <legend>{label}</legend>
      <div className="public-auth-code-inputs" role="group" aria-label={label}>
        {Array.from({ length: 6 }, (_, index) => (
          <input
            key={index}
            ref={(element) => { inputRefs.current[index] = element; }}
            aria-label={`${label}, digit ${index + 1} of 6`}
            aria-invalid={invalid || undefined}
            autoComplete={index === 0 ? "one-time-code" : "off"}
            autoFocus={index === 0}
            inputMode="numeric"
            pattern="[0-9]*"
            maxLength={6}
            required
            value={digits[index] ?? ""}
            onChange={(event) => {
              const entered = event.currentTarget.value.replace(/\D/g, "");
              if (entered.length > 1) {
                distributeDigits(entered);
                return;
              }
              const nextDigits = Array.from({ length: 6 }, (_, digitIndex) => digits[digitIndex] ?? "");
              nextDigits[index] = entered;
              onChange(nextDigits);
              if (entered && index < 5) inputRefs.current[index + 1]?.focus();
            }}
            onPaste={(event) => {
              const pasted = event.clipboardData.getData("text").replace(/\D/g, "");
              if (!pasted) return;
              event.preventDefault();
              distributeDigits(pasted);
            }}
            onKeyDown={(event) => {
              if (event.key === "ArrowLeft" && index > 0) {
                event.preventDefault();
                inputRefs.current[index - 1]?.focus();
              } else if (event.key === "ArrowRight" && index < 5) {
                event.preventDefault();
                inputRefs.current[index + 1]?.focus();
              } else if (event.key === "Backspace" && !digits[index] && index > 0) {
                inputRefs.current[index - 1]?.focus();
              }
            }}
          />
        ))}
      </div>
    </fieldset>
  );
}

const countryNames = new Intl.DisplayNames(["en"], { type: "region" });
const phoneCountries = getCountries()
  .map((country) => ({
    country,
    name: countryNames.of(country) ?? country,
    callingCode: getCountryCallingCode(country),
  }))
  .sort((left, right) => {
    if (left.country === "KE") return -1;
    if (right.country === "KE") return 1;
    return left.name.localeCompare(right.name);
  });

function AuthFrame({
  mode,
  loginLanding,
  emailMfa,
  flowView,
  flowFooterInsideLayout,
  flowFooterAfterPanel,
  passwordReset,
  loginFailed,
  title,
  subtitle,
  children,
  footer,
}: {
  mode: "login" | "register";
  loginLanding?: boolean;
  emailMfa?: boolean;
  flowView?: boolean;
  flowFooterInsideLayout?: boolean;
  flowFooterAfterPanel?: ReactNode;
  passwordReset?: boolean;
  loginFailed?: boolean;
  title: ReactNode;
  subtitle: string;
  children: ReactNode;
  footer?: ReactNode;
}) {
  const authClassName = [
    "public-auth",
    loginLanding && "public-auth--login",
    emailMfa && "public-auth--email-mfa",
    mode === "register" && "public-auth--register",
    flowView && "public-auth--flow",
    flowFooterInsideLayout && "public-auth--recovery",
    passwordReset && "public-auth--password-reset",
  ].filter(Boolean).join(" ");

  return (
    <main className={authClassName}>
      <header className="public-auth-header">
        <a className="public-auth-brand" href="/" aria-label="PesaGuard home">
          <img src="/pesaguard-brand-mark.svg" alt="" aria-hidden="true" />
          <span className="public-auth-brand-wordmark"><span>Pesa</span><span>Guard</span></span>
        </a>
        {!loginLanding && !flowView && <div className="public-auth-header-link">
          {mode === "login" ? <>New to PesaGuard? <a href="/register">Create account</a></> : <>Already have an account? <a href="/login">Sign in</a></>}
        </div>}
      </header>
      <div className="public-auth-layout">
        <section className={`public-auth-panel${loginLanding ? " public-auth-panel--login" : ""}`} aria-labelledby="public-auth-title">
          <div className="public-auth-panel-heading">
            <span className={`public-auth-panel-icon${loginLanding || passwordReset ? " public-auth-panel-icon--animated" : ""}${loginFailed ? " is-auth-failed" : ""}`}>
              {passwordReset
                ? <img className="public-auth-noun-icon" src="/icons/reset-password-vectors-point.png" alt="" aria-hidden="true" />
                : mode === "register"
                  ? <UserRoundPlus size={32} strokeWidth={1.6} aria-hidden="true" />
                  : <KeyRound size={23} strokeWidth={1.7} aria-hidden="true" />}
            </span>
            {passwordReset && <a className="public-auth-icon-credit" href="https://thenounproject.com/icon/reset-password-3728048/" target="_blank" rel="noreferrer">Reset Password by Vectors Point · CC BY 3.0</a>}
            {!loginLanding && <span className="public-auth-eyebrow">{mode === "register" ? "CREATE YOUR ACCOUNT" : "SECURE SIGN IN"}</span>}
            <h2 id="public-auth-title">{title === "Sign up" ? <><span>Sign</span> <span>Up</span></> : title}</h2>
            <p>{subtitle}</p>
          </div>
          {children}
          {footer && <div className="public-auth-footer">{footer}</div>}
        </section>
        {flowFooterAfterPanel}
        {flowFooterInsideLayout && (
          <footer className="public-auth-bottom public-auth-bottom--login public-auth-bottom--flow public-auth-bottom--recovery">
            <nav aria-label="Quick links"><a href="/faqs">FAQs</a><a href="/privacy">Policy</a><a href="/terms">Terms</a></nav>
          </footer>
        )}
        {loginLanding && (
          <footer className="public-auth-bottom public-auth-bottom--login">
            <nav aria-label="Quick links"><a href="/faqs">FAQs</a><a href="/privacy">Policy</a><a href="/terms">Terms</a></nav>
          </footer>
        )}
      </div>
      {flowView && !flowFooterInsideLayout
        ? <footer className="public-auth-bottom public-auth-bottom--login public-auth-bottom--flow">
            <nav aria-label="Quick links"><a href="/faqs">FAQs</a><a href="/privacy">Policy</a><a href="/terms">Terms</a></nav>
          </footer>
        : !flowView && !loginLanding && (mode === "register"
        ? <footer className="public-auth-bottom public-auth-bottom--login public-auth-bottom--register">
            <nav aria-label="Quick links"><a href="/faqs">FAQs</a><a href="/privacy">Policy</a><a href="/terms">Terms</a></nav>
          </footer>
        : <footer className="public-auth-bottom">
            <span>© {new Date().getFullYear()} PesaGuard</span>
            <span><ShieldCheck size={14} aria-hidden="true" /> Built for safer digital commerce</span>
          </footer>)}
    </main>
  );
}

function AuthPage({ mode, initialView = mode }: AuthPageProps) {
  const {
    login,
    register,
    completeRegistrationVerification,
    completeRegistrationVerificationByLink,
    verifyLoginEmailMfa,
    resendLoginEmailMfa,
    status,
    challenge,
  } = useAuth();
  const [view, setView] = useState<AuthView>(initialView);
  const [email, setEmail] = useState("");
  const [firstName, setFirstName] = useState("");
  const [lastName, setLastName] = useState("");
  const [phoneCountry, setPhoneCountry] = useState<CountryCode>("KE");
  const [phoneNumber, setPhoneNumber] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);
  const [authFailed, setAuthFailed] = useState(false);
  const [loginAttempted, setLoginAttempted] = useState(false);
  const [identifierTouched, setIdentifierTouched] = useState(false);
  const [passwordTouched, setPasswordTouched] = useState(false);
  const [registrationAttempted, setRegistrationAttempted] = useState(false);
  const [validationAttempted, setValidationAttempted] = useState(false);
  const [confirmPassword, setConfirmPassword] = useState("");
  const [acceptedTerms, setAcceptedTerms] = useState(false);
  const [codeDigits, setCodeDigits] = useState<string[]>(() => Array.from({ length: 6 }, () => ""));
  const code = codeDigits.join("");
  function setCode(value: string) {
    const cleanDigits = value.replace(/\D/g, "").slice(0, 6);
    setCodeDigits(Array.from({ length: 6 }, (_, index) => cleanDigits[index] ?? ""));
  }
  const [resetToken] = useState(() => {
    const query = new URLSearchParams(window.location.search);
    const resetFragment = window.location.hash.slice(1);
    const hash = new URLSearchParams(resetFragment);
    return query.get("token") ?? hash.get("reset-password")
      ?? (resetFragment.startsWith("reset-") ? resetFragment.slice("reset-".length) : "");
  });
  const [verificationToken] = useState(() => {
    const query = new URLSearchParams(window.location.search);
    const hash = new URLSearchParams(window.location.hash.slice(1));
    return query.get("token") ?? hash.get("verify-email") ?? "";
  });
  const [enrollmentToken, setEnrollmentToken] = useState("");
  const [enrollment, setEnrollment] = useState<{ secret: string; provisioningUri: string } | null>(null);
  const [loginMfaChallenge, setLoginMfaChallenge] = useState<LoginEmailMfaChallenge | null>(null);
  const [recoveryCodes, setRecoveryCodes] = useState<string[]>([]);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [successDialog, setSuccessDialog] = useState<"changed" | null>(null);
  const [resendAvailableAt, setResendAvailableAt] = useState<number | null>(null);
  const [now, setNow] = useState(Date.now());
  const verificationStarted = useRef(false);
  const successActionRef = useRef<HTMLButtonElement | HTMLAnchorElement>(null);
  const resendSeconds = resendAvailableAt === null ? 0 : Math.max(0, Math.ceil((resendAvailableAt - now) / 1000));
  const isEmailValid = /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim());
  const phoneIsValid = Boolean(parsePhoneNumberFromString(phoneNumber, phoneCountry)?.isValid());
  const forgotEmailError = validationAttempted
    ? !email.trim() ? "Please enter your email address."
      : !isEmailValid ? "Enter a valid email address."
        : null
    : null;
  const resetPasswordError = validationAttempted && !password ? "Please enter a new password." : null;
  const resetConfirmationError = validationAttempted && !confirmPassword
    ? "Please confirm your new password."
    : validationAttempted && password !== confirmPassword ? "Passwords do not match." : null;

  useEffect(() => {
    if (!successDialog) return;
    const previousOverflow = document.body.style.overflow;
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    document.body.style.overflow = "hidden";
    successActionRef.current?.focus();

    function handleDialogKeys(event: KeyboardEvent) {
      if (event.key === "Tab") {
        event.preventDefault();
        successActionRef.current?.focus();
      }
    }

    window.addEventListener("keydown", handleDialogKeys);
    return () => {
      window.removeEventListener("keydown", handleDialogKeys);
      document.body.style.overflow = previousOverflow;
      if (previousFocus?.isConnected) previousFocus.focus();
    };
  }, [successDialog]);

  useEffect(() => {
    if (challenge?.kind === "email_mfa_required") {
      setLoginMfaChallenge(challenge.challenge);
      setView("email-mfa");
      setResendAvailableAt(Date.parse(challenge.challenge.resendAvailableAt));
    } else if (challenge?.kind === "email_not_verified") {
      setView("verify");
      setEmail(challenge.email);
    }
  }, [challenge]);

  useEffect(() => {
    if (view !== "verify" || !verificationToken || verificationStarted.current) return;
    verificationStarted.current = true;
    setBusy(true);
    setError("");
    void completeRegistrationVerificationByLink(verificationToken)
      .then(() => setMessage("Your email is verified. Opening your dashboard…"))
      .catch((verificationError: unknown) => {
        setError(getUserMessage(verificationError, "This verification link is invalid or has expired."));
      })
      .finally(() => setBusy(false));
  }, [completeRegistrationVerificationByLink, verificationToken, view]);

  useEffect(() => {
    if (resendAvailableAt === null) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [resendAvailableAt]);

  function changeView(next: AuthView) {
    setView(next);
    setError("");
    setMessage("");
    setCode("");
    setValidationAttempted(false);
    setLoginAttempted(false);
    setIdentifierTouched(false);
    setPasswordTouched(false);
    setRegistrationAttempted(false);
  }

  async function submitLogin(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setLoginAttempted(true);
    setValidationAttempted(true);
    setError("");
    setAuthFailed(false);
    if (!email.trim() || !password) return;
    setBusy(true);
    try {
      await login(email.trim(), password);
    } catch (loginError) {
      if (loginError instanceof ApiError && loginError.code === "MFA_ENROLLMENT_REQUIRED"
        && loginError.mfaEnrollmentToken) {
        try {
          setEnrollmentToken(loginError.mfaEnrollmentToken);
          setEnrollment(await authApi.beginMfaEnrollmentChallenge(loginError.mfaEnrollmentToken));
          setValidationAttempted(false);
          setLoginAttempted(false);
          setView("enroll-mfa");
        } catch (enrollmentError) {
          setEnrollmentToken("");
          setError(getUserMessage(enrollmentError, "Authenticator setup could not be started."));
        }
      } else if (loginError instanceof ApiError && loginError.code === "EMAIL_NOT_VERIFIED") {
        setEmail(loginError.verificationEmail || email.trim());
        setResendAvailableAt(loginError.verificationResendAvailableAt
          ? Date.parse(loginError.verificationResendAvailableAt)
          : null);
        setValidationAttempted(false);
        setLoginAttempted(false);
        setView("verify");
      } else if (!(loginError instanceof ApiError && loginError.code === "LOGIN_EMAIL_MFA_REQUIRED")) {
        if (loginError instanceof ApiError
          && (loginError.code === "INVALID_CREDENTIALS" || loginError.code === "LOGIN_FAILED" || loginError.status === 401)) {
          setAuthFailed(true);
          setError("Invalid email or password.");
          if (typeof navigator !== "undefined" && "vibrate" in navigator) {
            navigator.vibrate([60, 35, 60]);
          }
        } else {
          setError(getUserMessage(loginError, "Sign in failed. Please try again."));
        }
      }
    } finally {
      setBusy(false);
    }
  }

  async function submitRegistration(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setRegistrationAttempted(true);
    setValidationAttempted(true);
    setError("");
    if (!firstName.trim() || !lastName.trim() || !isEmailValid || !password
      || !confirmPassword || password !== confirmPassword
      || !acceptedTerms) return;
    const parsedPhoneNumber = parsePhoneNumberFromString(phoneNumber, phoneCountry);
    if (!parsedPhoneNumber?.isValid()) return;
    setBusy(true);
    try {
      const displayName = `${firstName.trim()} ${lastName.trim()}`.trim();
      const result = await register(
        email.trim(),
        password,
        displayName,
        "Personal workspace",
        undefined,
        acceptedTerms,
        undefined,
        parsedPhoneNumber.number,
      );
      setEmail(result.email);
      if (result.verificationRequired) {
        setResendAvailableAt(result.verificationResendAvailableAt ? Date.parse(result.verificationResendAvailableAt) : null);
        setMessage(`Enter the verification code sent to ${result.email} to finish creating your account.`);
        setValidationAttempted(false);
        setRegistrationAttempted(false);
        setView("verify");
      } else {
        setMessage("Your personal workspace is ready. Sign in to continue.");
        setPassword("");
        setConfirmPassword("");
        setValidationAttempted(false);
        setRegistrationAttempted(false);
        setView("login");
      }
    } catch (registrationError) {
      setError(registrationError instanceof ApiError && registrationError.code === "USERNAME_REQUIRED"
        ? "Your account setup could not be completed. Please try again."
        : getUserMessage(registrationError, "Registration failed. Please try again."));
    } finally {
      setBusy(false);
    }
  }

  async function submitVerification(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setValidationAttempted(true);
    if (!isEmailValid || !/^\d{6}$/.test(code)) return;
    setBusy(true);
    setError("");
    try {
      if (password) {
        await completeRegistrationVerification(email.trim(), code, password);
      } else {
        await authApi.verifyEmail(email.trim(), code);
        setMessage("Your email is verified. Sign in to continue.");
        setView("login");
      }
    } catch (verificationError) {
      setError(getUserMessage(verificationError, "Email verification failed. Try again or request a new code."));
    } finally {
      setBusy(false);
    }
  }

  async function submitEmailMfa(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setValidationAttempted(true);
    if (!loginMfaChallenge || !/^\d{6}$/.test(code)) return;
    setBusy(true);
    setError("");
    try {
      await verifyLoginEmailMfa(loginMfaChallenge.challengeId, code);
    } catch (verificationError) {
      setError(getUserMessage(verificationError, "The sign-in code could not be verified."));
      setCode("");
    } finally {
      setBusy(false);
    }
  }

  async function resendCode() {
    setError("");
    setBusy(true);
    try {
      if (view === "email-mfa" && loginMfaChallenge) {
        const updated = await resendLoginEmailMfa(loginMfaChallenge.challengeId);
        setLoginMfaChallenge(updated);
        setResendAvailableAt(Date.parse(updated.resendAvailableAt));
        setCode("");
      } else {
        await authApi.resendEmailVerification(email.trim());
        setResendAvailableAt(Date.now() + 60_000);
        setMessage("If the address can receive a code, a new verification email is on its way.");
      }
    } catch (resendError) {
      setError(getUserMessage(resendError, "A new code could not be sent."));
    } finally {
      setBusy(false);
    }
  }

  async function submitForgotPassword(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setValidationAttempted(true);
    if (!isEmailValid) return;
    setBusy(true);
    setError("");
    setMessage("");
    try {
      await authApi.forgotPassword({ email: email.trim() });
      setMessage(
        "If an account matches this email, a password reset link is on the way. Check your inbox and spam folder.",
      );
    } catch (requestError) {
      setError(getUserMessage(requestError, "Password recovery could not be started."));
    } finally {
      setBusy(false);
    }
  }

  async function submitPasswordReset(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setValidationAttempted(true);
    if (!resetToken) {
      setError("This password reset link is missing its token. Request a new reset email.");
      return;
    }
    if (!password || !confirmPassword || password !== confirmPassword) return;
    setBusy(true);
    setError("");
    setMessage("");
    try {
      await authApi.resetPassword({ token: resetToken, newPassword: password });
      setPassword("");
      setConfirmPassword("");
      setValidationAttempted(false);
      setSuccessDialog("changed");
    } catch (resetError) {
      setError(getUserMessage(resetError, "Password reset failed. Request a new reset link."));
    } finally {
      setBusy(false);
    }
  }

  async function submitEnrollment(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setValidationAttempted(true);
    if (!enrollmentToken) {
      setError("Enter the six-digit code from your authenticator app.");
      return;
    }
    if (!/^\d{6}$/.test(code)) return;
    setBusy(true);
    setError("");
    try {
      const result = await authApi.confirmMfaEnrollmentChallenge(code, enrollmentToken);
      setRecoveryCodes(result.codes);
      setEnrollmentToken("");
      setEnrollment(null);
      setCode("");
    } catch (enrollmentError) {
      setError(getUserMessage(enrollmentError, "The authenticator code could not be verified."));
    } finally {
      setBusy(false);
    }
  }

  const viewMode = view === "register" ? "register" : "login";
  const formTitle = {
    login: "Welcome back",
    register: "Sign up",
    verify: "Verify your email",
    "email-mfa": "OTP Verification",
    forgot: "Reset your password",
    reset: "Change Password",
    "enroll-mfa": recoveryCodes.length ? "Save your recovery codes" : "Set up an authenticator",
    locked: "Verification temporarily locked",
  }[view];
  const subtitle = {
    login: "Use your email address and password to sign in.",
    register: "Create your personal PesaGuard account.",
    verify: `Enter the six-digit code sent to ${email || "your email address"}.`,
    "email-mfa": `We sent a One Time Password to ${loginMfaChallenge?.maskedEmail ?? (challenge?.kind === "email_mfa_required" ? challenge.challenge.maskedEmail : "your email address")}.`,
    forgot: "Enter the email address you used to register. We’ll send a password reset link if it matches an account.",
    reset: "We are very cautious about protecting your information. Please reset your password below.",
    "enroll-mfa": recoveryCodes.length
      ? "Store these codes somewhere safe. Each code can only be used once."
      : "Add an authenticator app to protect your developer account.",
    locked: "Too many attempts were made. Request a fresh verification code to continue.",
  }[view];

  function alertContent() {
    return <>
      {message && <p className="public-auth-notice" role="status">{message}</p>}
      {error && <p className="public-auth-error" role="alert">{error}</p>}
    </>;
  }

  let form: ReactNode;
  if (view === "login") {
    const identifierIsReady = Boolean(email.trim() && password);
    const identifierInvalid = ((loginAttempted || identifierTouched) && !email.trim()) || authFailed;
    const passwordInvalid = ((loginAttempted || passwordTouched) && !password) || authFailed;
    form = <form className={`public-auth-form public-auth-login-form${authFailed ? " is-auth-failed" : ""}`} onSubmit={(event) => void submitLogin(event)} aria-busy={busy} noValidate>
      {alertContent()}
      <label className="public-auth-login-label">
        <span>Email or username</span>
        <span className={`public-auth-input-wrap public-auth-identifier-wrap${identifierIsReady ? " is-ready" : ""}${identifierInvalid ? " is-invalid" : ""}`}>
          <input autoComplete="username" type="text" name="email" placeholder="Email or username" value={email} onBlur={() => setIdentifierTouched(true)} onChange={(event) => { setEmail(event.target.value); setAuthFailed(false); setError(""); }} aria-invalid={identifierInvalid || undefined} aria-describedby={identifierInvalid && !authFailed ? "public-login-identifier-error" : undefined} />
          {email && <button className="public-auth-input-action" type="button" aria-label="Clear email or username" onClick={() => { setEmail(""); setAuthFailed(false); setError(""); }}><X size={17} aria-hidden="true" /></button>}
          <span className="public-auth-input-line" aria-hidden="true" />
        </span>
        {identifierInvalid && !authFailed && <small className="public-auth-field-error" id="public-login-identifier-error" role="alert">Please enter your email or username</small>}
      </label>
      <label className="public-auth-login-label">
        <span>Password</span>
        <span className={`public-auth-input-wrap${passwordInvalid ? " is-invalid" : ""}`}>
          <input autoComplete="current-password" type={showPassword ? "text" : "password"} name="password" placeholder="Password" value={password} onBlur={() => setPasswordTouched(true)} onChange={(event) => { setPassword(event.target.value); setAuthFailed(false); setError(""); }} aria-invalid={passwordInvalid || undefined} aria-describedby={passwordInvalid && !authFailed ? "public-login-password-error" : undefined} />
          <button className="public-auth-input-action" type="button" aria-label={showPassword ? "Hide password" : "Show password"} aria-pressed={showPassword} onClick={() => setShowPassword((shown) => !shown)}>
            {showPassword ? <EyeOff size={17} aria-hidden="true" /> : <Eye size={17} aria-hidden="true" />}
          </button>
          <span className="public-auth-input-line" aria-hidden="true" />
        </span>
        {passwordInvalid && !authFailed && <small className="public-auth-field-error" id="public-login-password-error" role="alert">Please enter your password</small>}
      </label>
      <button className="public-auth-submit" type="submit" disabled={busy || status === "authenticating" || !identifierIsReady}>
        {busy ? <><LoaderCircle className="public-auth-spinner" size={17} aria-hidden="true" /> Signing in…</> : "Login"}
      </button>
      <p className="public-auth-login-forgot"><a href="/forgot-password">Forgot your password?</a></p>
      <p className="public-auth-switch">Don't have an account yet? <a href="/register">Sign up</a></p>
    </form>;
  } else if (view === "register") {
    form = <form className="public-auth-form public-auth-register-form" onSubmit={(event) => void submitRegistration(event)} aria-busy={busy} noValidate>
      {alertContent()}
      <div className="public-auth-register-grid">
        <label className="public-auth-register-field">First name
          <span className="public-auth-register-input-wrap"><input autoComplete="given-name" name="given-name" required value={firstName} onChange={(event) => setFirstName(event.target.value)} placeholder="First name" aria-invalid={registrationAttempted && !firstName.trim() || undefined} />
            {firstName && <button type="button" aria-label="Clear first name" onClick={() => setFirstName("")}><X size={16} aria-hidden="true" /></button>}
          </span>
          {registrationAttempted && !firstName.trim() && <small className="public-auth-field-error" role="alert">Please enter your first name.</small>}
        </label>
        <label className="public-auth-register-field">Last name
          <span className="public-auth-register-input-wrap"><input autoComplete="family-name" name="family-name" required value={lastName} onChange={(event) => setLastName(event.target.value)} placeholder="Last name" aria-invalid={registrationAttempted && !lastName.trim() || undefined} />
            {lastName && <button type="button" aria-label="Clear last name" onClick={() => setLastName("")}><X size={16} aria-hidden="true" /></button>}
          </span>
          {registrationAttempted && !lastName.trim() && <small className="public-auth-field-error" role="alert">Please enter your last name.</small>}
        </label>
        <label className="public-auth-register-field">Email address
          <span className="public-auth-register-input-wrap"><input autoComplete="email" type="email" name="email" required value={email} onChange={(event) => setEmail(event.target.value)} placeholder="Email address" aria-invalid={registrationAttempted && !isEmailValid || undefined} />
            {email && <button type="button" aria-label="Clear email address" onClick={() => setEmail("")}><X size={16} aria-hidden="true" /></button>}
          </span>
          {registrationAttempted && !email.trim() && <small className="public-auth-field-error" role="alert">Please enter your email address.</small>}
          {registrationAttempted && email.trim() && !isEmailValid && <small className="public-auth-field-error" role="alert">Enter a valid email address.</small>}
        </label>
        <div className="public-auth-register-phone-group">
          <label className="public-auth-register-field public-auth-register-country-field">Country
            <select name="phone-country" value={phoneCountry} onChange={(event) => {
              const selectedCountry = phoneCountries.find(({ country }) => country === event.target.value)?.country;
              if (selectedCountry) setPhoneCountry(selectedCountry);
            }} required>
              {phoneCountries.map(({ country, name, callingCode }) =>
                <option key={country} value={country}>{name} (+{callingCode})</option>)}
            </select>
          </label>
          <label className="public-auth-register-field">Phone number
            <span className="public-auth-register-input-wrap"><input autoComplete="tel-national" type="tel" name="phone" required value={phoneNumber} onChange={(event) => setPhoneNumber(event.target.value)} placeholder="Phone number" aria-invalid={registrationAttempted && !phoneIsValid || undefined} />
              {phoneNumber && <button type="button" aria-label="Clear phone number" onClick={() => setPhoneNumber("")}><X size={16} aria-hidden="true" /></button>}
            </span>
            {registrationAttempted && !phoneIsValid && <small className="public-auth-field-error" role="alert">{phoneNumber ? "Enter a valid phone number for the selected country." : "Please enter your phone number."}</small>}
          </label>
        </div>
        <label className="public-auth-register-field">Password
          <span className="public-auth-register-input-wrap"><input autoComplete="new-password" type={showPassword ? "text" : "password"} name="new-password" required value={password} onChange={(event) => setPassword(event.target.value)} placeholder="Password" aria-invalid={registrationAttempted && !password || undefined} />
            <button type="button" aria-label={showPassword ? "Hide password" : "Show password"} aria-pressed={showPassword} onClick={() => setShowPassword((shown) => !shown)}>{showPassword ? <EyeOff size={17} aria-hidden="true" /> : <Eye size={17} aria-hidden="true" />}</button>
          </span>
          {registrationAttempted && !password && <small className="public-auth-field-error" role="alert">Please enter a password.</small>}
        </label>
        <label className="public-auth-register-field">Confirm password
          <span className="public-auth-register-input-wrap"><input autoComplete="new-password" type={showConfirmPassword ? "text" : "password"} name="confirm-password" required value={confirmPassword} onChange={(event) => setConfirmPassword(event.target.value)} placeholder="Confirm password" aria-invalid={registrationAttempted && (!confirmPassword || password !== confirmPassword) || undefined} />
            <button type="button" aria-label={showConfirmPassword ? "Hide password" : "Show password"} aria-pressed={showConfirmPassword} onClick={() => setShowConfirmPassword((shown) => !shown)}>{showConfirmPassword ? <EyeOff size={17} aria-hidden="true" /> : <Eye size={17} aria-hidden="true" />}</button>
          </span>
          {registrationAttempted && !confirmPassword && <small className="public-auth-field-error" role="alert">Please confirm your password.</small>}
          {registrationAttempted && confirmPassword && password !== confirmPassword && <small className="public-auth-field-error" role="alert">Passwords do not match.</small>}
        </label>
      </div>
      <label className="public-auth-checkbox">
        <input type="checkbox" required checked={acceptedTerms} onChange={(event) => setAcceptedTerms(event.target.checked)} aria-invalid={registrationAttempted && !acceptedTerms || undefined} />
        <span>I agree to the <a href="/terms">Terms</a> and <a href="/privacy">Privacy Policy</a>.</span>
      </label>
      {registrationAttempted && !acceptedTerms && <small className="public-auth-field-error public-auth-terms-error" role="alert">Accept the terms to create an account.</small>}
      <button className="public-auth-submit" type="submit" disabled={busy || status === "authenticating" || !acceptedTerms}>{busy ? "Creating account…" : "Sign up"}</button>
      <p className="public-auth-switch">Already have an account? <a href="/login">Log in</a></p>
    </form>;
  } else if (view === "verify" || view === "email-mfa") {
    form = <form className="public-auth-form" onSubmit={(event) => void (view === "email-mfa" ? submitEmailMfa(event) : submitVerification(event))} aria-busy={busy} noValidate>
      {alertContent()}
      {view === "verify" && <>
        <label>Email address<input autoComplete="email" type="email" name="email" required value={email} onChange={(event) => setEmail(event.target.value)} aria-invalid={validationAttempted && !isEmailValid || undefined} /></label>
        {validationAttempted && !isEmailValid && <small className="public-auth-field-error" role="alert">{email.trim() ? "Enter a valid email address." : "Please enter your email address."}</small>}
      </>}
      <SixDigitCodeInput
        label={view === "email-mfa" ? "Please enter OTP" : "Email verification code"}
        digits={codeDigits}
        onChange={setCodeDigits}
        disabled={busy}
        invalid={validationAttempted && code.length !== 6}
      />
      {validationAttempted && code.length !== 6 && <small className="public-auth-field-error" role="alert">
        {view === "email-mfa" ? "Enter the six-digit sign-in code." : "Enter the six-digit code from your email."}
      </small>}
      <button className="public-auth-submit" type="submit" disabled={busy}>
        {busy ? "Verifying…" : view === "email-mfa" ? "Verify OTP" : "Verify and continue"}
        {view !== "email-mfa" && <ArrowRight size={17} aria-hidden="true" />}
      </button>
      {view === "email-mfa" ? <>
        <p className="public-auth-otp-resend">
          Have a problem with verification?{" "}
          <button className="public-auth-otp-link" type="button" onClick={() => void resendCode()} disabled={busy || resendSeconds > 0}>
            {resendSeconds > 0 ? `Resend OTP in ${resendSeconds}s` : "Resend OTP"}
          </button>
        </p>
        <p className="public-auth-otp-switch">
          <button className="public-auth-otp-link" type="button" onClick={() => changeView("login")}>Use another account</button>
        </p>
      </> : <>
        <button className="public-auth-secondary" type="button" onClick={() => void resendCode()} disabled={busy || resendSeconds > 0}>
          {resendSeconds > 0 ? `Send another code in ${resendSeconds}s` : "Send another code"}
        </button>
        <p className="public-auth-switch"><a href="/login">Back to sign in</a></p>
      </>}
    </form>;
  } else if (view === "forgot") {
    form = <form className="public-auth-form" onSubmit={(event) => void submitForgotPassword(event)} aria-busy={busy} noValidate>
      {alertContent()}
      <label>Email address<input autoComplete="email" autoFocus type="email" name="email" required value={email} onChange={(event) => { setEmail(event.target.value); setError(""); setMessage(""); }} aria-invalid={Boolean(forgotEmailError) || undefined} aria-describedby={forgotEmailError ? "public-forgot-email-error" : undefined} /></label>
      {forgotEmailError && <small className="public-auth-field-error" id="public-forgot-email-error" role="alert">{forgotEmailError}</small>}
      <button className="public-auth-submit" type="submit" disabled={busy}>{busy ? "Sending…" : "Reset Password"}</button>
      <p className="public-auth-switch">No account yet? <a href="/register">Create one</a> · <a href="/login">Back to sign in</a></p>
    </form>;
  } else if (view === "reset") {
    form = <form className="public-auth-form" onSubmit={(event) => void submitPasswordReset(event)} aria-busy={busy} noValidate>
      {alertContent()}
      {!resetToken && <p className="public-auth-error" role="alert">This reset link is incomplete. <a href="/forgot-password">Request another reset email</a>.</p>}
      <label className="public-auth-password-label"><span>New password</span><span className="public-auth-password-input"><input autoComplete="new-password" type={showPassword ? "text" : "password"} name="new-password" placeholder="New password" required value={password} onChange={(event) => { setPassword(event.target.value); setError(""); }} aria-invalid={Boolean(resetPasswordError) || undefined} aria-describedby={resetPasswordError ? "public-reset-password-error" : undefined} /><button type="button" aria-label={showPassword ? "Hide password" : "Show password"} aria-pressed={showPassword} onClick={() => setShowPassword((shown) => !shown)}>{showPassword ? <EyeOff size={17} aria-hidden="true" /> : <Eye size={17} aria-hidden="true" />}</button></span>
        {resetPasswordError && <small className="public-auth-field-error" id="public-reset-password-error" role="alert">{resetPasswordError}</small>}
      </label>
      <label className="public-auth-password-label"><span>Confirm new password</span><span className="public-auth-password-input"><input autoComplete="new-password" type={showConfirmPassword ? "text" : "password"} name="confirm-password" placeholder="Confirm password" required value={confirmPassword} onChange={(event) => { setConfirmPassword(event.target.value); setError(""); }} aria-invalid={Boolean(resetConfirmationError) || undefined} aria-describedby={resetConfirmationError ? "public-reset-confirm-error" : undefined} /><button type="button" aria-label={showConfirmPassword ? "Hide confirmation password" : "Show confirmation password"} aria-pressed={showConfirmPassword} onClick={() => setShowConfirmPassword((shown) => !shown)}>{showConfirmPassword ? <EyeOff size={17} aria-hidden="true" /> : <Eye size={17} aria-hidden="true" />}</button></span>
        {resetConfirmationError && <small className="public-auth-field-error" id="public-reset-confirm-error" role="alert">{resetConfirmationError}</small>}
      </label>
      <button className="public-auth-submit" type="submit" disabled={busy || !resetToken}>{busy ? "Updating password…" : "Reset Password"}</button>
      <p className="public-auth-switch public-auth-reset-contact">Have a problem with password reset? <a href="mailto:support@pesaguard.co.ke">Contact us</a></p>
    </form>;
  } else if (view === "enroll-mfa") {
    form = <div className="public-auth-form">
      {alertContent()}
      {recoveryCodes.length ? <>
        <div className="public-auth-recovery-codes" aria-label="MFA recovery codes">{recoveryCodes.map((recoveryCode) => <code key={recoveryCode}>{recoveryCode}</code>)}</div>
        <button className="public-auth-submit" type="button" onClick={() => { setRecoveryCodes([]); setView("login"); setPassword(""); setMessage("Authenticator configured. Sign in with your password and authenticator code."); }}>
          I’ve saved my codes <Check size={17} aria-hidden="true" />
        </button>
      </> : <>
        <p className="public-auth-mfa-instructions">Add this authenticator setup URI to your app, then enter its current six-digit code.</p>
        {enrollment && <label>Authenticator setup URI<input readOnly value={enrollment.provisioningUri} /></label>}
        {enrollment && <label>Manual setup key<input readOnly value={enrollment.secret} /></label>}
        <form className="public-auth-nested-form" onSubmit={(event) => void submitEnrollment(event)} noValidate>
          <label>Authenticator code<input autoComplete="one-time-code" inputMode="numeric" pattern="[0-9]{6}" maxLength={6} required value={code} onChange={(event) => setCode(event.target.value.replace(/\D/g, "").slice(0, 6))} aria-invalid={validationAttempted && code.length !== 6 || undefined} />
            {validationAttempted && code.length !== 6 && <small className="public-auth-field-error" role="alert">Enter the six-digit code from your authenticator app.</small>}
          </label>
          <button className="public-auth-submit" type="submit" disabled={busy}>{busy ? "Confirming…" : "Confirm authenticator"} <ArrowRight size={17} aria-hidden="true" /></button>
        </form>
      </>}
    </div>;
  } else {
    form = <div className="public-auth-form">{alertContent()}<p>To protect your account, wait briefly, then request a new verification code.</p><button className="public-auth-secondary" type="button" onClick={() => changeView("verify")}>Verify email</button></div>;
  }

  return <>
    <AuthFrame
      mode={viewMode}
      loginLanding={view === "login" || view === "email-mfa" || view === "verify"}
      emailMfa={view === "email-mfa" || view === "verify"}
      flowView={view !== "login" && view !== "register" && view !== "email-mfa" && view !== "verify"}
      flowFooterInsideLayout={view === "forgot" || view === "reset"}
      flowFooterAfterPanel={view === "reset" ? <p className="public-auth-reset-back"><a href="/login">←&nbsp; Back to Login</a></p> : undefined}
      passwordReset={view === "reset"}
      loginFailed={view === "login" && authFailed}
      title={view === "reset" ? <><span>Change</span> Password</> : formTitle}
      subtitle={subtitle}
      footer={view === "enroll-mfa" || view === "locked" ? <a href="/login">Return to sign in</a> : undefined}
    >{form}</AuthFrame>
    {successDialog && (
      <div className="public-auth-success-overlay">
        <section className="public-auth-success-dialog" role="alertdialog" aria-modal="true" aria-labelledby="public-auth-success-title" aria-describedby="public-auth-success-description">
          <span className="public-auth-success-mark" aria-hidden="true"><Check size={48} strokeWidth={4} /></span>
          <h2 id="public-auth-success-title">
            <><span>Password</span> Changed</>
          </h2>
          <p id="public-auth-success-description">
            <>Your password has been changed successfully.<br />Click proceed to log in.</>
          </p>
          <a ref={(element) => { successActionRef.current = element; }} className="public-auth-success-action" href="/login">Proceed</a>
        </section>
      </div>
    )}
  </>;
}

export function LoginPage({ initialView = "login" }: { initialView?: AuthView }) {
  return <AuthPage key={initialView} mode="login" initialView={initialView} />;
}

export function RegisterPage() {
  return <AuthPage mode="register" />;
}
