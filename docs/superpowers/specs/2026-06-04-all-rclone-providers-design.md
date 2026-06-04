# Design: select and configure any rclone provider (0.2.0)

Status: approved design, pre-plan. Target release: 0.2.0.

## Problem

Today the Add-remote flow offers three OAuth chips (Google Drive, OneDrive,
Dropbox) plus a freeform `type` + `key=value` editor. Every other rclone
backend — S3, B2, SFTP, WebDAV, Swift, pCloud, the crypt/union wrappers, and
the rest — is reachable only by hand-typing rclone option keys you have to
already know. The goal for 0.2.0: let a user pick any rclone backend and
configure it through a form built from rclone's own option schema, with OAuth
handled for them.

## What's already in place

The data layer is most of the way there and the design builds on it rather than
replacing it:

- `RcloneEngine.providers(): List<RemoteProvider>` calls the daemon's
  `config/providers` and `RcloneJson.parseProviders` maps it into
  `RemoteProvider` / `RemoteOption` models.
- `RemotesViewModel` already fetches that schema lazily into a `StateFlow`.
- `engine.createRemote(name, type, params)` (`config/create`) is the single
  creation path; `importConfig` and the freeform editor exist as fallbacks.

So this is a UI + classification + OAuth-provisioning problem, not a
data-modeling one.

## Decisions (locked during brainstorming)

1. **Config depth:** full schema-driven form for credential backends — typed
   fields, required vs advanced, examples/enums, sensitive masking, validation.
2. **OAuth coverage:** bundled first-party clients for Google Drive, Dropbox,
   Box, and OneDrive; BYOK for every other OAuth backend. Drive, Dropbox, and
   OneDrive already ship today; Box is *added* to the bundled set in 0.2.0.
3. **OAuth mechanism:** hybrid. Drive/Dropbox/OneDrive keep Virga's Custom Tabs +
   PKCE flow with no secret in the APK. Box is the exception: its token exchange
   needs a `client_secret`, so bundling Box means shipping a (non-confidential,
   public-client) secret and teaching `OAuthTokenExchanger` to send it — the
   strict "no secret in the APK" property holds only for the PKCE three. The long
   tail is driven by rclone's own daemon OAuth over the RC config flow, so no
   per-provider endpoint metadata is maintained.
4. **Meta/wrapper backends:** in scope. crypt, union, alias, combine, chunker,
   compress, cache, and hasher get a "wrap an existing remote" sub-flow.
5. **Connectivity test before save:** kept (see Validation).
6. **crypt:** its existing dedicated screen folds into the new wrapper sub-flow;
   there is no separate crypt UI after 0.2.0.

## Architecture

### ProviderCatalog (`core:rclone`)

A classification layer over `providers()`. Each backend resolves to one
`SetupKind`:

