# Google Play Data safety answers

For SDK vendors to give their customers (the app developers who publish on Google Play), and for app
developers whose app contains an SDK that uses Henosis Insights. Play requires apps to declare data
collected by SDKs inside them. These answers cover **only the Insights part**; your SDK and the app
may collect other data.

This is guidance from the SDK's side, not legal advice. Your privacy counsel has the final word.

## Data collection and security

| Play question | Answer for Henosis Insights | Why |
| --- | --- | --- |
| Does your app collect or share any of the required user data types? | **Yes** | Diagnostic data leaves the device |
| Is all of the user data collected by your app encrypted in transit? | **Yes** | Uploads use HTTPS only |
| Do you provide a way for users to request that their data is deleted? | Per your app's policy | Insights stores no user identifier, so no record can be matched to a person. Raw events are deleted after 90 days |

## Data types

Declare these under **App info and performance**. Nothing else applies.

| Data type | Collected | Shared | Processed ephemerally | Required or optional | Purposes |
| --- | --- | --- | --- | --- | --- |
| **Crash logs** | Yes | See below | No | Required, unless your app lets users turn diagnostics off | Analytics |
| **Diagnostics** | Yes | See below | No | Required, unless your app lets users turn diagnostics off | Analytics |

What falls under each, in Play's own terms:

- **Crash logs:** "stack traces, or other information directly related to a crash". Insights sends SDK
  error codes and stack frames from the SDK's own code only.
- **Diagnostics:** "information about the performance of your app… or any technical diagnostics".
  Insights sends SDK method call counts, start-up and health check results, deprecated-method counts and
  a daily heartbeat.

Not collected, so leave unchecked: Location, Personal info, Financial info, Health and fitness,
Messages, Photos and videos, Audio, Files and docs, Calendar, Contacts, App activity, Web browsing,
and **Device or other IDs** (Insights reads no device identifier).

### Shared

The data goes to the SDK vendor, who uses it to support your integration of their SDK. Play does not
count a transfer as "sharing" when the recipient is a service provider processing data on your
behalf. Whether that applies depends on your agreement with the SDK vendor. If it does not, also tick
**Shared** for both data types with purpose **Analytics**.

### Required or optional

Insights is on whenever the SDK is in use, unless the app turns it off with `HenosisInsights.setEnabled(false)`
or the manifest flag ([`docs/opt-out.md`](../docs/opt-out.md)). If your app exposes that switch to
users, answer **optional**.
