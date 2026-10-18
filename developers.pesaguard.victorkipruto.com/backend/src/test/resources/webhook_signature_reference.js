/**
 * Independent reference implementation of the PesaGuard webhook signature scheme.
 *
 * Written from the documented rule in docs/webhooks.md, NOT ported from the Java
 * source. That distinction matters: a port shares the original author's
 * assumptions, so it would agree with the Java code even where both are wrong
 * about the spec. This one is written to the written rule, so agreement between
 * the two is evidence rather than tautology.
 *
 * Documented rule (v1):
 *   signed_payload = "{version}.{epochSeconds}.{body}"
 *   signature      = HMAC-SHA256(secret, signed_payload), lowercase hex
 *   header         = "t={epochSeconds},{version}={signature}"
 */
const crypto = require("crypto");

const DEFAULT_VERSION = "v1";
const TOLERANCE_SECONDS = 300;

function signedPayload(version, epochSeconds, body) {
  return `${version}.${epochSeconds}.${body == null ? "" : body}`;
}

function sign(secret, body, epochSeconds, version = DEFAULT_VERSION) {
  const digest = crypto
    .createHmac("sha256", Buffer.from(secret, "utf8"))
    .update(Buffer.from(signedPayload(version, epochSeconds, body), "utf8"))
    .digest("hex");
  return `t=${epochSeconds},${version}=${digest}`;
}

function verify(secret, header, body, nowEpochSeconds, tolerance = TOLERANCE_SECONDS) {
  if (header == null || header.trim() === "") return "MISSING_SIGNATURE";
  let timestamp = null;
  let expected = null;
  for (const raw of header.split(",")) {
    const part = raw.trim();
    if (part.startsWith("t=")) {
      const parsed = Number.parseInt(part.slice(2), 10);
      if (Number.isNaN(parsed)) return "MALFORMED";
      timestamp = parsed;
    } else if (part.startsWith(`${DEFAULT_VERSION}=`)) {
      expected = part.slice(DEFAULT_VERSION.length + 1);
    }
  }
  if (timestamp == null || !expected) return "MALFORMED";
  // Absolute drift: a far-future timestamp is as suspicious as a far-past one.
  if (Math.abs(nowEpochSeconds - timestamp) > tolerance) return "STALE";
  // Compute the digest directly. Do NOT re-parse the value out of sign()'s header
  // string: that string is "t=<epoch>,<version>=<hex>", so splitting on the first
  // "=" yields "<epoch>,<version>=<hex>" rather than just the hex.
  const candidate = crypto
    .createHmac("sha256", Buffer.from(secret, "utf8"))
    .update(Buffer.from(signedPayload(DEFAULT_VERSION, timestamp, body), "utf8"))
    .digest("hex");
  // Constant-time comparison, matching the Java side.
  const a = Buffer.from(candidate, "utf8");
  const b = Buffer.from(expected, "utf8");
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return "INVALID_SIGNATURE";
  return "VALID";
}

module.exports = { sign, verify, signedPayload, DEFAULT_VERSION, TOLERANCE_SECONDS };

if (require.main === module) {
  const mode = process.argv[2];
  const secret = process.argv[3];
  // The body is read from a file as raw bytes. stdin is deliberately avoided:
  // on Windows, Python's text-mode stdin applies universal-newline translation
  // and turns \r\n into \n, while Node reads raw bytes. Piping the same body to
  // both would therefore hash two *different* bodies and report a false
  // mismatch. Reading a file keeps the comparison byte-exact.
  //
  // The body path is always the LAST argument, because Node's process.argv is
  // offset by one relative to Python's sys.argv.
  const bodyPath = process.argv[process.argv.length - 1];
  const body = require("fs").readFileSync(bodyPath, "utf8");
  if (mode === "sign") {
    const version = process.argv[4];
    const epoch = Number.parseInt(process.argv[5], 10);
    process.stdout.write(sign(secret, body, epoch, version));
  } else {
    const header = process.argv[4];
    const now = Number.parseInt(process.argv[5], 10);
    process.stdout.write(verify(secret, header, body, now));
  }
}