-- Throttle subject types actually used by the platform.
--
-- V1 constrained login_throttles.subject_type to ('account', 'ip'), but three
-- further subjects are written by the code:
--
--   oauth_token   OAuth token endpoint brute-force protection
--   invite_ip     per-IP invitation-acceptance throttling
--   invite_token  per-token invitation-acceptance throttling
--
-- Without these, every OAuth token-endpoint attempt and every invitation
-- acceptance raised a DataIntegrityViolationException, which the API surfaced
-- as a generic 409 RESOURCE_CONFLICT. Invitation acceptance was therefore
-- impossible in any deployed environment: the feature had never run against a
-- database.
--
-- The constraint is retained rather than dropped. An unbounded subject_type
-- would let a caller invent new throttle dimensions and silently fragment the
-- counter, so the set stays closed and every legitimate subject is listed.
--
-- Each subject is hashed before storage (subject_hash), so widening the set
-- does not widen what is retained: the column still holds digests, not the
-- identifiers themselves.

alter table login_throttles
    drop constraint if exists login_throttles_type_check;

alter table login_throttles
    add constraint login_throttles_type_check check (
        subject_type in ('account', 'ip', 'oauth_token', 'invite_ip', 'invite_token')
    );