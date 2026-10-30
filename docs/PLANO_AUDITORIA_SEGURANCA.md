# Security Assessment Plan — LinkedIn Integration Backend

**Repository:** [`pedrohcnascimento/linkedin-integration-backend`](https://github.com/pedrohcnascimento/linkedin-integration-backend)  
**Suggested location:** `docs/PLANO_AUDITORIA_SEGURANCA.md`  
**Document status:** Planned assessment; this document is not evidence that an audit has already been performed.  
**Baseline reviewed:** repository documentation and configuration as of 22 October 2026.

## 1. Purpose

This document defines a repeatable security assessment for the LinkedIn Integration Backend. The goal is to identify, validate, prioritize, remediate, and retest security weaknesses before the application is exposed publicly or handles real users at meaningful scale.

The project already implements several security controls, including session-based authentication, CSRF protection, OAuth 2.0 state validation, encrypted LinkedIn tokens at rest, rate limiting, exact-origin CORS validation, and idempotency safeguards for text publication. The assessment must verify that these controls work as intended and cannot be bypassed; the presence of a control in the source code is not sufficient evidence that it is effective.

A security scan cannot prove that an application is free of vulnerabilities. The target is to establish documented evidence of coverage, resolve serious findings, verify important authorization and business rules, and maintain automated regression tests so that discovered issues do not silently return.

## 2. Scope and safety rules

### 2.1 In scope

- Java 21 and Spring Boot application code.
- Spring Security configuration, session lifecycle, CSRF, CORS, and authentication flows.
- OAuth 2.0 / OpenID Connect integration with LinkedIn.
- Draft creation, pagination, editing, approval, publication, and publication history.
- Idempotency, retries, concurrency, and failure handling around external publishing.
- Rate limiting, Redis usage, proxy/IP handling, and resource-exhaustion controls.
- SQLite local/test configuration, Oracle production profile, Flyway migrations, Hibernate validation, and database access.
- Maven dependencies, build configuration, CI workflows, secrets, logs, error handling, and operational configuration.
- OpenAPI/Swagger and Actuator/health exposure.
- Tests and evidence needed to confirm that controls are effective.

### 2.2 Explicit boundaries

1. Run dynamic and active security tests only against a local instance, disposable test environment, or other environment for which the project owner has explicit authorization.
2. Do not run active scans, fuzzing, load tests, or penetration tests against LinkedIn or other third-party systems. For most tests, mock the LinkedIn API or use a documented, controlled integration test with the project's own app and test account.
3. Do not use real user data, production access tokens, or live publishing accounts as test fixtures. Prefer synthetic accounts and disposable records.
4. Back up a test database before tests that can create, modify, or delete records. Use non-destructive scans first; active scans can trigger application behavior and should be scoped carefully.
5. Store reports privately when they contain exploit details, request/response data, user identifiers, internal endpoints, or configuration findings. Do not commit secrets or sensitive response captures to the repository.
6. Follow applicable provider terms and organizational policies. This plan authorizes testing of the project's own application, not unauthorized testing of the provider.

## 3. Current project context and known limitations

The repository documentation describes the following capabilities as implemented:

- Local user registration, session login, current-user lookup, logout, and CSRF protection.
- LinkedIn OAuth start/callback with single-use `state`, sanitized connection status, local disconnection, and encrypted token storage at rest.
- Rate limiting for authentication, OAuth, and draft writing/publication.
- Text draft creation, paginated listing, editing before approval, approval, and authenticated publication through the official LinkedIn Posts API.
- Publication requests require `Idempotency-Key`; a replay of an already completed publication returns the existing result without another LinkedIn request.
- Paginated publication history is restricted to the authenticated user and includes `PUBLISHED` and `FAILED` entries.
- Exact HTTP/HTTPS CORS-origin validation and profile-specific API documentation configuration.
- SQLite for local/test environments and an Oracle-configured `prod` profile with Oracle Flyway migrations and Hibernate `ddl-auto: validate`.

The current project status documentation also identifies material items that must remain open until verified:

1. Concurrency with the same idempotency key has not been validated against a real supported database; the known collision test uses a simulated repository.
2. There is no automated or admin-endpoint reconciliation for an ambiguous external result (such as a timeout after LinkedIn may have accepted a post); recovery relies on the manual operator runbook in [`docs/reconciliacao-manual-publicacoes.md`](reconciliacao-manual-publicacoes.md), which must be reviewed as part of `PUB-05`.
3. The production Oracle profile and its migrations have not been validated against a disposable real Oracle test database. The Maven build also contains PostgreSQL dependencies, so the intended supported production database must be made explicit and verified.
4. The OAuth provider is simulated in automated tests, even though the project status records a smoke test with the real app in LinkedIn Developer Portal.
5. The LinkedIn Posts API version and payload require revalidation against official documentation before release.
6. Redis sharing and trusted client-IP handling behind a proxy must be validated in the target deployment environment.
7. OpenAPI coverage for all cases, responses, and errors must be reviewed.

These are known assurance gaps, not necessarily confirmed exploitable vulnerabilities. Record them as unresolved assessment items until evidence closes them.

## 4. Assessment standard and severity

Use these references as the principal frameworks:

- [OWASP Application Security Verification Standard (ASVS) 5.0.0](https://asvs.owasp.org/): verification requirements for application security controls. Use Level 2 as a working target for this public-facing application, review each requirement for applicability, and record exclusions with reasons. This is an internal target, not a certification claim.
- [OWASP API Security Top 10 — 2023](https://owasp.org/API-Security/editions/2023/en/0x11-t10/): prioritize object-level authorization, authentication, property/function authorization, resource consumption, sensitive business flows, SSRF, misconfiguration, inventory, and unsafe consumption of APIs.
- [OWASP Web Security Testing Guide, version 4.2](https://owasp.org/www-project-web-security-testing-guide/v42/): organize manual testing and evidence. The current guide may evolve; pin the version used in each audit report.
- [OWASP Dependency-Check](https://owasp.org/www-project-dependency-check/): identify dependencies associated with publicly disclosed vulnerabilities.
- [GitHub CodeQL code scanning](https://docs.github.com/en/code-security/code-scanning/introduction-to-code-scanning/about-code-scanning): static analysis for code patterns that may be vulnerable.
- [OWASP ZAP](https://www.zaproxy.org/): dynamic analysis of the running application.
- [Gitleaks](https://github.com/gitleaks/gitleaks): scan current content and Git history for credentials or other secrets.

Severity should reflect both impact and exploitability. Use Critical, High, Medium, Low, and Informational consistently; use CVSS as supporting evidence where applicable, not as a replacement for context. Document business impact, prerequisites, affected assets, and whether a finding is confirmed or only suspected.

## 5. Workflow

### Phase 0 — Prepare an isolated assessment environment

- [ ] Choose the supported production database explicitly (currently the `prod` profile selects Oracle).
- [ ] Create a disposable test environment with JDK 21 and a fresh test database.
- [ ] Provide synthetic users A and B, separate browser sessions, fake drafts, and test-only LinkedIn credentials or a mock provider.
- [ ] Confirm external calls can be intercepted or mocked, so a test cannot unintentionally publish real content.
- [ ] Capture the application commit SHA, build environment, enabled Spring profile, database version, and tool versions.
- [ ] Back up test data and document how to restore the environment.
- [ ] Ensure logs, reports, test data, and screenshots contain no production secrets.

**Exit criteria:** the target, accounts, database, external-call boundaries, and test permissions are known and documented.

### Phase 1 — Inventory and threat model

- [ ] Inventory all routes, HTTP methods, authentication requirements, CSRF requirements, DTOs, and response types.
- [ ] Inventory sensitive data: session identifiers, password hashes, OAuth access tokens, client secret, encryption key, rate-limit HMAC key, email, and publication content.
- [ ] Map data flows from browser → backend → database → LinkedIn and identify trust boundaries.
- [ ] Identify the security principal/owner for each stored resource and verify that owner scoping occurs in service/repository logic, not only in the frontend.
- [ ] Record what can happen if the application is unavailable, Redis fails, a database transaction fails, or LinkedIn returns a timeout.
- [ ] Review `docs/status-atual.md`, `docs/linkedin-capability-matrix.md`, `docs/planejamento-tecnico.md`, `SECURITY.md`, and the current source/configuration. The status document governs implementation status; planning documents do not prove that a feature exists.
- [ ] Identify every public route and every debug/documentation endpoint in each profile.

**Deliverable:** a route/asset inventory and a simple threat model listing trust boundaries, sensitive assets, potential attackers, and controls.

### Phase 2 — Static analysis, dependencies, and secret exposure

#### Code analysis

- [ ] Enable CodeQL analysis for the supported Java build and default branch.
- [ ] Review findings manually; confirm whether each applies, fix or document it, and add regression coverage where feasible.
- [ ] Run the full Maven build and tests from a clean checkout (`mvn clean verify`).
- [ ] Review Spring Security filters and authorization rules, input validation, exception handling, URL construction, HTTP client timeouts, and any serialization/deserialization boundaries.
- [ ] Check for unsafe use of SQL, command execution, file paths, redirect destinations, and untrusted URLs, including any future feature that could fetch user-supplied URLs.

#### Dependency and supply-chain checks

- [ ] Run OWASP Dependency-Check or a comparable SCA tool against direct and transitive Maven dependencies.
- [ ] Review known CVEs, affected versions, reachability, available fixes, and false positives.
- [ ] Review versions of Spring Boot, Spring Security, Hibernate, the Oracle/SQLite/PostgreSQL drivers, Flyway, springdoc, Redis client, and cryptography libraries.
- [ ] Review dependency scopes and database-driver inconsistency. A dependency being present in `pom.xml` does not prove that its database profile or migrations work.
- [ ] Produce a software bill of materials (SBOM) if practical, and retain it with the private assessment artifacts.
- [ ] Verify that CI uses pinned/reviewed actions and does not expose secrets to untrusted pull-request code.

#### Secrets

- [ ] Scan working files and the full Git history with Gitleaks or an equivalent tool.
- [ ] Check for LinkedIn client secrets, access tokens, refresh tokens, session secrets, encryption keys, HMAC keys, database credentials, and personal data.
- [ ] Confirm real secrets are injected through environment/secret management, not committed into YAML, `.env`, test fixtures, documentation, or logs.
- [ ] If a secret is found, revoke/rotate it immediately, assess whether it was used, and only then remove it from files/history according to the project's repository policy. Deleting a secret in a later commit does not invalidate the exposed value.

**Exit criteria:** each finding has a disposition, no known active secret remains valid in repository history, and the relevant build/security checks have recorded results.

### Phase 3 — Dynamic testing

Run OWASP ZAP first in passive/baseline mode against a local or disposable instance. Configure a separate, explicitly scoped active scan only after the passive results and test data are reviewed. Do not scan LinkedIn endpoints.

- [ ] Test using the same app profiles and security configuration intended for deployment.
- [ ] Verify TLS/cookie/security-header behavior at the deployment boundary, not only on loopback.
- [ ] Check exposed routes, unexpected HTTP methods, error pages, stack traces, server banners, cache behavior, CORS, and API documentation exposure.
- [ ] Test authentication-required routes without a session and with expired/invalid sessions.
- [ ] Exercise validation with malformed JSON, missing fields, oversized values, unexpected types, invalid IDs, and unsupported content types.
- [ ] Check request size, pagination boundaries, slow upstream calls, timeouts, and rate-limit handling.
- [ ] Review active scan results manually and confirm any suspected issue with a narrowly scoped reproduction.

Automated DAST will not reliably identify every authorization/business logic flaw. Continue with the manual cases in Phase 4.

## 6. Focused security test matrix

Run these tests against the project's own test environment. Record request, response, expected behavior, actual behavior, result, and a linked regression test for each case. Do not include real tokens or personal data in the evidence.

| ID | Area | Test | Expected secure result |
|---|---|---|---|
| AUTH-01 | Authentication | Call every session-protected endpoint without a session | Request is rejected with the documented authentication response; no private data is returned. |
| AUTH-02 | Authentication | Use an invalid, expired, or tampered session cookie | Session is not accepted; private operations remain inaccessible. |
| AUTH-03 | Session lifecycle | Log in, log out, then replay the old session | Old session cannot continue to access protected routes after logout. |
| AUTH-04 | Session fixation | Compare the session before and after successful login | Login rotates/changes the session identifier according to the configured secure session behavior. |
| AUTH-05 | Account enumeration | Compare registration/login responses for nonexistent and existing accounts | Responses do not reveal unnecessary account information; any deliberate difference is risk-reviewed. |
| AUTH-06 | Rate limiting | Exceed authentication limits from one IP/account and test reset behavior | Limits trigger documented responses and do not disclose secrets. |
| AUTH-07 | Password handling | Inspect storage, responses, logs, and errors after registration/login | Passwords are hashed; plaintext passwords and hashes are not leaked in responses/logs. |
| AUTHZ-01 | Object authorization | Create user A and B; have A request B's drafts by ID | Access is denied or not-found behavior is returned consistently; B's data is never disclosed. |
| AUTHZ-02 | Object authorization | Have A edit, approve, or publish B's draft by altering the ID | Operation is rejected; ownership is checked server-side on every operation. |
| AUTHZ-03 | Object authorization | Have A access B's publication history or connection status | Results are scoped to the authenticated user. |
| AUTHZ-04 | Property authorization | Submit extra fields such as owner/user ID, approval state, external post ID, or internal status | Client cannot overwrite server-controlled properties unless explicitly allowed by the contract. |
| AUTHZ-05 | Function authorization | Call approval/publication/disconnection routes in invalid states or without required CSRF/session | Access and state rules are enforced consistently. |
| CSRF-01 | CSRF | Submit state-changing requests without a token or with an invalid token | Requests are rejected; read-only behavior remains consistent with policy. |
| CSRF-02 | CSRF | Attempt to reuse another session's token or submit token through unsupported channels | Token cannot be used to authorize another session's state-changing request. |
| CORS-01 | CORS | Send requests from an untrusted origin, wildcard origin, malformed origin, and allowed origin | Only exact configured origins are allowed; credentialed requests never use wildcard origin. |
| OAUTH-01 | OAuth state | Omit, alter, replay, or use expired `state` in callback | Callback rejects the attempt and does not connect an attacker-controlled identity. |
| OAUTH-02 | OAuth redirect | Try a redirect URI with changed host, scheme, port, path, query, or lookalike domain | Only the exact configured redirect URI is accepted. |
| OAUTH-03 | OAuth errors | Simulate token exchange, UserInfo, encryption, and persistence failures | No credential leaks; local state remains consistent; an already consumed state is not reused. |
| OAUTH-04 | Token protection | Inspect database, API responses, logs, and exception output | Tokens are encrypted at rest as designed and are never returned/logged in plaintext. |
| OAUTH-05 | Disconnect | Disconnect locally and retry protected LinkedIn operations | Local authorization is removed and cannot be used; messaging does not falsely claim remote revocation if none occurred. |
| PUB-01 | Publishing | Publish a valid approved draft with a valid idempotency key | One successful external publishing action is recorded consistently. |
| PUB-02 | Idempotency | Replay a successful publication with the same key | Existing result is returned without a second external publication. |
| PUB-03 | Idempotency | Reuse a key for different content or another draft | The conflict is rejected; the key cannot silently represent multiple operations. |
| PUB-04 | Concurrency | Send concurrent requests with the same key against the supported real database | At most one external publication is initiated; persisted result and HTTP responses are consistent. |
| PUB-05 | Failure handling | Simulate a known external failure, timeout, and ambiguous success | No unsafe automatic retry; state is documented, observable, and recoverable through an explicit policy. |
| PUB-06 | Workflow | Try publishing an unapproved, already failed, deleted, or another user's draft | Invalid transitions and ownership violations are rejected. |
| RATE-01 | Rate limiting | Exceed limits for login, registration, OAuth, drafts, and publication | Correct route-specific limit applies and returns documented `429` behavior. |
| RATE-02 | Distributed rate limiting | Repeat requests across app instances using the shared Redis setup | Limits are shared and cannot be bypassed by switching instances. |
| RATE-03 | Proxy/IP | Send forged `Forwarded` and `X-Forwarded-For` values from an untrusted client | Untrusted headers do not control client identity or rate-limit keys. |
| RATE-04 | Redis outage | Make Redis unavailable in `prod` profile | Sensitive rate-limited routes fail according to the documented fail-closed policy without leaking configuration. |
| INPUT-01 | Validation | Submit malformed/oversized title, body, IDs, and unsupported media types | Request is rejected safely with a controlled error and no partial state change. |
| INPUT-02 | Injection | Test metacharacters and injection-style input at search, filters, and persistence boundaries | Input remains data; queries are parameterized and no unintended query/execution occurs. |
| INPUT-03 | URL/SSRF | Review any URL-fetching or redirect behavior and test untrusted destinations only in the test environment | Server does not fetch arbitrary internal/metadata destinations or follow unsafe redirects. If no such feature exists, record SSRF as not applicable for the current build. |
| RESOURCE-01 | Resource consumption | Request huge page sizes, deeply paginated data, very large payloads, and repeated expensive calls | Limits bound CPU/memory/database work; page size remains capped. |
| RESOURCE-02 | Timeouts | Simulate a slow/unavailable LinkedIn API and database | Timeouts and failure responses are controlled; request pools are not exhausted indefinitely. |
| RESOURCE-03 | Concurrency | Issue parallel draft mutations and publish/approve transitions | State transitions are consistent and no race bypasses approval or ownership. |
| DATA-01 | Sensitive data | Inspect all DTOs, logs, errors, health output, and audit events | No secrets, tokens, session identifiers, or unnecessary personal data are exposed. |
| CONFIG-01 | Production profile | Verify `prod` disables Swagger/OpenAPI and does not expose debug routes | Documentation/debug functionality remains disabled in production. |
| CONFIG-02 | Actuator | Enumerate Actuator paths and inspect health details | Only intended health/operational data is accessible; environment/config dump endpoints are not exposed. |
| CONFIG-03 | Error handling | Cause validation, persistence, and upstream exceptions | Client sees a safe, stable error contract; internal stack traces and credentials remain server-side. |
| DB-01 | Database profile | Run migrations and application validation against a disposable Oracle instance | Flyway migrations succeed and Hibernate validates the expected schema. |
| DB-02 | Database integrity | Test uniqueness, FK constraints, transaction rollback, and data ownership | Database constraints protect integrity even when requests race or services fail. |
| DOC-01 | API inventory | Compare runtime routes with README/OpenAPI and this inventory | No undocumented or unintended public route remains; profile differences are recorded. |

### 6.1 Test cases especially important for this repository

**A. Two-user isolation:** create two local users with separate sessions. Create a draft and connect LinkedIn (or use a mocked connection) for each. Change IDs and request parameters across sessions. Verify all read and write operations are owner-scoped, including publication history and connection state.

**B. Idempotency race:** against the actual supported database, synchronize multiple requests with the same `Idempotency-Key` so they begin together. Verify database uniqueness/locking and the publication adapter do not permit duplicate upstream calls. Repeat with different keys and with the same key but different payloads.

**C. Ambiguous external response:** simulate a timeout after the mock provider has accepted the publication but before the backend receives the success response. Verify that the system records uncertainty and does not blindly retry. Document the recovery decision; “exactly once” cannot be assumed across a database and an external API without a provider-supported reconciliation mechanism.

**D. OAuth one-time state:** initiate authorization, use the callback once, then attempt the same callback again. Also test mismatched state, failed token exchange, and failed local persistence after state consumption. Confirm the state cannot be reused and that the user must start a new authorization when required by the defined flow.

**E. Production configuration:** start the application with the `prod` profile and missing/invalid mandatory secrets or dependencies. Verify startup or affected operations fail safely and do not fall back to insecure defaults. Confirm Redis, proxy behavior, HTTPS, cookies, Oracle migration, and API documentation settings match actual deployment.

## 7. Dynamic testing with OWASP ZAP

1. Start the application against disposable test data using the target security configuration.
2. Begin with passive/baseline scanning and manual exploration of documented endpoints.
3. Configure authentication/session handling only for test accounts; do not save real cookies or tokens into committed ZAP contexts.
4. Review alerts and false positives before enabling active scanning.
5. If active scanning is required, restrict it to the application host, known routes, and disposable data. Exclude LinkedIn and other external domains.
6. Inspect unusual responses manually and reproduce suspected findings with a narrow test.
7. Save the report as an assessment artifact outside public repository history if it contains sensitive data.

A clean ZAP report is not proof of security. Authorization, state-machine, OAuth, and idempotency requirements still need the targeted tests listed above.

## 8. Configuration and operations review

Review each item against the actual deployment, not only local configuration:

- [ ] HTTPS is enforced at the trusted ingress/reverse proxy.
- [ ] Production session cookies use `Secure`, `HttpOnly`, and an appropriate `SameSite` setting.
- [ ] Session expiration, logout invalidation, and session rotation are verified.
- [ ] CORS is allowlisted by exact origin and is compatible with credentialed sessions.
- [ ] CSRF remains enabled for state-changing session-authenticated operations.
- [ ] Swagger/OpenAPI is disabled or explicitly protected in production.
- [ ] Actuator exposes only approved operational information.
- [ ] Error responses do not disclose stack traces, query details, secrets, or upstream response bodies.
- [ ] Logs exclude passwords, access tokens, client secrets, encryption keys, session IDs, and unnecessary personal data.
- [ ] Encryption keys are generated with appropriate entropy, stored outside source control, rotated through a planned process, and are not reused as unrelated secrets.
- [ ] Database credentials have least privilege and come from the deployment secret store.
- [ ] Database migrations can be applied and the application can validate the resulting schema in a disposable Oracle environment.
- [ ] Redis is shared across production instances, authenticated, protected by TLS where supported, and unavailable Redis behaves as documented.
- [ ] The trusted proxy configuration cannot be spoofed by arbitrary client-supplied forwarding headers.
- [ ] External HTTP clients set reasonable connect/read timeouts and handle status codes, rate limits, and provider outages safely.
- [ ] Dependency updates and vulnerability reports are reviewed regularly.
- [ ] Backup, recovery, incident response, and credential rotation procedures are written and tested.

## 9. Findings and remediation process

Create one finding record for every confirmed vulnerability or material uncertainty. Use this template:

```text
ID:
Title:
Severity:
Status: OPEN | CONFIRMED | FALSE POSITIVE | MITIGATED | FIXED | ACCEPTED
Affected component/route:
Environment and commit SHA:
Evidence:
Reproduction steps:
Security impact:
Prerequisites/likelihood:
Proposed remediation:
Regression test:
Retest result and date:
Residual risk / rationale for acceptance:
```

The report should distinguish:

- **Confirmed vulnerability:** reproducible weakness with a demonstrated impact.
- **Potential vulnerability:** suspicious code/configuration requiring more evidence.
- **False positive:** finding reviewed and shown not to apply, with rationale.
- **Hardening recommendation:** useful improvement that is not currently a demonstrated exploit.
- **Known assurance gap:** behavior not yet verified, such as untested real-database concurrency.

Fix findings according to severity and business impact. Every fix should include a regression test where practical. After remediation, rerun the affected test, relevant automation, and the complete release checks.

## 10. Completion and release gates

Do not mark the assessment complete until all applicable items below have evidence:

- [ ] Route/asset inventory and threat model are reviewed and current.
- [ ] CodeQL or equivalent static analysis has been run and findings triaged.
- [ ] Dependency analysis has been run and vulnerable components have a disposition.
- [ ] Current files and full Git history have been scanned for secrets; any exposed real secret was rotated/revoked.
- [ ] Full build and automated tests pass in a clean environment with the target JDK.
- [ ] ZAP passive scan is reviewed; active testing, if needed, was isolated and authorized.
- [ ] Cross-account authorization and property/function authorization tests pass.
- [ ] Session, CSRF, OAuth, token storage, CORS, and rate-limit tests pass.
- [ ] Idempotency has been tested concurrently against the actual supported database.
- [ ] Ambiguous LinkedIn publishing outcomes have a documented safe recovery policy and tests.
- [ ] Production Oracle migrations and schema validation have passed against a disposable Oracle database.
- [ ] Redis and trusted proxy/IP handling have been verified in a target-like environment.
- [ ] Production documentation, Actuator exposure, cookies, HTTPS, logs, and error handling have been reviewed.
- [ ] No unresolved Critical or High findings remain. Any exception has documented owner approval, risk rationale, mitigation, and review date.
- [ ] Findings have been retested, regression tests added, and reports retained securely.
- [ ] A reviewer independent of the implementation has reviewed the highest-risk areas before public launch, when feasible.

Passing this checklist does not certify that the application is vulnerability-free. It establishes that a defined set of controls and risks has been assessed with documented evidence. The scope, tool versions, exclusions, and untested areas must accompany the final report.

## 11. Recommended execution order

1. **Inventory and threat model:** enumerate routes and assets; start with two-user authorization tests.
2. **Static analysis and secret hygiene:** CodeQL, Maven dependency analysis, full-history secret scanning, and configuration review.
3. **Focused integration tests:** OAuth state/replay, CSRF, session lifecycle, idempotency, race conditions, and ownership isolation.
4. **Dynamic scans:** ZAP passive scan, followed by scoped active scanning in a disposable environment.
5. **Production-like validation:** Oracle migration, Redis distribution, proxy/IP handling, cookies, HTTPS, and profile-specific endpoints.
6. **Remediation and regression:** prioritize critical/high findings; test fixes and rerun all security gates.
7. **Independent review:** have another developer or qualified security tester review authorization, OAuth, crypto, and publishing failure paths.

## 12. Official references

- [OWASP ASVS 5.0.0](https://asvs.owasp.org/) — verification standard. Use the stable version pinned for the assessment.
- [OWASP API Security Top 10 — 2023](https://owasp.org/API-Security/editions/2023/en/0x11-t10/) — common API-specific risks.
- [OWASP Web Security Testing Guide v4.2](https://owasp.org/www-project-web-security-testing-guide/v42/) — manual web security test methodology.
- [OWASP Dependency-Check](https://owasp.org/www-project-dependency-check/) — dependency vulnerability discovery.
- [GitHub CodeQL code scanning](https://docs.github.com/en/code-security/code-scanning/introduction-to-code-scanning/about-code-scanning) — static code analysis.
- [GitHub secret security](https://docs.github.com/en/code-security/concepts/secret-security) — repository secret scanning concepts and options.
- [Gitleaks](https://github.com/gitleaks/gitleaks) — secret scanning in files and Git history.
- [OWASP ZAP](https://www.zaproxy.org/) — dynamic application security testing.
- [OWASP CSRF Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html) — CSRF defense reference.
- [OWASP Session Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html) — session security reference.
- [OWASP OAuth 2.0 Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/OAuth2_Cheat_Sheet.html) — OAuth security guidance.

For project-specific current status and scope, also consult:

- [`docs/status-atual.md`](status-atual.md)
- [`docs/linkedin-capability-matrix.md`](linkedin-capability-matrix.md)
- [`docs/planejamento-tecnico.md`](planejamento-tecnico.md)
- [`SECURITY.md`](../SECURITY.md)

---

**Maintenance rule:** update this plan when the endpoint inventory, authentication model, deployment profile, database, LinkedIn API integration, or publication state machine changes. An item should be marked complete only when test evidence or a review record has been saved.
