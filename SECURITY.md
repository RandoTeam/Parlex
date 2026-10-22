# Security Policy

## Supported versions

Security fixes are applied to the current `main` branch and the latest published
beta. Older beta APKs are historical and are not maintained.

## Reporting a vulnerability

Please do **not** open a public issue for a vulnerability.

Use GitHub's private vulnerability reporting:

1. Open https://github.com/RandoTeam/Parlex/security/advisories
2. Choose **Report a vulnerability**
3. Include the affected version, device/Android version, reproduction steps and
   whether user text, camera frames, audio or model files are involved.

There is no guaranteed bounty. Reports are reviewed on a best-effort basis.

## Scope

In scope:

- user-text/audio/screenshot exposure;
- accessibility or MediaProjection privilege abuse;
- path traversal/zip-slip in model or package import;
- arbitrary file write or unsafe intent handling;
- unsafe model download/verification;
- secrets committed to the repository.

Out of scope:

- denial of service requiring root or a modified OS;
- vulnerabilities in upstream models, llama.cpp, ML Kit, Tesseract or Android;
- issues that require the user to install a malicious accessibility service or
  grant root;
- social engineering.

## Secrets

Release keystores and `keystore.properties` must never be committed. If you
believe a secret was exposed, report it privately and rotate the key.
