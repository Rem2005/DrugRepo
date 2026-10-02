#ifndef DRUGREPO_VISION_JNI_H
#define DRUGREPO_VISION_JNI_H

// Cross-language ABI contract. VisionNative.VISION_ABI_VERSION in Kotlin must equal
// kVisionAbiVersion here; NativeLibLoadTest fails if either side drifts.
//
// Still 1: linking OpenCV and adding exported functions changed neither the C++ result
// struct nor the calling convention, which is what this constant tracks.
constexpr int kVisionAbiVersion = 1;

// TODO.md Phase 0 task 2/4. The pinned OpenCV release this native library is built against.
// Asserted both on-device and by the native harness, so a drifted or mis-fetched SDK cannot
// pass a gate silently.
constexpr const char* kExpectedOpenCvVersion = "4.14.0";

// Plain C++ entry points, deliberately free of any JNI type so they can be asserted by the
// native harness (vision_test.cpp) as well as through the JNI layer. The JNI wrappers in
// vision_jni.cpp are thin shims over these, so there is exactly one implementation to test.

// Returns CV_VERSION of the statically linked OpenCV.
const char* openCvVersion();

// Builds an ArUco dictionary, renders a marker and constructs an ArucoDetector, proving the
// objdetect/aruco module is linked and executable on this device. Returns the generated
// marker side in pixels, or a negative value on failure.
int arucoSelfTest();

#endif  // DRUGREPO_VISION_JNI_H
