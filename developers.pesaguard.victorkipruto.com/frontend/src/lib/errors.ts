export type AppErrorCode =
  | "UNKNOWN_ERROR"
  | "CLIENT_RUNTIME_ERROR"
  | "NETWORK_ERROR"
  | "TIMEOUT"
  | "REQUEST_ABORTED"
  | "VALIDATION_ERROR"
  | "AUTHENTICATION_REQUIRED"
  | "AUTHENTICATION_FAILED"
  | "AUTHORIZATION_DENIED"
  | "SESSION_EXPIRED"
  | "NOT_FOUND"
  | "CONFLICT"
  | "RATE_LIMITED"
  | "SERVER_ERROR"
  | "SERVICE_UNAVAILABLE"
  | "MAINTENANCE"
  | "CONFIGURATION_ERROR"
  | "INVALID_API_RESPONSE";

export type ErrorContext = {
  component?: string;
  method?: string;
  requestId?: string | null;
  route?: string;
  status?: number;
  traceId?: string | null;
};

export type SafeErrorDiagnostic = {
  code: AppErrorCode;
  component?: string;
  method?: string;
  requestId?: string;
  route?: string;
  status?: number;
  timestamp: string;
  traceId?: string;
};

const ERROR_MESSAGES: Record<AppErrorCode, string> = {
  UNKNOWN_ERROR: "Something went wrong. Please try again.",
  CLIENT_RUNTIME_ERROR: "We couldn't load this page correctly. Please try again.",
  NETWORK_ERROR: "We couldn't connect to PesaGuard. Check your connection and try again.",
  TIMEOUT: "The request took too long to complete. Please try again.",
  REQUEST_ABORTED: "The request was cancelled.",
  VALIDATION_ERROR: "Please check the information you entered.",
  AUTHENTICATION_REQUIRED: "Please sign in to continue.",
  AUTHENTICATION_FAILED: "We couldn't sign you in with those details.",
  AUTHORIZATION_DENIED: "You don't have permission to perform this action.",
  SESSION_EXPIRED: "Your session has expired. Please sign in again.",
  NOT_FOUND: "We couldn't find what you're looking for.",
  CONFLICT: "This action couldn't be completed because the information has changed.",
  RATE_LIMITED: "You're sending requests too quickly. Please try again shortly.",
  SERVER_ERROR: "Something went wrong on our side. Please try again shortly.",
  SERVICE_UNAVAILABLE: "This service is temporarily unavailable. Please try again in a moment.",
  MAINTENANCE: "PesaGuard is temporarily unavailable for maintenance. Please try again shortly.",
  CONFIGURATION_ERROR: "We couldn't prepare your request. Please refresh the page and try again.",
  INVALID_API_RESPONSE: "We couldn't process the server response. Please try again.",
};

export class AppError extends Error {
  constructor(
    readonly appCode: AppErrorCode,
    readonly userMessage: string = ERROR_MESSAGES[appCode],
    readonly status?: number,
    readonly requestId?: string | null,
    readonly traceId?: string | null,
    readonly technical?: unknown,
  ) {
    super(userMessage);
    this.name = "AppError";
  }
}

let requestSequence = 0;

