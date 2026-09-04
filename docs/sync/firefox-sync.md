# Firefox Sync client (Zen Browser spaces)

Candy's second sync backend. It speaks Mozilla's Firefox Sync protocol so Candy can exchange
Zen Browser's spaces, containers, pinned tabs, folders and split views with a Zen desktop through a
Mozilla account, with no extension installed in Zen. Candy Sync stays the self-hosted, workspace
E2EE backend for Chromium and Firefox devices; Firefox Sync is an additional backend that feeds the
same tab and space model.

The module is deliberately engine-independent: it is a plain JVM Kotlin library with no Android,
WebView or Compose dependency, so it runs unchanged in today's WebView-based Candy, in a future
Chromium-based Candy engine as a prebuilt jar, and in plain JVM unit tests.

## Ownership

| Layer | Responsibility | Main code |
| --- | --- | --- |
| Encoding | Bounded base64/base64url/hex/UTF-8, strict JSON, Zen-compatible canonical JSON | `firefox-sync/.../SyncEncoding.kt` |
| Account | Mozilla account OAuth code flow with PKCE, `keys_jwk` key pair, `keys_jwe` unwrapping to kSync | `FirefoxAccountOAuth.kt` |
| Keys | kSync to collection key bundle, `kid` and client-state derivations, HKDF | `SyncKeyBundle.kt` |
| Record crypto | Storage format 5: AES-256-CBC plus HMAC-SHA256 over base64 ciphertext | `SyncRecordCrypto.kt` |
| Storage wire | Token-server, `info/collections`, `meta/global`, `crypto/keys`, BSO arrays, POST results | `SyncStorageCodec.kt`, `SyncStorageModels.kt` |
| Authentication | Hawk request signing with payload hash and server clock offset | `HawkAuthenticator.kt` |
| Transport | OkHttp implementation of the account, token-server and storage endpoints | `FirefoxSyncTransport.kt` |
| Zen schema | `spaces` collection records and snapshot assembly | `ZenSpacesCodec.kt`, `ZenSpacesModels.kt` |
| Orchestration | Connect, fetch Zen spaces with paging, batched uploads with preconditions | `FirefoxSyncSession.kt` |

Everything above is pure protocol. Persistence of tokens and keys, the Android login surface, the
applier that maps Zen spaces onto Candy tabs and profiles, and the background scheduler are not
implemented yet and belong to `app/`, mirroring how Candy Sync splits `sync/` from `data/sync/`.

## Protocol summary

| Step | Endpoint | Candy behavior |
| --- | --- | --- |
| Login | `https://accounts.firefox.com/authorization` | PKCE S256, `access_type=offline`, `keys_jwk` P-256 public key, scopes `https://identity.mozilla.com/apps/oldsync` and `profile`. Web-channel clients pass `context=oauth_webchannel_v1` and read the `fxaccounts:oauth_login` message; redirect clients parse the redirect URI. Both check `state` in constant time |
| Token | `https://oauth.accounts.firefox.com/v1/token` | Public-client code exchange with `code_verifier`; refresh with `refresh_token`; `destroy` on sign-out |
| Keys | `keys_jwe` in the token response | Compact JWE, `ECDH-ES` with `A256GCM`, Concat KDF with SHA-256. The `oldsync` scoped key `k` is the 64-byte kSync; `kid` is `<rotation timestamp>-<base64url(SHA-256(kSync)[0..16])>` and is verified against the key |
| Storage login | `https://token.services.mozilla.com/1.0/sync/1.5` | `Authorization: Bearer <access token>` plus `X-KeyID: <kid>`; the response yields Hawk id/key and the node's `api_endpoint`, which must be https |
| Layout | `meta/global` (cleartext) | `storageVersion` must be 5; the Zen engine appears as `engines.spaces` with `version` 3 |
| Keys | `crypto/keys` | Decrypted with kSync split into encryption and HMAC halves; per-collection bundles override `default` |
| Records | `storage/spaces` | `full=1` pages of 500 with `X-Weave-Next-Offset`; each payload is decrypted and HMAC-checked with the `spaces` bundle before decoding |
| Writes | `POST storage/spaces` | Batches of at most 100 BSOs with `X-If-Unmodified-Since`; a concurrent Zen write fails with 412 rather than being overwritten |

