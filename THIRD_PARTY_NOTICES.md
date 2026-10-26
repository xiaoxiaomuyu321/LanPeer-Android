# Third-party components

The project's MIT license covers the code written for LanPeer. The APK also
contains dependencies with their own licenses:

| Component | Version | License and upstream notice |
| --- | --- | --- |
| [WebRTC Android SDK](https://github.com/webrtc-sdk/android) | 137.7151.05 | [MIT wrapper](https://github.com/webrtc-sdk/android/blob/main/LICENSE), [WebRTC BSD-3-Clause and additional notices](https://github.com/webrtc-sdk/webrtc/blob/main/README.md) |
| [Java-WebSocket](https://github.com/TooTallNate/Java-WebSocket) | 1.5.7 | [MIT](https://github.com/TooTallNate/Java-WebSocket/blob/master/LICENSE) |
| [SLF4J](https://github.com/qos-ch/slf4j) | 2.0.16 | [MIT](https://github.com/qos-ch/slf4j/blob/master/LICENSE.txt) |
| [ZXing Core](https://github.com/zxing/zxing) | 3.5.3 | [Apache-2.0](https://github.com/zxing/zxing/blob/master/LICENSE) |

The dependency versions are pinned in `app/build.gradle`. This notice does not
relicense third-party code. Check each upstream license and its notices before
redistributing a modified APK.
