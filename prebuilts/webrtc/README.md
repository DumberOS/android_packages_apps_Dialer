# WebRTC prebuilts

Normal Dialer and ROM builds use this directory directly. No WebRTC source
checkout, Rust, depot_tools, or external NDK installation is needed.

The bundle contains the matching Java/JNI bindings and native libraries for
ARM64 and ARM32, with upstream and dependency licenses. `manifest.json` pins
the upstream revision and SHA-256 hashes. Validate it from the ROM root with:

```sh
python3 packages/apps/Dialer/tools/verify-webrtc-prebuilts.py
```

Only maintainers updating WebRTC run `tools/prepare-webrtc.sh`, with an external
checkout and official depot_tools. Commit the JAR, both native libraries,
licenses and manifest together. Never run an updater during a ROM build.

The Rust messaging/signaling core belongs to Messaging's separate
`prebuilts/dumber-chat` bundle; Dialer accesses it through the shared Binder API.
