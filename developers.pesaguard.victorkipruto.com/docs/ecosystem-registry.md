# Ecosystem release registry

The developer-facing registry is read-only:

- `GET /api/v1/ecosystem/sdk-releases` lists SDK release metadata.
- `GET /api/v1/ecosystem/sdk-releases/{language}/recommended?apiMajor=1` selects the newest supported compatible SDK.
- `GET /api/v1/ecosystem/cli-releases` lists CLI build metadata.
- `GET /api/v1/ecosystem/cli-releases/recommended?platform=LINUX&architecture=X86_64&channel=STABLE` selects a downloadable target build.

Responses contain version, channel, lifecycle, SHA-256 checksum, compatibility, and artefact information. The API does not proxy or serve downloads. Release creation remains owned by the trusted release pipeline; the database is the release metadata source and a deployment must apply V17 before these endpoints can return records.

Recommendation excludes withdrawn releases and returns only supported SDK channels. CLI channel requests honour an available requested preview channel before falling back to a more stable available channel.
