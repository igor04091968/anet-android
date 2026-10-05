# Historical gw2 Android compatibility investigation

This note describes a previous legacy-gateway test, not the native source used by ANet 1.0.3. Current native source is `../anet-vpn`, branch `feature/android-diagnostics-tuning-20261005`; use imported profiles matching the live GOST/new server. No production server or laptop route was changed for the new diagnostics/tuning release.


The live gateway at UDP 144.31.85.160:443 uses the pre-billing protobuf
envelope: message fields 1–6, padding bytes in field 7. A bounded phase-I
probe authenticated the response encryption and verified its Ed25519 signature.
Its outer fields were DHServerExchange (2) and padding (7).

Upstream commit 2abe965 reused field 7 for AuthDenyNotification and moved
padding to field 8. Both previously packaged native revisions used that newer
schema. They attempted to parse random padding as an error message, producing
varying protobuf wire-type failures. Generic TLS/QUIC fragmentation strings in
a server binary do not identify the ANet handshake protocol.

There is also a transport incompatibility: the new UDP sender prepends a
two-byte fragmentation header even when frag_enabled is false. The legacy
gateway expects the nonce at byte zero and ignores these new packets. Legacy
mode sends raw encrypted datagrams without that header in phases I and III.

The native source in ../anet-updated now accepts the root TOML option
`legacy_padding_tag = true`. It explicitly encodes and decodes the older
envelope in all handshake layers. New-protocol decoding remains the default.
Signature verification, authenticated encryption and TLS verification remain
enabled. Legacy mode rejects session-resumption requests.

The Android wrapper imports the bundled compatible profile once, identified
by `gw2-padding7-v1`, and selects it. Existing saved profiles remain intact.
This is necessary because replacing an APK asset does not replace preferences.

Validation commands (no server configuration or laptop route changes):

```sh
python3 scripts/probe_phase1.py app/src/main/assets/default-client.toml
cd ../anet-updated
cargo test --release -p anet-client-core wire_tests
cargo test --release -p anet-client-cli --example probe_quic -- ../anet-android/app/src/main/assets/default-client.toml
```

The phase-I probe does not create a VPN session. The Rust QUIC probe creates
a temporary client session, checks the gateway ICMP echo response, then closes
the QUIC connection. It creates no TUN interface and needs no elevated privileges.

The operator config, signing key and personalized APK remain local and ignored
by Git. Native backups and the previous APK are under native-backups/.
Actual Android installation and device behavior require a tablet test.
