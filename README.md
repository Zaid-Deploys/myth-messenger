# MYTH — Private, Ephemeral & End-to-End Encrypted Messaging

Myth is a production-grade, privacy-first messaging application designed around three uncompromising principles:
1. **End-to-End Encryption (E2EE)** using a Signal-compatible **Double Ratchet** protocol with forward secrecy and post-compromise security.
2. **Strict Message Non-Persistence**: Neither the backend server nor the Android client stores message plaintext, ciphertext, attachments, or delivery queues in persistent databases.
3. **Read-Based 5-Second Self-Destruction**: The 5-second countdown timer triggers only after *both* conversation participants have confirmed reading the message, after which it is purged permanently from device memory.

---

## Architecture Overview

```
                      +---------------------------------------+
                      |       Android Client (Compose)        |
                      | - Android Keystore for master key     |
                      | - FLAG_SECURE & ScreenCaptureCallback |
                      | - Memory-only VolatileChatStore       |
                      | - DoubleRatchetSession (Local RAM)    |
                      +-------------------+-------------------+
                                          |
                        HTTPS (REST)      |   WSS (Encrypted Payloads)
                        Auth, Contacts,   |   Real-time Relay,
                        PreKey Bundles    |   Read Receipts, 5s Countdown
                                          |
                      +-------------------v-------------------+
                      |       Backend Server (Ktor/Netty)     |
                      | - Token-Bucket Rate Limiter           |
                      | - VolatileRelayManager (RAM only)     |
                      | - 60s Undelivered Message TTL         |
                      | - Zero Message DB Storage             |
                      +-------------------+-------------------+
                                          |
                      +-------------------v-------------------+
                      |      Relational Database (SQLite/PG)  |
                      | - Users & Argon2id Password Hashes    |
                      | - Public Identity & PreKeys ONLY      |
                      | - Contact Relationships & Blocks      |
                      | * ZERO MESSAGE TABLES EXIST *         |
                      +---------------------------------------+
```

---

## Cryptographic Specification

- **Password Hashing**: Argon2id with 19 MiB memory cost, 3 iterations, 1 parallelism, and 16-byte random salt.
- **Key Agreement (X3DH)**: Extended Triple Diffie-Hellman using X25519 key agreements and Ed25519 identity key signatures.
- **Double Ratchet**:
  - Symmetric-key KDF chain advancing on every sent message for immediate forward secrecy.
  - Asymmetric Diffie-Hellman ratchet advancing on remote DH ephemeral keys for post-compromise break-in recovery.
  - Authenticated Encryption with Associated Data (AEAD) using **ChaCha20-Poly1305** with 128-bit authentication tags and sequence number binding.
- **Safety Numbers**: 60-digit deterministic cryptographic fingerprints formatted into 12 chunks of 5 digits for out-of-band identity verification.

---

## Strict Message Non-Persistence

1. **Zero Database Retention**: The persistent database schema contains only `users`, `public_keys`, `one_time_prekeys`, `contacts`, and `blocked_users`. No table for messages, history, or queues exists.
2. **Volatile Buffer (60s TTL)**: If a recipient is temporarily offline, the server buffers the encrypted ciphertext in volatile RAM for a maximum of 60 seconds. If the TTL expires, the payload is erased and the sender receives an `UNDELIVERED` notice.
3. **5-Second Self-Destruction**: When the recipient views the message, an authenticated read receipt is transmitted to the sender. Once both devices confirm the read receipt, a 5-second countdown timer runs simultaneously on both screens. At 0s, the message is wiped from active memory.

---

## Android Security Protections

- **`FLAG_SECURE`**: Enforced on the application window across all screens, preventing OS-level screenshots, screen recording, and system app-switcher previews.
- **Screenshot Detection (Android 14+ / API 34+)**: Uses `Activity.ScreenCaptureCallback` to detect screen captures and immediately transmit a security alert to the conversation peer.
- **Backup Disabled**: `android:allowBackup="false"` and `data_extraction_rules.xml` strictly prevent cloud backups or device-to-device transfer of cryptographic state.

---

## Honest Security Boundaries

No software application can make physically impossible guarantees. Myth transparently documents its threat boundaries:
- **External Cameras**: Myth cannot prevent an external optical camera from photographing the physical phone display.
- **Rooted / Compromised Devices**: On rooted devices with kernel-level instrumentation, untrusted physical access can inspect volatile RAM.
- **Malicious Endpoints**: A compromised recipient OS or modified client binary could capture displayed text before self-destruction.

---

## Build, Test & Run Instructions

### Prerequisites
- JDK 21 (`/home/zaidshaikh/.jdks/jdk-21.0.4+7` or standard JDK 21)
- Android SDK (API 34/35)

### 1. Run Automated Test Suites
```bash
# Run Cryptography Tests (Argon2id, Double Ratchet, Tamper Proof, Safety Numbers)
./gradlew :core-crypto:test

# Run Server Tests (Auth, Contacts, PreKeys, Volatile Relay, Non-Persistence)
./gradlew :server:test

# Run Android Unit Tests
./gradlew :android:app:testDebugUnitTest
```

### 2. Start the Backend Server
```bash
./gradlew :server:run
```
The server will start on port `8080`. Health check is available at `http://localhost:8080/health`.

### 3. Build the Android Application
```bash
./gradlew :android:app:assembleDebug
```
The output APK is generated at:
`android/app/build/outputs/apk/debug/app-debug.apk`

### 4. Run with Docker Compose
```bash
docker-compose up --build
```
