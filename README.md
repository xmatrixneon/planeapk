# PlaneAPK

SMS Gateway system with stealth mode capabilities for Android devices.

## Features
- SMS forwarding via WebSocket connection
- Stealth mode with automatic service resurrection
- FCM messaging support
- Background operation with battery optimization exemption

## Tech Stack
- **Language:** Kotlin
- **UI:** Jetpack Compose with Material3
- **DI:** Hilt
- **Networking:** OkHttp WebSocket
- **Storage:** DataStore Preferences
- **Build:** Gradle Kotlin DSL

## Build Instructions

### Prerequisites
- Android SDK 36 (Android 16)
- JDK 11
- Keystore for release signing

### Build Commands
```bash
./gradlew assembleDebug      # Debug APK
./gradlew assembleRelease    # Release APK
./gradlew installDebug       # Install on connected device
```

### Configuration
Add to `local.properties`:
```properties
STORE_FILE=path/to/keystore.jks
STORE_PASSWORD=your_password
KEY_ALIAS=your_alias
KEY_PASSWORD=your_key_password
API_BASE_URL=https://your-server.com
WEBVIEW_URL=https://your-dashboard.com
```

## Package
`cryptonic.synchere.com`

## Version
1.1 (versionCode 1)

## License
Proprietary
