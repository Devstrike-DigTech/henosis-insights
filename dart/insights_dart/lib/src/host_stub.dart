import 'transport.dart';
import 'types.dart';

PlatformInfo detectPlatform() => const PlatformInfo(wrapperKind: 'other', os: 'other');

bool envOptOut() => false;

Future<PlatformInfo> refinePlatform(PlatformInfo p) async => p;

void Function()? onPageHide(void Function() callback) => null;

final class HostTransport implements Transport {
  const HostTransport();

  @override
  Future<SendResult> send(String endpoint, String writeKey, String body, {required bool keepalive}) async =>
      const Retry();
}
