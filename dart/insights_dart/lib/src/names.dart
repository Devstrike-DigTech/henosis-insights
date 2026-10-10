import 'sha256.dart';

/// Normalisers that make any vendor-supplied identifier fit schema v1's bounded patterns.
abstract final class Names {
  static final _method = RegExp(r'^[A-Za-z_$][A-Za-z0-9_.$-]{0,127}$');
  static final _code = RegExp(r'^[A-Z][A-Z0-9_]{0,63}$');
  static final _check = RegExp(r'^[a-z][a-z0-9_]{0,63}$');
  static final _version = RegExp(r'^[A-Za-z0-9+._-]{1,64}$');
  static final _customerKey = RegExp(r'^[A-Za-z0-9_-]{8,128}$');
  static final _sdkName = RegExp(r'^[a-z0-9][a-z0-9._-]{0,63}$');
  static final _appId = RegExp(r'^[A-Za-z0-9@][A-Za-z0-9._:/-]{0,255}$');
  static final _frameClass = RegExp(r'^[A-Za-z_$@][A-Za-z0-9_.$<>/@-]{0,255}$');
  static final _frameFn = RegExp(r'^[A-Za-z_$<][A-Za-z0-9_.$<>-]{0,127}$');

  static String _cut(String s, int n) => s.length > n ? s.substring(0, n) : s;

  static String method(String raw) {
    if (_method.hasMatch(raw)) return raw;
    var c = raw.replaceAll(RegExp(r'[^A-Za-z0-9_.$-]'), '_');
    if (c.isEmpty) c = 'unknown';
    if (!RegExp(r'^[A-Za-z_$]').hasMatch(c)) c = '_$c';
    return _cut(c, 128);
  }

  static String code(String raw) {
    if (_code.hasMatch(raw)) return raw;
    var c = raw.toUpperCase().replaceAll(RegExp(r'[^A-Z0-9_]'), '_').replaceAll(RegExp(r'^_+|_+$'), '');
    if (c.isEmpty) c = 'UNKNOWN';
    if (!RegExp(r'^[A-Z]').hasMatch(c)) c = 'E_$c';
    return _cut(c, 64);
  }

  static String checkName(String raw) {
    if (_check.hasMatch(raw)) return raw;
    var c = raw
        .toLowerCase()
        .replaceAll(RegExp(r'[^a-z0-9_]'), '_')
        .replaceAll(RegExp(r'_+'), '_')
        .replaceAll(RegExp(r'^_+|_+$'), '');
    if (c.isEmpty) c = 'custom';
    if (!RegExp(r'^[a-z]').hasMatch(c)) c = 'c_$c';
    return _cut(c, 64);
  }

  static String permissionCheck(String permission) => checkName('permission_${permission.split('.').last}');

  static String? version(String? raw) {
    if (raw == null || raw.isEmpty) return null;
    if (_version.hasMatch(raw)) return raw;
    final c = _cut(raw.replaceAll(RegExp(r'[^A-Za-z0-9+._-]'), '_'), 64);
    return c.isEmpty ? null : c;
  }

  static String sdkName(String raw) {
    if (_sdkName.hasMatch(raw)) return raw;
    final c = _cut(raw.toLowerCase().replaceAll(RegExp(r'[^a-z0-9._-]'), '-').replaceAll(RegExp(r'^[._-]+'), ''), 64);
    return c.isEmpty ? 'sdk' : c;
  }

  static String? appId(String? raw) => raw != null && _appId.hasMatch(raw) ? raw : null;

  /// The key if already hash-like; otherwise its SHA-256.
  static ({String key, bool ok, bool missing}) customerKey(String raw) => _customerKey.hasMatch(raw)
      ? (key: raw, ok: true, missing: false)
      : (key: sha256Hex(raw), ok: false, missing: raw.trim().isEmpty);

  static String? frameClass(String raw) {
    final c = _cut(raw.replaceAll(RegExp(r'[^A-Za-z0-9_.$<>/@-]'), '_'), 256);
    return _frameClass.hasMatch(c) ? c : null;
  }

  static String frameFn(String raw) {
    final s = raw.replaceFirst(RegExp(r'^new '), '').trim();
    if (_frameFn.hasMatch(s)) return s;
    final c = _cut(s.replaceAll(RegExp(r'[^A-Za-z0-9_.$<>-]'), '_'), 128);
    return _frameFn.hasMatch(c) ? c : '<anonymous>';
  }
}
