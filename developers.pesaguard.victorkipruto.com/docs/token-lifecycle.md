# Token lifecycle

How PesaGuard Developer Platform issues, validates, rotates and revokes
credentials. Read this before changing anything in `security/tokens` or
`security/sessions`.

## The two credentials

A signed-in developer holds two unrelated credentials. Neither is derivable from
the other.

| | Access token | Refresh token |
|---|---|---|
| Format | RS256 JWT | 256-bit opaque random value |
| Lifetime | 5 minutes | 30 days |
| Storage at the server | Not stored — signature only | SHA-256 hash only |
| Revocable before expiry | **No** | Yes |
| Used for | Every API call | `POST /api/v1/auth/refresh` only |

The split exists because of one asymmetry: a JWT can be verified without a
database lookup, which is what lets the platform scale horizontally, but it also
cannot be withdrawn once issued. So the short-lived half carries the claims, and
the long-lived half — which *can* be revoked — is what actually decides whether a
session is still alive.

## Why not JWT for both

A refresh token as a JWT would have the same drawback as the access token with
none of the compensating benefit: it is used rarely, so the stateless saving is
negligible, while an unrevocable 30-day credential is a serious liability.
Rotation requires server-side state either way, because detecting reuse means
remembering which token was already spent.

## Access token claims

```json
{
  "iss": "pesaguard-developer-platform",
  "aud": "pesaguard-developer-platform",
  "sub": "<user UUID>",
  "jti": "<session UUID>",
  "org": "<organization UUID>",
  "amr": "pwd",
  "mfa": true,
  "rol": "ROLE_OWNER",
  "iat": 1767225000,
  "exp": 1767225300
}
```

Deliberately absent: email, display name, permissions. A JWT is readable by
anyone who holds it and cannot be corrected once issued, so a claim that changes
would stay wrong for the token's whole life and be visible to any interceptor.

**`rol` and `org` are the one documented exception** to the rule against putting
organization data in claims. They are included because `AccessDecisionService`
and `TenantGuard` authorize on them, and a stateless verifier has no other way to
know who the caller is. The cost is real and accepted: a role change takes effect
when the token expires rather than immediately, bounded by the 5-minute TTL.

## Verification

`AccessTokenService.verify` requires all of:

1. Parses as a compact JWS.
2. The algorithm **is** RS256 — not merely "whatever the header says".
3. The signature verifies against the configured public key.
4. `exp` is present and in the future.
5. `iss` matches this service.
6. `aud` contains this service.
7. `sub`, `jti` and `org` parse as UUIDs.

Any failure yields an identical 401. Distinguishing "malformed" from "wrong
signature" from "expired" would let a caller probe which part of a forged token
was incorrect.

### The algorithm check is the whole defence

A verifier that reads the algorithm from the header is vulnerable to:

- **`alg: none`** — an unsigned token with arbitrary claims.
- **Algorithm confusion** — signed with HS256 using the RSA *public* key as the
  HMAC secret. That key is published to every verifier, so no secret is needed.

Both are blocked by pinning RS256. `AccessTokenServiceTest` asserts both, because
a regression here would be silent and total.

## Key rotation

`keyId` is published in the header. Rotation is an overlap, not a cutover:

1. Add the new public key alongside the current one.
2. Point `PESAGUARD_JWT_PRIVATE_KEY` at the new key and bump `keyId`.
3. Tokens signed with the new key verify; tokens signed with the old key still
   verify because both public halves are trusted.
4. After the longest token lifetime plus clock skew, remove the old public key.

No redeployment storm, no invalidation of live sessions.

Both key halves are **required**. A deployment supplying only the private key is
refused at startup: a verifier that must derive the public key holds private key
material on every node, which is the separation RS256 exists to provide.

## Refresh rotation and reuse detection

Each login starts a **family**. Rotation spends the presented token and mints a
successor in the same family.

**A refresh token may be presented exactly once.** Presenting an already-spent
token means the value leaked, and the only safe response is to kill the entire
family and force re-authentication. The response is deliberately
indiscriminate: distinguishing "attacker replayed a stolen token" from
"legitimate client raced itself" is not possible from the available evidence, and
guessing wrong either leaves a stolen session alive or locks out an honest user.

Two implementation details are load-bearing.

**The spend is a conditional UPDATE.**

```sql
update user_refresh_tokens set used_at = ? where id = ? and used_at is null
```

Two tabs refreshing at the same instant both observe an unused token. Only the
database decides which proceeds; the loser sees zero rows and is treated as a
replay. A read-then-write would mint two live tokens from one spend.

**Revocation happens in its own transaction.**

`ReplayedRefreshTokenHandler` uses `PROPAGATION_REQUIRES_NEW`. Revoking the family
inside `rotate()` and then throwing would mark the transaction for rollback and
discard the revocation — the attacker would get a 401 saying the token was burned
while every token in the family stayed live. It is a separate bean because Spring
cannot intercept a self-invocation, so a same-class call would silently degrade
to the ambient transaction.

## Revocation matrix

| Trigger | Access token | Refresh family |
|---|---|---|
| Logout | Valid until expiry (<= 5 min) | Revoked |
| Session revoked | Valid until expiry | Revoked |
| Password changed | Valid until expiry | Revoked, all |
| Password reset | Valid until expiry | Revoked, all |
| Reuse detected | Valid until expiry | Revoked, whole family |
| Emergency | **Revoked immediately** | Revoked |

An access token survives logout for up to five minutes by design — the price of
statelessness. What logout guarantees is that the session cannot be *renewed*:
the family is dead, so the token cannot become a new one.

### The emergency denylist

`RevokedTokenRegistry` covers the case where waiting is not acceptable. Entries
live in Redis, keyed by `jti`, expiring with the token they refer to rather than
being swept separately.

It **fails closed**: if Redis is unreachable the token is refused. An outage of
the revocation store must not become an outage of revocation.

It is not on the routine path. Logout, password change and device removal use the
refresh family, which needs no Redis round trip.

## Concurrent refresh across tabs

Two tabs holding the same refresh token will make one lose the race, and that
loser is treated as a replay — correct for an attacker, harsh for a legitimate
user.

The backend does not soften this, because softening it means accepting reuse and
therefore accepting the attack. Clients must coordinate instead:

- Hold the refresh token in one place (a shared worker or `BroadcastChannel`),
  not per tab.
- Serialize refresh requests.
- Do not retry refresh indefinitely: one attempt, then sign out.

## Configuration

| Property | Default | Notes |
|---|---|---|
| `PESAGUARD_JWT_ISSUER` | `pesaguard-developer-platform` | Checked on verify |
| `PESAGUARD_JWT_AUDIENCE` | `pesaguard-developer-platform` | Checked on verify |
| `PESAGUARD_JWT_KEY_ID` | `v1` | Published in the header |
| `PESAGUARD_JWT_PRIVATE_KEY` | *(required)* | PKCS#8 base64 |
| `PESAGUARD_JWT_PUBLIC_KEY` | *(required)* | X.509 base64 |
| `PESAGUARD_JWT_ACCESS_TOKEN_TTL` | `PT5M` | Keep short |
| `PESAGUARD_REFRESH_TOKEN_TTL` | `P30D` | Longer is fine; revocable |

## Deliberately absent

- **Cookies.** Access tokens are bearer credentials held by the portal, not
  cookies, so CSRF does not apply to this path. If cookie auth is ever added it
  must be `Secure; HttpOnly; SameSite` **and** paired with CSRF protection.
- **A per-request database check.** That would undo the stateless design.