- `Credential` — render the schema form.
- `OAuth(bundled: Boolean)` — bundled four vs BYOK long-tail. A backend is
  treated as OAuth when its option set contains a `token` option alongside
  `client_id` (rclone's convention for the OAuth backends). `bundled` is not
  inferred from options at all; it's set by the override map below for exactly
  the four we ship clients for. Everything else with that option shape is BYOK.
  This keeps a stray non-OAuth backend that happens to expose a `token` field
  from being misread — bundling is an allowlist, not a heuristic.
- `Wrapper` — references other remotes rather than credentials (the fixed set:
  crypt, union, alias, combine, chunker, compress, cache, hasher).

Classification is derived from rclone's metadata, plus one short curated
override map that: pins popular providers to the top of the picker, marks which
OAuth backends are bundled, and hides `local`. The override map is the only
hand-maintained list, and it stays small — new rclone backends classify
automatically.

### Schema model (extend `RemoteOption`)

The form needs, per option: name, help text, value type (string, bool, int,
SizeSuffix, Duration, enum), examples (value + help), default, required flag,
`IsPassword`/sensitive flag, and advanced flag. Most of this is already in the
`config/providers` payload; the design fills any gaps in `RcloneJson` parsing
and the model.

One concrete consequence: `RemotesViewModel.optionsForBackend` today filters
`!it.advanced`, so it can't back the form — the "Advanced" expander needs the
advanced options too. The form reads the full option list and partitions it
into required / basic / advanced itself; the existing filtered accessor stays
for whatever else uses it, or gets a sibling that returns everything.

### UI (`feature:remotes`)

- **Picker:** searchable. Popular cloud pinned, then "All providers," then
  "Advanced · wrap a remote." Each row shows the provider mark + display name.
- **Credential form:** `RemoteOption`s → typed Compose fields (text,
  password-masked, switch, number, dropdown for examples/enums). Required first;
  an "Advanced" expander holds the rest. Validation from the schema.
- **OAuth sub-flow:** bundled → existing Custom Tabs/PKCE; BYOK → a client-id
  (and secret where the backend needs one) entry, then rclone-delegated OAuth.
- **Wrapper sub-flow:** pick base remote(s) from the configured remotes — a
  multi-select for union upstreams, a single picker for crypt/alias/etc. — plus
  the wrapper's own options, then create.

### Creation path

All three kinds converge on `engine.createRemote(name, type, params)`
(`config/create`). Today that call sets only `opt.nonInteractive`; the existing
`createCryptRemote` adds `opt.obscure=true` because its values are plaintext
passwords. The blanket `obscure` flag can't move up to `createRemote` unchanged:
it would also rewrite an OAuth `token` (already-formatted JSON) and corrupt it.
So `createRemote` gains a way to name *which* keys to obscure — a
`sensitiveKeys: Set<String>` derived from each option's `IsPassword`/sensitive
flag — and obscures only those. `createCryptRemote` collapses into this once the
crypt path runs through the generic form. The mock engine in the unit tests
takes the same signature.

The BYOK rclone-delegated flow is the one path that does *not* hand fully-formed
params to `createRemote`: rclone's daemon runs the interactive OAuth and writes
the remote itself as the exchange completes. To keep "one creation path" honest,
that flow still routes its non-token params (and the obscure decision) through
the same code, and the post-create connectivity test is identical — only the
token acquisition differs. The freeform editor and `importConfig` remain
fallbacks, not a parallel creation path.

## Data flow

- **Credential:** pick provider → cached schema → render form → fill → validate
  → obscure sensitive → `config/create` → connectivity test → save → refresh.
- **OAuth (bundled):** pick → name → Custom Tabs (PKCE) → token →
  `config/create` with token → test.
- **OAuth (BYOK / long-tail):** pick → name → enter client id (+secret) → rclone
  daemon builds the auth URL → Virga opens it in a Custom Tab and brokers the
  redirect back → daemon completes the exchange and writes config → test.
- **Wrapper:** pick → name → choose base remote(s) + wrapper options →
  `config/create` → test.

## Validation (connectivity test)

After `config/create`, run a quick check — `operations/about`, falling back to a
root `listDir` for backends without `about`. Success finalizes the remote; a
failure keeps the entered config but warns, so a wrong secret can't masquerade
as a working remote. The test is bounded by a timeout and never blocks the UI
thread.

## Error handling

- `config/providers` fetch failure → fall back to the current freeform
  `key=value` editor (the existing safety net stays).
- OAuth cancel / timeout / `state` mismatch → surfaced via the existing
  `OAuthResult.Error` path.
- `config/create` and test failures → mapped to the existing `VirgaError`
  hierarchy and shown inline on the form.

## Testing

- Pure-function unit tests for `ProviderCatalog` classification and the
  schema→form-field mapping, off a committed real `config/providers` JSON
  fixture (so rclone version bumps that change the schema are caught).
- ViewModel tests per sub-flow against a mock engine: credential create, bundled
  OAuth, BYOK OAuth, wrapper create, and the connectivity-test keep/warn branch.
- Roborazzi snapshots: the picker and one representative credential form.
- Instrumented e2e: create a credential remote and a crypt/union wrapper over an
  existing remote against a local fake, on device.

## Primary risk — validate first

The rclone-delegated OAuth path (driving rclone's interactive config OAuth over
the RC API and brokering the Android redirect) is the unproven piece. The
implementation plan opens with a spike to confirm it end to end. If it proves
too fiddly within the release window, the documented fallback for the long tail
is BYOK "paste a token / `rclone.conf` fragment," which is already supported —
the bundled four and the credential/wrapper forms don't depend on the spike.

## Scope boundaries

- `local` is excluded from the picker; it's device storage, and sync-source
  folder picking already exists elsewhere.
- No per-provider bespoke screens. Everything credential-side is schema-driven.
- crypt's standalone screen is removed; crypt is configured only through the
  wrapper sub-flow. Existing crypt remotes in `rclone.conf` keep working — the
  config format is unchanged, only the dedicated creation screen goes away.
- No remote *editing* redesign in 0.2.0 beyond what create needs; editing an
  existing remote through the same dynamic form is a candidate follow-up, not a
  requirement here.

## Affected modules

- `core:rclone` — `ProviderCatalog`, `RemoteOption`/`RcloneJson` extensions,
  rclone-delegated OAuth helper, `createRemote` sensitive-key obscuring, Box
  added to `OAuthProvider` plus `client_secret` support in `OAuthTokenExchanger`.
- `feature:remotes` — picker, dynamic form, the three sub-flows, ViewModel
  state, removal of the standalone crypt screen.
- `core:designsystem` — any shared form-field components the form needs.
- Tests across the above; a `config/providers` fixture under test resources.
