# Transport security

**English** | [简体中文](transport-security.zh-CN.md)

## Trust and user interaction

All phone endpoints use HTTPS (TLS 1.2/1.3). Android Keystore holds an independent,
non-exportable EC P-256 key for each installation. Python pins SHA-256 of the
certificate's DER SubjectPublicKeyInfo (SPKI), not its IP address or a JSON field.
No HTTP fallback, redirects, shared private key, or trust-all authenticated
connection exists. The initial credential-free identity probe is explicitly
unauthenticated; its observed pin is only a candidate until pairing is confirmed.

First pairing requires the user to compare **all eight digits** shown independently
by the phone and bridge, approve on the phone, and confirm the match on the
controller. CLI asks for `yes`; MCP accepts `confirm_pairing:true` only after the
user explicitly confirms. Agents must not read/approve through phone-control tools,
infer consent from a server response, or pre-confirm a new pairing. A malicious
server can claim it was approved, so phone approval alone cannot establish trust.
Subsequent connections verify the persisted pin without further prompts.

## Native wire protocol: phoneuse-sas-v1

Transport encryption and proof of private-key possession come from TLS. This
application-specific SAS exchange authenticates the candidate server key using a
hash commitment followed by independent random contributions. It is not a ZRTP
implementation or a replacement for TLS. The general commitment/SAS rationale is
described in [RFC 6189](https://www.rfc-editor.org/rfc/rfc6189.html#section-4.4.1.1).

All fields below are 32-byte values encoded as exactly 64 lowercase hex characters.
`P` is the observed SPKI pin. `C` and `S` are independently generated 256-bit random
nonces. Hash inputs are ASCII strings joined with a single NUL byte, with no
trailing NUL. Domain and purpose separators are literal strings:

```text
commitment = SHA256("phoneuse-sas-v1" || NUL || "commit" || NUL || P || NUL || C)
digest     = SHA256("phoneuse-sas-v1" || NUL || "sas" || NUL || P || NUL || C || NUL || S)
code       = zero_pad_8(big_endian_uint32(digest[0:4]) mod 100000000)
```

1. The bridge observes `P` in TLS, generates `C`, and sends only the commitment in
   `/pair/request` with `protocol` and `client_name`. It pins every following TLS
   socket to `P` before any HTTP bytes or secrets are sent.
2. After accepting the commitment, the phone generates `S`. It returns `S`, the
   protocol, pairing ID, claim secret, expiry, and polling interval. The request
   is `committed`, hidden from the approval list, and cannot issue credentials.
3. The bridge freezes `P,C,S`, independently computes its code, and sends `C` in
   `/pair/reveal`. It never displays a remotely supplied verification code.
4. The phone checks the commitment against **its own TLS public key** and `C`.
   A mismatch permanently denies that request. A match computes the phone code
   and changes the request to `pending`, making it visible for local approval.
5. After the user confirms both displays, the bridge permits `/pair/status` to
   claim a locally approved credential and atomically saves it with the pin.

The client commits before learning the phone nonce, and does not reveal until the
other contribution is fixed. Relaying a commitment for a substituted key fails;
using two independent transcripts requires guessing the compared code (roughly
one in 100 million per attempt). Limits: one new request per five seconds globally,
at most 16 live requests, two-minute expiry, one reveal value per request, no
automatic retry of a failed pairing. Denial, expiry, or service restart cannot
carry human confirmation into a new transcript. These limits also bound resources;
they do not eliminate denial of service. The human comparison and controller-side
confirmation remain part of the authentication mechanism.

Cross-language test vector: `P=00*32`, `C=11*32`, `S=22*32` (hex bytes):
commitment `9752824c0f5f6e1fb8abbea0ef2960d94e93db4125093766cdcb87bdf8b589e1`,
code `17242944`.

## Persistence, migration, and key changes

The phone key survives service/app restarts. Credentials use a new storage namespace
so previously exposed HTTP tokens cannot authorize HTTPS control. The Python
registry migrates old addresses to HTTPS but discards unpinned credentials and
pending claims. Both sides must be updated and paired again once.

A changed IP does not change key identity. Key mismatch fails closed before HTTP
data is sent and never replaces the saved pin. Independently verify an intended
reinstall/reset, revoke the old client on the phone, then explicitly remove the
local binding with `python CLIENT forget-device --device-id DEVICE_ID` and pair
again. Never automatically forget/rebind in response to a network error. SPKI
pinning permits certificate renewal with the same key; this native trust model
does not depend on public CA chains, hostnames, or certificate validity dates.
The phone key and identity/credential preferences are not backed up or migrated.

## Browser boundary

Direct browser access remains an HTTPS endpoint and uses Secure, HttpOnly,
SameSite=Strict cookies and strict IP Host/HTTPS Origin checks. Browser JavaScript
cannot authenticate an untrusted certificate on behalf of the browser. Its older
six-digit authorization code is not the native eight-digit SAS protocol and cannot
replace certificate trust. Establish browser certificate trust independently;
do not blindly bypass its warning. Use MCP/CLI for the automatic native trust flow.
This change does not add a local browser proxy, domain provisioning, or public CA.

Validation includes Kotlin/Python transcript vectors, actual loopback TLS pinning,
substituted-key rejection before HTTP data, confirmation gating, migration, and
Android Keystore TLS/pairing/API/MCP instrumentation. These checks are regression
evidence, not an independent cryptographic audit.
