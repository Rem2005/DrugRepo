#include <jni.h>

#include <cstring>

#include <opencv2/core.hpp>
#include <opencv2/core/version.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/objdetect/aruco_detector.hpp>
#include <opencv2/objdetect/aruco_dictionary.hpp>

#include "vision_jni.h"

namespace {

// Arbitrary non-zero marker id and size. No analytic meaning is attached to either: this
// exercises the aruco module, it is not reference data for any assay.
constexpr int kMarkerId = 7;
constexpr int kMarkerSidePixels = 64;

}  // namespace

// TODO.md Phase 0 task 2. Reports the version of the OpenCV that was statically linked
// into this library, so a gate can compare it against kExpectedOpenCvVersion instead of
// trusting that the right SDK was fetched.
const char* openCvVersion() {
    return CV_VERSION;
}

// TODO.md Phase 0 task 2. Integration self-test for the aruco module: build the dictionary,
// render one marker and construct a detector. It is a link-and-run check, not pipeline
// logic; marker detection quality and its thresholds belong to TODO Phase 3.
//
// Returns the marker side in pixels on success, negative on failure.
int arucoSelfTest() {
    try {
        const cv::aruco::Dictionary dictionary =
            cv::aruco::getPredefinedDictionary(cv::aruco::DICT_4X4_50);
        if (dictionary.bytesList.empty()) {
            return -1;
        }

        cv::Mat marker;
        cv::aruco::generateImageMarker(dictionary, kMarkerId, kMarkerSidePixels, marker);
        if (marker.empty() || marker.cols != kMarkerSidePixels || marker.rows != kMarkerSidePixels) {
            return -2;
        }

        // Constructs the detector the Phase 3 pipeline will use, exercising the aruco
        // runtime rather than only the marker renderer.
        cv::aruco::ArucoDetector detector(dictionary);
        if (detector.getDictionary().bytesList.empty()) {
            return -3;
        }

        return marker.cols;
    } catch (const cv::Exception&) {
        return -100;
    } catch (...) {
        return -101;
    }
}

// The exported names below are bound to fully qualified Kotlin declarations
// (nic.drugrepo.vision.VisionNative.*). Renaming the Kotlin class, the method or the package
// silently breaks the binding, which is why NativeLibLoadTest exists.
//
// The second parameter is jobject, not jclass: the Kotlin declarations are members of an
// `object`, so the native methods are instance methods. A static native method (for example
// from `@JvmStatic` in a companion) would instead be named Java_..._nativeVersion__ with two
// trailing underscores, and would need jclass here.
//
// JNIEXPORT is visibility("default"), which makes these symbols local to libdrugvision.so:
// another module cannot link against them. That is why vision_test.cpp compiles the sources
// directly instead of linking this library.
extern "C" JNIEXPORT jint JNICALL
Java_nic_drugrepo_vision_VisionNative_nativeVersion(JNIEnv* /*env*/, jobject /*thiz*/) {
    return kVisionAbiVersion;
}

extern "C" JNIEXPORT jstring JNICALL
Java_nic_drugrepo_vision_VisionNative_nativeOpenCvVersion(JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF(openCvVersion());
}

extern "C" JNIEXPORT jint JNICALL
Java_nic_drugrepo_vision_VisionNative_nativeArucoSelfTest(JNIEnv* /*env*/, jobject /*thiz*/) {
    return arucoSelfTest();
}
