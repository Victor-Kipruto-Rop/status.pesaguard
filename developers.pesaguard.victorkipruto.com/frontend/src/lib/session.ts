/**
 * Persistence for the two credentials, plus cross-tab coordination.
 *
 * <h2>Why refresh is coordinated across tabs</h2>
 * The backend treats a refresh token as single-use: presenting one twice revokes
 * the entire family and signs the user out everywhere. Two open tabs each
 * holding the same token would therefore lock the user out by accident — the
 * second refresh looks exactly like a stolen token being replayed.
 *
 * <p>Two mechanisms prevent that, and both are needed:
 *
 * <ul>
 *   <li>A {@link BroadcastChannel} tells other tabs that a rotation happened, so
 *       they pick up the new token instead of spending the old one.</li>
 *   <li>A single in-flight promise (in `api.ts`) de-duplicates concurrent
 *       refreshes inside one tab into a single network call.</li>
 * </ul>
 *
 * <p>`localStorage` rather than `sessionStorage`, so a reload does not sign the
 * user out.
 *
 * <h2>Storage risk</h2>
 * Both tokens sit in `localStorage`, so any successful XSS can read a 30-day
 * refresh credential. That is an accepted tradeoff of the current bearer-token
 * architecture: the backend issues no cookies, so there is no HttpOnly option
 * today. The correct fix is to move the refresh token into an HttpOnly, Secure,
 * SameSite=Strict cookie with CSRF protection on `/auth/refresh`. That is a
 * backend change and is recorded in the handover notes rather than assumed away.
 */

import type { AuthOrganization, AuthSession, AuthUser } from "../types/auth";

const ACCESS_TOKEN_KEY = "pesaguard_session_token";
const ACCESS_EXPIRY_KEY = "pesaguard_session_expires_at";
const REFRESH_TOKEN_KEY = "pesaguard_refresh_token";
const USER_KEY = "pesaguard_session_user";
const ORGANIZATION_KEY = "pesaguard_session_organization";

const CHANNEL_NAME = "pesaguard-auth";

/**
 * Refresh this far before the access token actually expires.
 *
 * <p>Not a safety margin for its own sake: it has to cover the round trip to the
 * backend plus time the user spends on a slow request. Expiring at exactly the
 * right moment guarantees an in-flight request fails and the user sees a logout
 * instead of a completed action.
 */
const REFRESH_LEEWAY_MS = 60_000;

export type StoredSession = {
  accessToken: string;
  accessTokenExpiresAt: string;
  refreshToken: string;
  user: AuthUser;
  organization: AuthOrganization;
};

export type AuthBroadcast =
  | { type: "rotated"; session: StoredSession }
  | { type: "signed-out" }
  | { type: "signed-in"; session: StoredSession };

let channel: BroadcastChannel | null = null;

function getChannel(): BroadcastChannel | null {
  // BroadcastChannel is absent in some embedded webviews and older Safari.
  // Degrading to "no cross-tab coordination" is strictly better than crashing.
  if (typeof BroadcastChannel === "undefined") {
    return null;
  }
  if (!channel) {
    channel = new BroadcastChannel(CHANNEL_NAME);
  }
  return channel;
}

/** Subscribes to auth changes from other tabs. Returns an unsubscribe function. */
export function onRemoteAuthChange(handler: (message: AuthBroadcast) => void): () => void {
  const active = getChannel();
  if (!active) {
    return () => undefined;
  }
  const listener = (event: MessageEvent<AuthBroadcast>) => {
    handler(event.data);
  };
  active.addEventListener("message", listener);
  return () => active.removeEventListener("message", listener);
}

function publish(message: AuthBroadcast): void {
  try {
    getChannel()?.postMessage(message);
  } catch {
    // A closed channel must never break the sign-in that triggered it.
  }
}

function safeParse<T>(value: string | null): T | null {
  if (!value) {
    return null;
  }
  try {
    return JSON.parse(value) as T;
  } catch {
    return null;
  }
}

export function persistSession(session: AuthSession): StoredSession {
  const stored: StoredSession = {
    accessToken: session.accessToken,
    accessTokenExpiresAt: session.expiresAt,
    refreshToken: session.refreshToken,
    user: session.user,
    organization: session.organization,
  };

  localStorage.setItem(ACCESS_TOKEN_KEY, stored.accessToken);
  localStorage.setItem(ACCESS_EXPIRY_KEY, stored.accessTokenExpiresAt);
  localStorage.setItem(REFRESH_TOKEN_KEY, stored.refreshToken);
  localStorage.setItem(USER_KEY, JSON.stringify(stored.user));
  localStorage.setItem(ORGANIZATION_KEY, JSON.stringify(stored.organization));
  return stored;
}

export function clearSession(): void {
  localStorage.removeItem(ACCESS_TOKEN_KEY);
  localStorage.removeItem(ACCESS_EXPIRY_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
  localStorage.removeItem(ORGANIZATION_KEY);
}

export function updateStoredSessionUser(user: AuthUser): void {
  localStorage.setItem(USER_KEY, JSON.stringify(user));
}

export function loadSession(): StoredSession | null {
  const accessToken = localStorage.getItem(ACCESS_TOKEN_KEY);
  const refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY);
  const user = safeParse<AuthUser>(localStorage.getItem(USER_KEY));
  const organization = safeParse<AuthOrganization>(localStorage.getItem(ORGANIZATION_KEY));

  // All five or nothing. A partial set would produce a session that looks signed
  // in locally but cannot authenticate, surfacing as a confusing 401 loop.
  if (!accessToken || !refreshToken || !user || !organization) {
    return null;
  }

  return {
    accessToken,
    accessTokenExpiresAt: localStorage.getItem(ACCESS_EXPIRY_KEY) ?? "",
    refreshToken,
    user,
    organization,
  };
}

export function getAccessToken(): string | null {
  return localStorage.getItem(ACCESS_TOKEN_KEY);
}

export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_TOKEN_KEY);
}

/**
 * Whether the access token is expired, or close enough to it to be useless.
 *
 * <p>Proactive rather than reactive: the 401 path is a backstop, but refreshing
 * before the token lapses is what stops an ordinary request from ever failing
 * and triggering it.
 */
export function isAccessTokenExpiring(): boolean {
  const expiry = localStorage.getItem(ACCESS_EXPIRY_KEY);
  if (!expiry) {
    // No recorded expiry means we cannot reason about it; assume it is fine and
    // let a 401 handle that case, rather than refreshing on every request.
    return false;
  }
  const at = Date.parse(expiry);
  if (Number.isNaN(at)) {
    return false;
  }
  return at - REFRESH_LEEWAY_MS <= Date.now();
}

export function publishRotated(session: StoredSession): void {
  publish({ type: "rotated", session });
}

export function publishSignedIn(session: StoredSession): void {
  publish({ type: "signed-in", session });
}

export function publishSignedOut(): void {
  publish({ type: "signed-out" });
}