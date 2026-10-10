import 'dart:convert';
import 'dart:typed_data';

/// Reads string values for [wanted] keys from the top-level dictionary of an Apple property list,
/// XML or binary (`bplist00`, the format inside shipped iOS apps). Returns what it finds; never throws.
Map<String, String> plistStrings(Uint8List bytes, Set<String> wanted) {
  try {
    if (bytes.length > 40 && ascii.decode(bytes.sublist(0, 8), allowInvalid: true) == 'bplist00') {
      return _binary(bytes, wanted);
    }
    final text = utf8.decode(bytes, allowMalformed: true);
    return {
      for (final key in wanted)
        if (RegExp('<key>${RegExp.escape(key)}</key>\\s*<string>([^<]*)</string>').firstMatch(text) case final m?)
          key: m.group(1)!,
    };
  } catch (_) {
    return const {};
  }
}

int _uint(Uint8List b, int offset, int size) {
  var v = 0;
  for (var i = 0; i < size; i++) {
    v = v * 256 + b[offset + i];
  }
  return v;
}

Map<String, String> _binary(Uint8List b, Set<String> wanted) {
  final t = b.length - 32;
  final offsetSize = b[t + 6];
  final refSize = b[t + 7];
  final numObjects = _uint(b, t + 8, 8);
  final top = _uint(b, t + 16, 8);
  final table = _uint(b, t + 24, 8);
  if (top >= numObjects) return const {};

  int offsetOf(int obj) => _uint(b, table + obj * offsetSize, offsetSize);

  (int, int) lengthAt(int off) {
    final low = b[off] & 0x0f;
    if (low != 0x0f) return (low, off + 1);
    final n = 1 << (b[off + 1] & 0x0f);
    return (_uint(b, off + 2, n), off + 2 + n);
  }

  String? string(int obj) {
    final off = offsetOf(obj);
    final type = b[off] >> 4;
    final (len, start) = lengthAt(off);
    if (type == 0x5) return latin1.decode(b.sublist(start, start + len));
    if (type == 0x6) {
      final units = [for (var i = 0; i < len; i++) (b[start + 2 * i] << 8) | b[start + 2 * i + 1]];
      return String.fromCharCodes(units);
    }
    return null;
  }

  final dictOff = offsetOf(top);
  if (b[dictOff] >> 4 != 0xd) return const {};
  final (count, start) = lengthAt(dictOff);
  final out = <String, String>{};
  for (var i = 0; i < count; i++) {
    final key = string(_uint(b, start + i * refSize, refSize));
    if (key == null || !wanted.contains(key)) continue;
    final value = string(_uint(b, start + (count + i) * refSize, refSize));
    if (value != null) out[key] = value;
  }
  return out;
}
