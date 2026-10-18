import assert from "node:assert/strict";
import { after, afterEach, before, beforeEach, test } from "node:test";
import { createServer } from "vite";

const leakedDetail = "database password stack trace 10.0.0.4";
const responseRequestId = "123e4567-e89b-42d3-a456-426614174000";
const diagnostics = [];

let vite;
let errors;
let api;
let validateOrganizationName;
let originalFetch;
let originalStorageDescriptor;
let originalConsoleDebug;

function jsonResponse(status, body, headers = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", ...headers },
  });
}

function errorResponse(status) {
  return jsonResponse(status, {
    error: {
      code: "INTERNAL_FAILURE",
      message: leakedDetail,
      stack: leakedDetail,
      violations: [{ field: "password", message: leakedDetail }],
    },
  }, { "X-Request-ID": responseRequestId });
}

before(async () => {
  originalFetch = globalThis.fetch;
  originalStorageDescriptor = Object.getOwnPropertyDescriptor(globalThis, "localStorage");
  originalConsoleDebug = console.debug;

  globalThis.localStorage = { getItem: () => null };
  console.debug = () => {};

  vite = await createServer({
    root: process.cwd(),
    configFile: "vite.config.ts",
    server: { middlewareMode: true },
    appType: "custom",
    logLevel: "error",
  });
  errors = await vite.ssrLoadModule("/src/lib/errors.ts");
  api = await vite.ssrLoadModule("/src/lib/api.ts");
  ({ organizationNameValidationMessage: validateOrganizationName } = await vite.ssrLoadModule("/src/lib/organizationNameValidation.ts"));
  errors.configureErrorReporter((diagnostic) => diagnostics.push(diagnostic));
});

beforeEach(() => {
  globalThis.fetch = originalFetch;
  diagnostics.length = 0;
});

afterEach(() => {
  globalThis.fetch = originalFetch;
});

after(async () => {
  errors?.configureErrorReporter(undefined);
  globalThis.fetch = originalFetch;
  console.debug = originalConsoleDebug;
  if (originalStorageDescriptor) {
    Object.defineProperty(globalThis, "localStorage", originalStorageDescriptor);
  } else {
    delete globalThis.localStorage;
  }
  await vite?.close();
});

test("normalizes runtime exceptions without exposing their details", () => {
  const normalized = errors.normalizeError(new TypeError(leakedDetail));
  assert.equal(normalized.appCode, "CLIENT_RUNTIME_ERROR");
  assert.equal(normalized.message, "We couldn't load this page correctly. Please try again.");
  assert.doesNotMatch(normalized.message, /database|password|stack trace|10\.0\.0\.4/i);
});

test("sanitizes diagnostic metadata and excludes exception details", () => {
  const diagnostic = errors.sanitizeError(new Error(leakedDetail), {
    component: "invalid<script>",
    route: "/projects?token=private#details",
    requestId: "not a valid id",
    traceId: "invalid\ntrace",
  });
  assert.equal(diagnostic.route, "/projects");
  assert.equal(diagnostic.component, undefined);
  assert.equal(diagnostic.requestId, undefined);
  assert.equal(diagnostic.traceId, undefined);
  assert.doesNotMatch(JSON.stringify(diagnostic), /database|password|stack trace|10\.0\.0\.4/i);
});

