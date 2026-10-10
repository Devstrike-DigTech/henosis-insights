import 'dart:convert';

import 'package:insights_dart/src/names.dart';
import 'package:insights_dart/src/plist.dart';
import 'package:insights_dart/src/sha256.dart';
import 'package:insights_dart/src/stack.dart';
import 'package:test/test.dart';

// `plutil -convert binary1` of an Info.plist with CFBundleIdentifier com.example.verify,
// CFBundleShortVersionString 3.0.1 and a UTF-16 CFBundleDisplayName.
const _binaryPlist =
    'YnBsaXN0MDDWAQIDBAUGBwgJCgsMXxAaQ0ZCdW5kbGVTaG9ydFZlcnNpb25TdHJpbmdfEBNDRkJ1bmRsZURpc3BsYXlOYW1lXxAPQ0ZCdW5kbGVWZXJzaW9uXxASQ0ZCdW5kbGVJZGVudGlmaWVyXxASTFNSZXF1aXJlc0lQaG9uZU9TXlVJRGV2aWNlRmFtaWx5VTMuMC4xaABWAOkAcgBpAGYAeQAgJxNSNDJfEBJjb20uZXhhbXBsZS52ZXJpZnkJog0OEAEQAggVMkhab4STmaqtwsPGyAAAAAAAAAEBAAAAAAAAAA8AAAAAAAAAAAAAAAAAAADK';

void main() {
  group('sha256', () {
    test('matches the FIPS 180-4 vectors and multi-block input', () {
      expect(sha256Hex(''), 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');
      expect(sha256Hex('abc'), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
      expect(sha256Hex('abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq'),
          '248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1');
      expect(sha256Hex('a' * 1000), '41edece42d63e8d9bf515a9ba6932e1c20cbc9f5a5d134645adb5db1b9737ea3');
    });
  });

  group('names', () {
    final method = RegExp(r'^[A-Za-z_$][A-Za-z0-9_.$-]{0,127}$');
    final code = RegExp(r'^[A-Z][A-Z0-9_]{0,63}$');
    final check = RegExp(r'^[a-z][a-z0-9_]{0,63}$');
    test('normalises anything into the schema patterns', () {
      for (final raw in ['', ' ', '9lives', 'upload passport AB1', 'ünïcödé', 'a' * 500, '-x']) {
        expect(Names.method(raw), matches(method));
        expect(Names.code(raw), matches(code));
        expect(Names.checkName(raw), matches(check));
      }
    });
    test('keeps valid identifiers and app ids', () {
      expect(Names.method('AcmeKyc.upload'), 'AcmeKyc.upload');
      expect(Names.appId('com.example.verify'), 'com.example.verify');
      expect(Names.appId('verify.example.org'), 'verify.example.org');
      expect(Names.appId('has space'), isNull);
    });
  });

  group('stack frames', () {
    test('parses web debug (DDC) frames', () {
      final frames = sdkFrames(
        StackTrace.fromString(
            'packages/acme_kyc/src/kyc.dart 42:7       upload\npackages/verify_app/main.dart 3:1   main'),
        ['package:acme_kyc/'],
      );
      expect(frames, [(cls: 'acme_kyc/src/kyc.dart', fn: 'upload', line: 42)]);
    });
    test('no prefixes or no trace means no frames', () {
      expect(sdkFrames(StackTrace.current, const []), isEmpty);
      expect(sdkFrames(null, ['package:acme_kyc/']), isEmpty);
    });
  });

  group('plist', () {
    const wanted = {'CFBundleIdentifier', 'CFBundleShortVersionString', 'CFBundleDisplayName'};
    test('reads a binary plist as shipped inside iOS apps', () {
      expect(plistStrings(base64.decode(_binaryPlist), wanted), {
        'CFBundleIdentifier': 'com.example.verify',
        'CFBundleShortVersionString': '3.0.1',
        'CFBundleDisplayName': 'Vérify ✓',
      });
    });
    test('reads an XML plist', () {
      const xml = '<plist><dict><key>CFBundleIdentifier</key>\n<string>com.example.mac</string></dict></plist>';
      expect(plistStrings(utf8.encode(xml), wanted), {'CFBundleIdentifier': 'com.example.mac'});
    });
    test('garbage never throws', () {
      expect(plistStrings(utf8.encode('bplist00${'x' * 50}'), wanted), isEmpty);
      expect(plistStrings(utf8.encode(''), wanted), isEmpty);
    });
  });
}
