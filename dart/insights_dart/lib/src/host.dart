// Platform-specific pieces, picked at compile time: dart:io on mobile/desktop/server, fetch and
// browser globals on the web.
export 'host_stub.dart' if (dart.library.io) 'host_io.dart' if (dart.library.js_interop) 'host_web.dart';
