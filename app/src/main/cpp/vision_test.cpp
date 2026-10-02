// TODO.md Phase 0 task 4: native test harness for the C++ layer.
//
// Deliberately framework-free: assertions are plain assert(), so the return value alone tells
// the instrumented test (NativeHarnessTest) whether the native layer is healthy. Every
// input here is synthetic and fixed. Nothing in this file is reference data, a threshold, or
// an analytical claim - it checks that the modules the pipeline depends on are linked and
// deterministic.
//
// Not compiled into the shipped library: CMakeLists.txt declares this target only under
// if(VISION_NATIVE_TEST), which the Gradle debug build type alone passes.
//
// This was originally a main() in a standalone executable that the instrumented test staged in
// app storage and execve'd. That cannot work: apps targeting SDK 29+ may not execute files out
// of their own home directory, so execve fails with EACCES no matter the file mode. The
// harness is therefore a shared library and its asserts run inside the already-running app
// process. A failing assert calls abort(), so a regression shows up as an instrumentation
// crash rather than a tidy assertion message - deliberately loud, not silent.

#include <cassert>
#include <cstring>
#include <string>

#include <jni.h>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/objdetect/aruco_detector.hpp>
#include <opencv2/objdetect/aruco_dictionary.hpp>

#include "vision_jni.h"

namespace {

// Fixed synthetic image, no RNG: a diagonal ramp gives the Laplacian something with real
// structure to respond to, so a variance of zero would mean the operator did not run.
cv::Mat syntheticGradient(int size) {
    cv::Mat image(size, size, CV_8UC1);
    for (int y = 0; y < size; ++y) {
        for (int x = 0; x < size; ++x) {
            image.at<uchar>(y, x) = static_cast<uchar>((x + y) % 256);
        }
    }
    return image;
}

void testOpenCvIsThePinnedRelease() {
    const std::string linked = openCvVersion();
    assert(linked == kExpectedOpenCvVersion);
}

void testCoreAndImgprocAreUsable() {
    const cv::Mat image = syntheticGradient(64);
    assert(!image.empty());

    cv::Mat laplacian;
    cv::Laplacian(image, laplacian, CV_64F, 1);
    cv::Scalar mean;
    cv::Scalar stddev;
    cv::meanStdDev(laplacian, mean, stddev);
    assert(stddev[0] > 0.0);

    // Fixed bounds on a fixed image: the selected pixel count is exact, which catches an
    // unexpected change in the inRange kernel rather than merely "it ran".
    const cv::Mat flat(4, 4, CV_8UC3, cv::Scalar(10, 20, 30));
    cv::Mat selected;
    cv::inRange(flat, cv::Scalar(10, 20, 30), cv::Scalar(10, 20, 30), selected);
    assert(selected.type() == CV_8UC1);
    assert(cv::countNonZero(selected) == 16);
}

void testArucoModuleIsUsable() {
    const cv::aruco::Dictionary dictionary =
        cv::aruco::getPredefinedDictionary(cv::aruco::DICT_4X4_50);
    assert(!dictionary.bytesList.empty());

    cv::Mat marker;
    cv::aruco::generateImageMarker(dictionary, 7, 64, marker);
    assert(!marker.empty());
    assert(marker.cols == 64 && marker.rows == 64);

    // AGENTS.md directive 6: the same input must always produce the same output bytes.
    cv::Mat markerAgain;
    cv::aruco::generateImageMarker(dictionary, 7, 64, markerAgain);
    assert(marker.type() == markerAgain.type());
    assert(cv::countNonZero(marker != markerAgain) == 0);

    cv::aruco::ArucoDetector detector(dictionary);
    assert(!detector.getDictionary().bytesList.empty());

    // Same code path the JNI layer calls, so both gates cover one implementation.
    assert(arucoSelfTest() == 64);
}

void testAbiContract() {
    assert(kVisionAbiVersion == 1);
}

}  // namespace

// Returns 0 when every assert above held. Exported so the instrumented test can reach it
// without a JNI wrapper; JNI symbol names are prefixed, so this never collides with them.
extern "C" int vision_native_test_run() {
    testOpenCvIsThePinnedRelease();
    testCoreAndImgprocAreUsable();
    testArucoModuleIsUsable();
    testAbiContract();
    return 0;
}

// The only JNI symbol the harness needs: NativeHarnessTest.runNativeHarness().
// Static (companion @JvmStatic), so the second argument is the class, not an instance.
extern "C" JNIEXPORT jint JNICALL
Java_nic_drugrepo_NativeHarnessTest_runNativeHarness(JNIEnv*, jclass) {
    return vision_native_test_run();
}