Timestamps are decimal seconds as the server formats them. Outgoing JSON is always emitted in
canonical key order so requests are byte-identical on Android and on the JVM.

## Zen spaces schema (engine version 3)

Cleartext is `{id, kind, data}`; tombstones are `{id, deleted: true}`. Ids are Sync BSO ids.

| Kind | Id | Data |
| --- | --- | --- |
| `container` | container guid; Firefox's built-in containers are `builtin-1` to `builtin-4` on every device | `guid`, `name`, `icon`, `color` |
| `space` | space uuid | `uuid`, `name`, `icon`, `theme` (opaque Zen JSON), `containerGuid`, `children` (ordered pinned tab, folder and split ids) |
| `tab` | Zen sync id of a pinned or essential tab | `tabId`, `url`, `title`, `icon`, `containerGuid`, `essential`, `workspaceUuid` (null for essentials), `folderId`, `staticLabel`, `hasStaticIcon`, `defaultContainer` |
| `folder` | folder id | `folderId`, `name`, `icon`, `workspaceUuid`, `parentFolderId`, `live` (opaque Zen JSON), `children` |
| `split` | split-view group id | `splitId`, `gridType`, `tabs` (at least two), `workspaceUuid`, `folderId` |
| `layout` | fixed id `layout` | `spaces` (ordered uuids) and `essentials` (container guid or `default` to ordered tab ids) |

- Zen syncs only pinned and essential tabs, never the whole strip. Its engine projects the full
  local state on every sync and diffs against the last acknowledged upload, so Candy must upload
  complete records rather than partial patches.
- Unknown kinds decode to `null`. The session reports them as skipped ids and never tombstones them,
  matching Zen's own handling of records from newer clients.
- Opaque values are preserved as canonical JSON text. `ZenSpacesCodec.digest` reproduces Zen's
  `recordDigest` so an uploaded-state map can be shared with the desktop's semantics.
- The schema is internal to Zen with no compatibility promise. A different `engines.spaces.version`
  yields `UnsupportedEngineVersion`, which disables reading and writing until Candy is updated.
  Re-read `src/zen/sync/ZenSpacesSyncModel.sys.mjs` in the Zen repository when bumping the version.

## Boundaries

- The OAuth client id must be registered with Mozilla for the `oldsync` scope. Candy has no
  registration yet; the config type accepts any registered id and the Fenix web-channel redirect.
- Private tabs, Link Peek previews and Candy Sync workspace secrets never enter Firefox Sync records.
- Firefox Sync's `addons` collection lists Firefox add-on ids. It is not read: Chromium extensions
  cannot be represented there in either direction.
- Every network endpoint must be https; plain http is accepted only for loopback test servers.

## Verification

| Layer | Check |
| --- | --- |
| Key derivations, `kid`, HKDF, storage record crypto, Hawk MAC, PKCE, JWE unwrapping | Known-answer vectors generated independently with Python `cryptography` in `SyncKeyBundleTest`, `SyncRecordCryptoTest`, `HawkAuthenticatorTest`, `FirefoxAccountOAuthTest` |
| Wire codecs and canonical JSON | `SyncStorageCodecTest`, `SyncEncodingTest` |
| Zen record kinds, rejection rules, round trips, snapshot ordering | `ZenSpacesCodecTest` |
| Paging, engine-version guard, batched uploads, key selection | `FirefoxSyncSessionTest` with a fake transport |
| HTTP headers, Hawk signing, paging headers, error mapping | `OkHttpFirefoxSyncTransportTest` with MockWebServer |

Run the suite without an Android SDK:

```sh
./gradlew :firefox-sync:test
```

## Roadmap

| Step | Scope |
| --- | --- |
| Account surface | WebView login that injects the web-channel listener, Keystore-protected token and key storage, refresh and sign-out |
| Applier | Map containers to per-tab WebView profiles, spaces to Candy spaces, pinned and essential tabs, folders and splits onto the tab model; upload Candy changes through `uploadZenSpaces` |
| Standard collections | `tabs`, `bookmarks`, `history` and `passwords` engines on the same session once the spaces path is proven against a real Zen install |
| Engine fork | The Chromium-based Candy engine with Chrome extension support consumes this module as a prebuilt jar; nothing here changes |
