# Audit 1: Production Readiness

| | |
|---|---|
| Repo | `yvs1n/ApexDrive` @ `6b31f21` (main) |
| Date | 2026-10-21 |
| Scope | Bugs, fake status labels, security and privacy, performance, lifecycle, build and release, and every hardcoded vehicle or head-unit assumption |
| Method | Static read of every Java, XML and Gradle file in the tree, with every `file:line` below re-checked against `6b31f21`. Nothing was compiled or run: there is no Android SDK, emulator or head unit in this environment |
| Out of scope | UI/UX (Audit 2) and the feature roadmap (Audit 3). Where a finding touches them it points to the other audit instead of designing the fix here |

## How to read this document

Each finding is one block:

> **P-xx · Severity · `path:line`**
> **What happens:** observable behaviour. **Why it matters:** impact on the driver or the project. **Fix:** smallest change that works.

**Severity**

| Level | Meaning |
|---|---|
| Critical | Can leave the head unit unusable or leak data outside the car with no way to stop it |
| High | Wrong or unsafe behaviour on the main flows, or blocks open-source contribution or release |
| Med | Wrong behaviour on a secondary flow, or real cost in battery, CPU, memory or maintenance |
| Low | Polish, hygiene, future risk |

**Tags.** `[verify in car]` marks anything whose real behaviour depends on the head unit (Bluetooth stack, radio tuner, CAN bus, vendor broadcasts, vendor Settings keys, `Runtime.exec` permissions). I could only read the code, so those findings state what the code assumes and what to observe, not what the unit does. `[inferred]` marks a conclusion drawn from naming or history rather than from code.

**Fix constraints.** Every fix below works on API 21 to 28 with the current dependencies (AppCompat 1.4.2, Material 1.6.1, ConstraintLayout 2.1.4, CardView 1.0.0, which pulls in `androidx.core` 1.7) unless the fix says it needs a newer dependency.

**What is already fine.** No analytics, ad or crash-reporting SDK, no WebView, no `exec` with user-controlled input, no secrets in the tree, `MediaSyncService` is correctly protected by `BIND_NOTIFICATION_LISTENER_SERVICE`, `BootReceiver` correctly does not call `startActivity`, and `onBackPressed` correctly never leaves the launcher.

<!-- SUMMARY -->
