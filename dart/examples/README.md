# Dart examples

`acme_kyc` is a fictional pure-Dart identity-verification SDK that embeds `insights_dart`.
`acme_kyc/example` is a Flutter app (Android, iOS, web) that uses it and flushes on launch.

It defaults to the public fixture write key and a local ingest (`http://127.0.0.1:8787` on the web,
`http://10.0.2.2:8787` from the Android emulator); see `../../js/examples/README.md` for starting one.

```bash
cd acme_kyc/example
flutter build web --release        # then serve build/web and open it
flutter build apk --release        # then install on an emulator and launch
```

The Android manifest allows plain HTTP for this local example only.
