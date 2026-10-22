# ADR-0003: Public repository and MIT code license

- Status: accepted
- Date: 2026-10-22

## Context

The project was private and GitHub Actions was blocked by private-repository
billing. The source contains no secrets or release keystore; the application
code is original and the model weights are downloaded separately.

## Decision

1. Make `RandoTeam/Parlex` public.
2. License the application source under MIT (`LICENSE`).
3. Keep model/runtime licenses separate in `THIRD_PARTY_NOTICES.md` and
   `docs/LEGAL.md`.
4. Keep release keys, model files, APKs and credentials out of Git.
5. Use GitHub Security Advisories for private vulnerability reports.

## Consequences

- Standard GitHub-hosted Actions run free for the public repository, subject to
  fair-use limits.
- History is public; never commit secrets or model weights.
- Third-party notices must be kept accurate when dependencies change.
- Public issues/PRs need privacy checks and issue templates.
