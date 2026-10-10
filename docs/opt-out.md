# Turning Henosis Insights off

For **app developers** whose app contains an SDK that uses Henosis Insights. One switch turns it off
for every SDK in the app. While it is off nothing is queued or sent, and anything already queued is
deleted.

On Android the choice is remembered across launches. The Dart and JavaScript clients remember it only
when the SDK gives Insights persistent storage; otherwise call `setEnabled(false)` at every launch,
before or after the SDK starts, or use the no-code flag.

## Android

Either in `AndroidManifest.xml` (no code):

```xml
<application …>
    <meta-data android:name="com.devstrike.henosis.insights.ENABLED" android:value="false" />
</application>
```

Or at runtime, for example from your own privacy settings:

```kotlin
import com.devstrike.henosis.insights.HenosisInsights

HenosisInsights.setEnabled(false)   // true turns it back on
```

## Flutter / Dart

```dart
import 'package:insights_dart/insights_dart.dart';

HenosisInsights.setEnabled(false);
```

Or without code: the environment variable `HENOSIS_INSIGHTS_DISABLED=1` (Dart on desktop and servers),
or `globalThis.HENOSIS_INSIGHTS_DISABLED = true` set by the page before it loads (Flutter web).

If an Android SDK and its Flutter wrapper both use Insights, `setEnabled(false)` on the Android side
covers both.

## React Native, web and Node

```ts
import { HenosisInsights } from '@devstrike/insights-js';

HenosisInsights.setEnabled(false);
```

Or without code: `HENOSIS_INSIGHTS_DISABLED=1` in the environment (Node), or
`globalThis.HENOSIS_INSIGHTS_DISABLED = true` before the SDK loads (web).

## What it covers

The switch covers Henosis Insights only. It does not change anything else an SDK does. See
[`privacy/DATA.md`](../privacy/DATA.md) for what Insights would otherwise send.
