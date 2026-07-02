# RayNeo SDK libraries

Place the RayNeo X3 Pro SDK archives here (obtained from the RayNeo developer portal):

- `MercuryAndroidSDK-<version>.aar`
- `RayNeoIPCSDK-<version>.aar`

The app initializes the Mercury SDK in `PaleBlueApp` inside a `runCatching` block,
so the project still builds and runs (flat, non-compositor) without these AARs —
useful for emulator smoke tests. On device, the Mercury compositor drives both lenses.
