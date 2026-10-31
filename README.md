# Sourcerer — a GitLab-style forge on Grails, built for read throughput

Create repositories, view their details, browse files; authenticated git clone/push over HTTP;
issues; merge requests.

## Layout

The **root project is the Grails application** (`grails-app/`, `src/main/`): controllers, URL mappings,
and the git smart-HTTP servlet. Everything it depends on is a Gradle subproject under `modules/`:

| module        | what                                                                   | depends on |
|---------------|------------------------------------------------------------------------|------------|
| `core`        | records, store ports, `ApiException`, JSON                             | –          |
| `security`    | PBKDF2 passwords, stateless HMAC tokens                                | core       |
| `gitstore`    | in-process JGit: init, refs, tree/blob, log, merge, diff               | core       |
| `persistence` | JDBC + HikariCP (embedded H2 by default, PostgreSQL via URL)           | core       |
| `service`     | auth, repos, browse (cached), issues, merge requests; `Platform` root  | all above  |
| `loadtest`    | virtual-thread HTTP load generator                                     | –          |

Domain modules are plain Java with no framework, so a change in `gitstore` recompiles only
`gitstore → service → root app`. Build cache, parallel execution and incremental compilation are on
(`gradle.properties`); shared build logic lives in `buildSrc` (`sourcerer.java-library`).

## Run

    ./gradlew bootRun                  # http://localhost:8080, data in ./data (H2 + bare repos)
    bash scripts/smoke.sh                   # end-to-end: API + real `git clone/push`
    ./gradlew :loadtest:run --args="http://localhost:8080 15 128 5"

Config (`grails-app/conf/application.yml`, or env e.g. `SOURCERER_DATA_DIR`):
`sourcerer.data-dir`, `sourcerer.jdbc-url`, `sourcerer.db-user/-password`, `sourcerer.token-secret`.

## API (JSON; `Authorization: Bearer <token>` or HTTP Basic)

    POST /api/auth/register | /api/auth/login | /api/auth/token   GET /api/user
    POST /api/repos {name, description, private, defaultBranch, initReadme}   GET /api/repos
    GET  /api/repos/{owner}/{repo}                   details + branch/tag counts + head commit
    GET  /api/repos/{owner}/{repo}/tree?ref=&path=   directory listing
    GET  /api/repos/{owner}/{repo}/blob?ref=&path=   file (text inline), /raw for bytes
    GET  /api/repos/{owner}/{repo}/branches | commits?ref=&page=
    GET|POST /api/repos/{o}/{r}/issues, GET|PATCH .../issues/{n}, GET|POST .../issues/{n}/comments
    GET|POST /api/repos/{o}/{r}/merge-requests, GET|PATCH .../{n}, GET .../{n}/diff, POST .../{n}/merge
    git clone http://localhost:8080/git/{owner}/{repo}.git      (push needs a token as the password)

## Why it is fast

* Git objects are read in-process (JGit) — no fork/exec. Open repositories and ref lookups are cached.
* Tree/blob/commit responses are cached **as serialised JSON bytes**, keyed by commit sha
  (immutable ⇒ never invalid); only branch→sha is short-lived and is invalidated on push/merge.
* `ETag` = commit sha → repeat reads are `304` with no body.
* Auth is stateless (one HMAC, no DB hit); password checks for git Basic auth are cached.
* No GORM/Hibernate/GSP on the request path; controllers write bytes straight to the response.
* Tomcat runs on virtual threads (`spring.threads.virtual.enabled`); ZGC in `bootRun`.

Measured on the dev machine (load generator on the same box, mixed details/tree/blob reads,
128 connections): **~19k req/s, p99 ≈ 15 ms, 0 errors**. That clears the 10k RPS target; it is not
a head-to-head against a Rust implementation, and I make no claim of beating one.

## Known limits

* Single node: metadata cache invalidation is in-process; run behind one instance, or move the
  caches to a shared tier before scaling out. Use PostgreSQL for anything but a demo.
* Tokens are stateless, so they cannot be revoked before expiry (max 365 days).
* Merge requests are branch-to-branch within one repository (no forks); owner-only repos (no
  collaborators/teams yet); merges create a merge commit (no squash/rebase).
* No rate limiting, email, or CI.

## Web UI

Open `http://localhost:8080/` after `./gradlew bootRun`. It is a dependency-free single-page app
(`src/main/resources/static/`: `index.html`, `app.js`, `style.css`; Grails serves it under `/static/**`
and `UrlMappings` forwards `/` to it) that talks to the JSON API above. Register/sign in, create and
delete repositories, browse branches/files/commits, read diffs, open and comment on issues, open/close/merge
merge requests, and generate a personal access token for `git push`. Static files are bundled into the build,
so restart `bootRun` after changing them. All user content is HTML-escaped before rendering.