test("generates a valid UUID correlation ID in the supported browser runtime", () => {
  assert.match(errors.generateRequestId(), /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
});

test("validates organization names using trimmed Unicode character lengths and control-character checks", () => {
  assert.equal(validateOrganizationName("  Élan Systems & Co.  "), null);
  assert.notEqual(validateOrganizationName(" "), null);
  assert.notEqual(validateOrganizationName("A"), null);
  assert.notEqual(validateOrganizationName("A".repeat(121)), null);
  assert.notEqual(validateOrganizationName("Acme\u0000Corp"), null);
});

const statusCases = [
  [400, "We couldn't process that request."],
  [401, "Please sign in to continue."],
  [403, "You don't have permission to perform this action."],
  [404, "We couldn't find what you're looking for."],
  [409, "This action couldn't be completed because the information has changed."],
  [422, "Please check the information you entered."],
  [429, "You're sending requests too quickly. Please try again shortly."],
  [500, "Something went wrong on our side. Please try again shortly."],
  [502, "This service is temporarily unavailable."],
  [503, "This service is temporarily unavailable."],
  [504, "The request took too long to complete. Please try again."],
];

for (const [status, safeMessage] of statusCases) {
  test(`maps HTTP ${status} to a safe message`, async () => {
    let sentRequestId;
    globalThis.fetch = async (_url, init) => {
      sentRequestId = new Headers(init.headers).get("X-Request-ID");
      return errorResponse(status);
    };

    await assert.rejects(
      api.apiFetch("/test", { anonymous: true }),
      (error) => {
        assert.equal(error.userMessage, safeMessage);
        assert.doesNotMatch(error.message, /database|password|stack trace|10\.0\.0\.4/i);
        assert.equal(error.requestId, responseRequestId);
        if (status === 422) {
          assert.equal(error.violations[0].message, "Please check the information you entered.");
        }
        return true;
      },
    );

    assert.match(sentRequestId, /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
    assert.doesNotMatch(JSON.stringify(diagnostics), /database|password|stack trace|10\.0\.0\.4/i);
  });
}

test("turns non-JSON gateway failures into a safe service message", async () => {
  globalThis.fetch = async () => new Response(`<html>${leakedDetail}</html>`, {
    status: 502,
    headers: { "content-type": "text/html" },
  });

  await assert.rejects(api.apiFetch("/test", { anonymous: true }), (error) => {
    assert.equal(error.userMessage, "This service is temporarily unavailable.");
    assert.doesNotMatch(error.message, /database|password|stack trace|10\.0\.0\.4/i);
    return true;
  });
  assert.doesNotMatch(JSON.stringify(diagnostics), /database|password|stack trace|10\.0\.0\.4/i);
});

test("shows the organization name policy error without using backend text", async () => {
  globalThis.fetch = async () => jsonResponse(400, {
    error: { code: "ORGANIZATION_NAME_INVALID", message: leakedDetail },
  });
  await assert.rejects(api.apiFetch("/test", { anonymous: true }), (error) => {
    assert.equal(error.userMessage, "Enter an organization name between 2 and 120 characters.");
    assert.doesNotMatch(error.message, /database|password|stack trace|10\.0\.0\.4/i);
    return true;
  });
});

test("shows a safe phone conflict message without using backend text", async () => {
  globalThis.fetch = async () => jsonResponse(409, {
    error: { code: "PHONE_ALREADY_REGISTERED", message: leakedDetail },
  });
  await assert.rejects(api.apiFetch("/test", { anonymous: true }), (error) => {
    assert.equal(error.userMessage, "This phone number is already in use. Use a different number or sign in.");
    assert.doesNotMatch(error.message, /database|password|stack trace|10\.0\.0\.4/i);
    return true;
  });
});

test("shows actionable password-policy and reset-link errors without using backend text", async () => {
  const cases = [
    ["WEAK_PASSWORD", "Choose a password with at least 12 characters that doesn't include your email or personal information and hasn't appeared in a known data breach."],
    ["PASSWORD_RESET_INVALID", "This password reset link is invalid or has expired. Request a new reset email."],
  ];

  for (const [code, userMessage] of cases) {
    globalThis.fetch = async () => jsonResponse(400, {
      error: { code, message: leakedDetail },
    });
    await assert.rejects(api.apiFetch("/test", { anonymous: true }), (error) => {
      assert.equal(error.userMessage, userMessage);
      assert.doesNotMatch(error.message, /database|stack trace|10\.0\.0\.4/i);
      return true;
    });
  }
});

test("rejects malformed successful JSON with a safe response error", async () => {
  globalThis.fetch = async () => new Response(leakedDetail, {
    status: 200,
    headers: { "content-type": "application/json" },
  });

  await assert.rejects(api.apiFetch("/test", { anonymous: true }), (error) => {
    assert.equal(error.appCode, "INVALID_API_RESPONSE");
    assert.equal(error.userMessage, "We couldn't process the server response. Please try again.");
    assert.doesNotMatch(error.message, /database|password|stack trace|10\.0\.0\.4/i);
    return true;
  });
});

test("normalizes network failures without displaying transport details", async () => {
  globalThis.fetch = async () => {
    throw new TypeError(`Failed to fetch ${leakedDetail}`);
  };

  await assert.rejects(api.apiFetch("/test", { anonymous: true }), (error) => {
    assert.equal(error.appCode, "NETWORK_ERROR");
    assert.equal(error.userMessage, "We couldn't connect to PesaGuard. Check your internet connection and try again.");
    assert.doesNotMatch(error.message, /database|password|stack trace|10\.0\.0\.4/i);
    return true;
  });
  assert.doesNotMatch(JSON.stringify(diagnostics), /database|password|stack trace|10\.0\.0\.4/i);
});

test("times out stalled requests with a safe error", async () => {
  globalThis.fetch = async (_url, init) => new Promise((_resolve, reject) => {
    init.signal.addEventListener("abort", () => reject(new DOMException(leakedDetail, "AbortError")), { once: true });
  });

  await assert.rejects(
    api.apiFetch("/test", {
      anonymous: true,
      requestPolicy: { timeoutMs: 1000, retryCount: 0 },
    }),
    (error) => {
      assert.equal(error.appCode, "TIMEOUT");
      assert.equal(error.userMessage, "The request took too long to complete. Please try again.");
      assert.doesNotMatch(error.message, /database|password|stack trace|10\.0\.0\.4/i);
      return true;
    },
  );
});

test("retries GET requests with a new correlation ID", async () => {
  let callCount = 0;
  const requestIds = [];
  globalThis.fetch = async (_url, init) => {
    callCount += 1;
    requestIds.push(new Headers(init.headers).get("X-Request-ID"));
    return callCount === 1
      ? errorResponse(503)
      : jsonResponse(200, { data: { ready: true } });
  };

  const result = await api.apiFetch("/test", {
    anonymous: true,
    requestPolicy: { timeoutMs: 1000, retryCount: 1 },
  });
  assert.deepEqual(result, { data: { ready: true } });
  assert.equal(callCount, 2);
  assert.notEqual(requestIds[0], requestIds[1]);
});

test("does not retry a failed mutation", async () => {
  let callCount = 0;
  globalThis.fetch = async () => {
    callCount += 1;
    return errorResponse(503);
  };

  await assert.rejects(api.apiFetch("/test", {
    method: "POST",
    body: JSON.stringify({ action: "create" }),
    anonymous: true,
    requestPolicy: { timeoutMs: 1000, retryCount: 2 },
  }));
  assert.equal(callCount, 1);
});
