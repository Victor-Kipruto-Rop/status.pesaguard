import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import type { AuthChallenge, AuthOrganization, AuthSession, AuthStatus, AuthUser, LoginEmailMfaChallenge, RegistrationResponse } from "../types/auth";
import { ApiError, onSessionRefreshed, onSignedOut, safeUserErrorMessage } from "../lib/api";
import * as authApi from "../lib/authApi";
import {
  clearSession,
  loadSession,
  onRemoteAuthChange,
  persistSession,
  publishSignedIn,
  publishSignedOut,
  updateStoredSessionUser,
} from "../lib/session";

interface AuthContextValue {
  user: AuthUser | null;
  organization: AuthOrganization | null;
  sessionExpiresAt: string | null;
  permissions: string[];
  permissionsLoaded: boolean;
  welcomePending: boolean;
  hasPermission: (permission: string) => boolean;
  consumeWelcome: () => void;
  status: AuthStatus;
  isAuthenticated: boolean;
  login: (email: string, password: string, organizationId?: string) => Promise<void>;
  verifyLoginEmailMfa: (challengeId: string, code: string) => Promise<void>;
  completeRegistrationVerification: (email: string, code: string, password: string) => Promise<void>;
  completeRegistrationVerificationByLink: (token: string) => Promise<void>;
  resendLoginEmailMfa: (challengeId: string) => Promise<LoginEmailMfaChallenge>;
  switchWorkspace: (workspaceId: string) => Promise<void>;
  acceptSession: (session: AuthSession) => void;
  register: (email: string, password: string, displayName: string, organizationName: string, organizationDescription?: string, termsAccepted?: boolean, username?: string, phoneNumber?: string) => Promise<RegistrationResponse>;
  logout: () => Promise<void>;
  /** Re-reads identity from the API. Kept for a manual "retry" affordance. */
  reload: () => Promise<void>;
  /** Outstanding step blocking sign-in, or null when the next action is credentials. */
  challenge: AuthChallenge;
  error: string | null;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

/**
 * Maps a login failure onto the challenge it represents, or null for a real failure.
 *
 * <p>Reads only the backend's own `code`. Nothing is inferred from the status
 * number or the message text: login challenge codes arrive as 401 and
 * INVALID_CREDENTIALS arrives as 401, and a 401 also means "your token died"
 * on every other endpoint.
 * Guessing from either would show a verification-code prompt to a user who typed
 * the wrong password.
 */
function readChallenge(caught: unknown, email: string): AuthChallenge {
  if (!(caught instanceof ApiError)) {
    return null;
  }
  switch (caught.code) {
    case "LOGIN_EMAIL_MFA_REQUIRED":
      return caught.loginChallengeId && caught.loginChallengeExpiresAt
        && caught.loginChallengeResendAvailableAt && caught.maskedLoginEmail
        ? {
            kind: "email_mfa_required",
            challenge: {
              challengeId: caught.loginChallengeId,
              expiresAt: caught.loginChallengeExpiresAt,
              resendAvailableAt: caught.loginChallengeResendAvailableAt,
              maskedEmail: caught.maskedLoginEmail,
            },
          }
        : null;
    case "EMAIL_NOT_VERIFIED":
      return {
        kind: "email_not_verified",
        email: caught.verificationEmail || email,
      };
    default:
      return null;
  }
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const initial = loadSession();
  const [session, setSession] = useState(initial);
  const [permissions, setPermissions] = useState<string[]>([]);
  const [permissionsLoaded, setPermissionsLoaded] = useState(false);
  const [welcomePending, setWelcomePending] = useState(false);
  const [status, setStatus] = useState<AuthStatus>(initial ? "checking" : "guest");
  const [error, setError] = useState<string | null>(null);
  const [challenge, setChallenge] = useState<AuthChallenge>(null);
  const logoutInProgress = useRef(false);

  const adopt = useCallback((next: typeof session) => {
    setSession(next);
    setStatus("authenticated");
    setError(null);
    setPermissions([]);
    setPermissionsLoaded(false);
    void authApi.currentSession()
      .then((current) => setPermissions(current.authorities.filter((authority) => !authority.startsWith("ROLE_"))))
      .catch(() => setPermissions([]))
      .finally(() => setPermissionsLoaded(true));
  }, []);

  const dropToGuest = useCallback(() => {
    setSession(null);
    setPermissions([]);
    setPermissionsLoaded(false);
    setChallenge(null);
    setError(null);
    setStatus("guest");
  }, []);

  const clearLocalAuthState = useCallback(() => {
    clearSession();
    setChallenge(null);
    setError(null);
    dropToGuest();

    const sessionKeys = Object.keys(window.sessionStorage)
      .filter((key) => key.startsWith("pesaguard."));
    for (const key of sessionKeys) window.sessionStorage.removeItem(key);

    const localContextKeys = Object.keys(window.localStorage)
      .filter((key) => key === "pesaguard.active-project-id"
        || key.startsWith("pesaguard.active-environment."));
    for (const key of localContextKeys) window.localStorage.removeItem(key);
  }, [dropToGuest]);

  // Confirm a stored session against the API on mount.
  //
  // Local storage alone is not proof of a live session: the token may have
  // expired, been revoked from another device, or the account suspended. Without
  // this check the app would render an authenticated shell and then fail every
  // request inside it.
  useEffect(() => {
    if (!session) {
      setStatus("guest");
      return;
    }
    let cancelled = false;
    authApi
      .currentSession()
      .then((current) => {
        if (!cancelled) {
          setPermissions(current.authorities.filter((authority) => !authority.startsWith("ROLE_")));
          setPermissionsLoaded(true);
          setStatus("authenticated");
        }
      })
      .catch(() => {
        // apiFetch already attempted one refresh and, if that failed, cleared the
        // session and notified listeners. So there is nothing left to do here
        // beyond landing on the guest screen.
        if (!cancelled) {
          dropToGuest();
        }
      });
    return () => {
      cancelled = true;
    };
    // Intentionally depends only on presence, not on the token itself: re-running
    // on every rotation would call /auth/session on every refresh.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session?.accessToken ? "has-token" : "no-token"]);

  // A rotation in this tab updates the visible identity.
  useEffect(
    () =>
      onSessionRefreshed((stored) => {
        adopt(stored);
      }),
    [adopt],
  );

  // A session that dies anywhere in this tab signs the UI out.
  useEffect(
    () =>
      onSignedOut(() => {
        clearLocalAuthState();
        if (window.location.pathname !== "/login") window.location.replace("/login");
      }),
    [clearLocalAuthState],
  );

  // Another tab signed in, signed out, or rotated. localStorage is shared, so
  // adopting the stored session keeps every tab consistent instead of one tab
  // continuing to use a token another tab has already spent.
  useEffect(
    () =>
      onRemoteAuthChange((message) => {
        if (message.type === "signed-out") {
          clearLocalAuthState();
          if (window.location.pathname !== "/login") window.location.replace("/login");
          return;
        }
        if (message.session.accessToken !== session?.accessToken) {
          adopt(message.session);
        }
      }),
    [adopt, clearLocalAuthState, session?.accessToken],
  );

  const login = useCallback(
    async (email: string, password: string, organizationId?: string) => {
      setError(null);
      setChallenge(null);
      setStatus("authenticating");
      try {
        const result = await authApi.login({
          email,
          password,
          ...(organizationId ? { organizationId } : {}),
        });
        const stored = persistSession(result);
        publishSignedIn(stored);
        adopt(stored);
      } catch (caught) {
        // The backend distinguishes "wrong password" from "password was right but
        // something else is outstanding". Treating the second as a failure makes
        // the portal ask for the password again and lose the user's progress.
        const challenge = readChallenge(caught, email);
        if (challenge) {
          setChallenge(challenge);
          setStatus(
            challenge.kind === "email_mfa_required"
              ? "email_mfa_required"
              : "unverified_email",
          );
          setError(null);
          throw caught;
        }
        setStatus("guest");
        setError(safeUserErrorMessage(caught, "Sign in failed. Please try again."));
        throw caught;
      }
    },
    [adopt],
  );

  const verifyLoginEmailMfa = useCallback(
    async (challengeId: string, code: string) => {
      setError(null);
      setStatus("authenticating");
      try {
        const result = await authApi.verifyLoginEmailMfa(challengeId, code);
        const stored = persistSession(result);
        publishSignedIn(stored);
        setChallenge(null);
        adopt(stored);
      } catch (caught) {
        setStatus("email_mfa_required");
        setError(safeUserErrorMessage(caught, "Verification failed. Please try again."));
        throw caught;
      }
    },
    [adopt],
  );

  const completeRegistrationVerification = useCallback(
    async (email: string, code: string, password: string) => {
      setError(null);
      setStatus("authenticating");
      try {
        const result = await authApi.completeRegistrationVerification(email, code, password);
        const stored = persistSession(result);
        publishSignedIn(stored);
        setWelcomePending(true);
        setChallenge(null);
        adopt(stored);
      } catch (caught) {
        setStatus("guest");
        setError(safeUserErrorMessage(caught, "Verification failed. Please try again."));
        throw caught;
      }
    },
    [adopt],
  );

  const completeRegistrationVerificationByLink = useCallback(
    async (token: string) => {
      setError(null);
      setStatus("authenticating");
      try {
        const result = await authApi.completeRegistrationVerificationByLink(token);
        const stored = persistSession(result);
        publishSignedIn(stored);
        setWelcomePending(true);
        setChallenge(null);
        adopt(stored);
      } catch (caught) {
        setStatus("guest");
        setError(safeUserErrorMessage(caught, "Email verification could not be completed."));
        throw caught;
      }
    },
    [adopt],
  );

  const resendLoginEmailMfa = useCallback(
    async (challengeId: string) => authApi.resendLoginEmailMfa(challengeId),
    [],
  );

  const consumeWelcome = useCallback(() => setWelcomePending(false), []);

  const acceptSession = useCallback((result: AuthSession) => {
    const stored = persistSession(result);
    publishSignedIn(stored);
    adopt(stored);
  }, [adopt]);

  const switchWorkspace = useCallback(async (workspaceId: string) => {
    const next = await authApi.switchWorkspace(workspaceId);
    const stored = persistSession(next);
    for (const key of ["pesaguard.project.id", "pesaguard.project.name", "pesaguard.environment.id",
      "pesaguard.environment.name", "pesaguard.environment.type", "pesaguard.environment.project",
      "pesaguard.environment.project-id"]) {
      window.sessionStorage.removeItem(key);
    }
    publishSignedIn(stored);
    adopt(stored);
    // Many screens load their data once per mount. A hard navigation ensures no
    // data cached under the previous tenant remains visible after switching.
    window.location.reload();
  }, [adopt]);

  const register = useCallback(
    async (
      email: string,
      password: string,
      displayName: string,
      organizationName: string,
      organizationDescription?: string,
      termsAccepted = true,
      username?: string,
      phoneNumber?: string,
    ) => {
      setError(null);
      setStatus("authenticating");
      try {
        const result = await authApi.register({
          email,
          password,
          displayName,
          organizationName: organizationName.trim(),
          ...(organizationDescription ? { organizationDescription } : {}),
          termsAccepted,
          ...(username !== undefined ? { username } : {}),
          ...(phoneNumber ? { phoneNumber } : {}),
        });
        setStatus("guest");
        return result;
      } catch (caught) {
        setStatus("guest");
        setError(safeUserErrorMessage(caught, "Registration failed. Please try again."));
        throw caught;
      }
    },
    [adopt],
  );

  const logout = useCallback(async () => {
    if (logoutInProgress.current) return;
    logoutInProgress.current = true;
    let serverRevoked = false;
    try {
      await authApi.logout();
      serverRevoked = true;
    } catch (logoutError) {
      // The local session still ends, but report that server-side revocation
      // could not be confirmed on the next sign-in screen.
      console.warn("Server-side logout could not be confirmed.");
    } finally {
      clearLocalAuthState();
      publishSignedOut();
      window.location.replace(serverRevoked ? "/login" : "/login?logout=unconfirmed");
    }
  }, [clearLocalAuthState]);

  const reload = useCallback(async () => {
    if (!session) {
      setStatus("guest");
      return;
    }
    try {
      const current = await authApi.currentSession();
      updateStoredSessionUser(current.user);
      setPermissions(current.authorities.filter((authority) => !authority.startsWith("ROLE_")));
      setPermissionsLoaded(true);
      setSession((existing) => existing ? { ...existing, user: current.user } : existing);
      setStatus("authenticated");
      setError(null);
    } catch {
      setPermissionsLoaded(true);
      dropToGuest();
    }
  }, [dropToGuest, session]);

  const value = useMemo<AuthContextValue>(
    () => ({
      user: session?.user ?? null,
      organization: session?.organization ?? null,
      sessionExpiresAt: session?.accessTokenExpiresAt || null,
      permissions,
      permissionsLoaded,
      welcomePending,
      hasPermission: (permission) => permissions.includes(permission),
      consumeWelcome,
      status,
      isAuthenticated: status === "authenticated" && Boolean(session),
      login,
      verifyLoginEmailMfa,
      completeRegistrationVerification,
      completeRegistrationVerificationByLink,
      resendLoginEmailMfa,
      switchWorkspace,
      acceptSession,
      register,
      logout,
      reload,
      challenge,
      error,
    }),
    [session, status, error, challenge, permissions, permissionsLoaded, welcomePending, consumeWelcome,
      login, verifyLoginEmailMfa, completeRegistrationVerification, completeRegistrationVerificationByLink,
      resendLoginEmailMfa, switchWorkspace,
      acceptSession, register, logout, reload],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth must be used within an AuthProvider.");
  }
  return context;
}