export function generateRequestId(): string {
  try {
    const cryptoApi = globalThis.crypto;
    if (typeof cryptoApi?.randomUUID === "function") {
      return cryptoApi.randomUUID();
    }
    if (typeof cryptoApi?.getRandomValues === "function") {
      const bytes = cryptoApi.getRandomValues(new Uint8Array(16));
      bytes[6] = (bytes[6] & 0x0f) | 0x40;
      bytes[8] = (bytes[8] & 0x3f) | 0x80;
      const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
      return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
    }
  } catch {
    // Request IDs are correlation hints, never credentials or security tokens.
  }

  requestSequence += 1;
  return `req-${Date.now().toString(36)}-${requestSequence.toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

function codeFromStatus(status: number): AppErrorCode {
  if (status === 400 || status === 422) return "VALIDATION_ERROR";
  if (status === 401) return "AUTHENTICATION_REQUIRED";
  if (status === 403) return "AUTHORIZATION_DENIED";
  if (status === 404) return "NOT_FOUND";
  if (status === 409) return "CONFLICT";
  if (status === 408) return "TIMEOUT";
  if (status === 429) return "RATE_LIMITED";
  if (status === 502 || status === 503 || status === 504) return "SERVICE_UNAVAILABLE";
  if (status >= 500) return "SERVER_ERROR";
  return "UNKNOWN_ERROR";
}

export function normalizeError(error: unknown): AppError {
  if (error instanceof AppError) return error;

  if (typeof error === "object" && error !== null && "status" in error
    && typeof error.status === "number") {
    const status = error.status;
    return new AppError(codeFromStatus(status), ERROR_MESSAGES[codeFromStatus(status)], status);
  }

  if (typeof DOMException !== "undefined" && error instanceof DOMException
    && error.name === "AbortError") {
    return new AppError("REQUEST_ABORTED");
  }

  if (error instanceof Error) {
    return new AppError("CLIENT_RUNTIME_ERROR", undefined, undefined, undefined, undefined, error);
  }

  return new AppError("UNKNOWN_ERROR");
}

export function getUserMessage(error: unknown, fallback?: string): string {
  if (error instanceof AppError) {
    if ("fieldViolationsMapped" in error && error.fieldViolationsMapped === true) return "";
    return error.userMessage;
  }
  return fallback ?? normalizeError(error).userMessage;
}

function safeCorrelationValue(value: string | null | undefined): string | undefined {
  return value && /^[A-Za-z0-9._:-]{1,128}$/.test(value) ? value : undefined;
}

export function sanitizeError(error: unknown, context: ErrorContext = {}): SafeErrorDiagnostic {
  const normalized = normalizeError(error);
  const route = context.route ?? (typeof window === "undefined" ? undefined : window.location.pathname);
  const component = context.component && /^[A-Za-z0-9_$.-]{1,80}$/.test(context.component)
    ? context.component
    : undefined;
  return {
    code: normalized.appCode,
    ...(component ? { component } : {}),
    ...(context.method && /^[A-Z]{1,12}$/.test(context.method) ? { method: context.method } : {}),
    ...(safeCorrelationValue(context.requestId ?? normalized.requestId)
      ? { requestId: safeCorrelationValue(context.requestId ?? normalized.requestId) }
      : {}),
    ...(route ? { route: route.split(/[?#]/, 1)[0].slice(0, 200) } : {}),
    ...(typeof context.status === "number" ? { status: context.status } : normalized.status === undefined ? {} : { status: normalized.status }),
    timestamp: new Date().toISOString(),
    ...(safeCorrelationValue(context.traceId ?? normalized.traceId)
      ? { traceId: safeCorrelationValue(context.traceId ?? normalized.traceId) }
      : {}),
  };
}

export type ErrorReporter = (diagnostic: SafeErrorDiagnostic) => void;

let configuredReporter: ErrorReporter | undefined;

export function configureErrorReporter(reporter: ErrorReporter | undefined): void {
  configuredReporter = reporter;
}

export function reportError(error: unknown, context: ErrorContext = {}): void {
  const diagnostic = sanitizeError(error, context);
  if (configuredReporter) {
    configuredReporter(diagnostic);
  } else {
    console.error("PesaGuard application error", diagnostic);
  }

  if (import.meta.env.DEV) {
    console.debug(
      "PesaGuard developer diagnostic",
      error instanceof AppError ? error.technical ?? error : error,
    );
  }
}

export function installGlobalErrorHandlers(): () => void {
  const onError = (event: ErrorEvent) => {
    reportError(event.error ?? event.message, { component: "window" });
  };
  const onUnhandledRejection = (event: PromiseRejectionEvent) => {
    reportError(event.reason, { component: "unhandled-promise" });
    event.preventDefault();
  };

  window.addEventListener("error", onError);
  window.addEventListener("unhandledrejection", onUnhandledRejection);
  return () => {
    window.removeEventListener("error", onError);
    window.removeEventListener("unhandledrejection", onUnhandledRejection);
  };
}
