"""Independent reference implementation of the PesaGuard webhook signature scheme.

Written from the documented rule in docs/webhooks.md, NOT ported from the Java
source. That distinction matters: a port shares the original author's assumptions,
so it would agree with the Java code even where both are wrong about the spec.
This one is written to the written rule, so agreement is evidence.

Documented rule (v1):
    signed_payload = "{version}.{epoch_seconds}.{body}"
    signature      = HMAC-SHA256(secret, signed_payload), lowercase hex
    header         = "t={epoch_seconds},{version}={signature}"
"""

import hashlib
import hmac
import sys

DEFAULT_VERSION = "v1"
TOLERANCE_SECONDS = 300


def signed_payload(version, epoch_seconds, body):
    return f"{version}.{epoch_seconds}.{body if body is not None else ''}"


def sign(secret, body, epoch_seconds, version=DEFAULT_VERSION):
    payload = signed_payload(version, epoch_seconds, body)
    mac = hmac.new(secret.encode("utf-8"), payload.encode("utf-8"), hashlib.sha256)
    return f"t={epoch_seconds},{version}={mac.hexdigest()}"


def verify(secret, header, body, now_epoch_seconds, tolerance=TOLERANCE_SECONDS):
    """Returns one of: VALID, MISSING_SIGNATURE, MALFORMED, STALE, INVALID_SIGNATURE."""
    if header is None or not header.strip():
        return "MISSING_SIGNATURE"
    timestamp = None
    expected = None
    for part in header.split(","):
        part = part.strip()
        if part.startswith("t="):
            try:
                timestamp = int(part[2:])
            except ValueError:
                return "MALFORMED"
        elif part.startswith(f"{DEFAULT_VERSION}="):
            expected = part[len(DEFAULT_VERSION) + 1:]
    if timestamp is None or not expected:
        return "MALFORMED"
    if abs(now_epoch_seconds - timestamp) > tolerance:
        return "STALE"
    # Compute the digest directly. Do NOT re-parse the value out of sign()'s
    # header string: that string is "t=<epoch>,<version>=<hex>", so splitting on
    # the first "=" yields "<epoch>,<version>=<hex>" rather than just the hex.
    payload = signed_payload(DEFAULT_VERSION, timestamp, body)
    candidate = hmac.new(
        secret.encode("utf-8"), payload.encode("utf-8"), hashlib.sha256
    ).hexdigest()
    # Constant-time comparison.
    if not hmac.compare_digest(candidate, expected):
        return "INVALID_SIGNATURE"
    return "VALID"


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "sign"
    secret = sys.argv[2]
    # The body is read from a file as raw bytes. stdin is deliberately avoided:
    # on Windows, Python's text-mode stdin applies universal-newline translation
    # and turns \r\n into \n, while Node reads raw bytes. Piping the same body to
    # both would therefore hash two *different* bodies and report a false
    # mismatch. Reading a file as binary keeps the comparison byte-exact.
    if mode == "sign":
        version = sys.argv[3]
        epoch = int(sys.argv[4])
        with open(sys.argv[5], "rb") as handle:
            body = handle.read().decode("utf-8")
        print(sign(secret, body, epoch, version))
    else:
        header = sys.argv[3]
        now = int(sys.argv[4])
        with open(sys.argv[5], "rb") as handle:
            body = handle.read().decode("utf-8")
        print(verify(secret, header, body, now))