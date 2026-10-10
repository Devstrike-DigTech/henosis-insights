/// Outcome of one upload attempt.
sealed class SendResult {
  const SendResult();
}

/// 2xx: the ingest took the batch.
final class Accepted extends SendResult {
  const Accepted();
}

/// 4xx other than 408/429: the batch itself is bad; retrying will not help.
final class Rejected extends SendResult {
  const Rejected(this.status);
  final int status;
}

/// 408, 429, 5xx or a network failure: keep the events and try later.
final class Retry extends SendResult {
  const Retry([this.status]);
  final int? status;
}

SendResult classify(int status) {
  if (status >= 200 && status < 300) return const Accepted();
  if (status == 408 || status == 429 || status >= 500) return Retry(status);
  if (status >= 400) return Rejected(status);
  return Retry(status);
}

/// Sends one serialised batch. Implementations never throw.
abstract interface class Transport {
  Future<SendResult> send(String endpoint, String writeKey, String body, {required bool keepalive});
}

const Duration requestTimeout = Duration(seconds: 15);

/// Browsers cap keepalive request bodies at 64 KB.
const int keepaliveMaxBytes = 60000;
