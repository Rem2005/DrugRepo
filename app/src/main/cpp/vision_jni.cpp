#include <jni.h>

#include <cstring>
#include <vector>

#include <opencv2/core.hpp>
#include <opencv2/core/version.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/objdetect/aruco_detector.hpp>
#include <opencv2/objdetect/aruco_dictionary.hpp>

#include "cv_pipeline.h"
#include "vision_jni.h"

namespace {

// Arbitrary non-zero marker id and size. No analytic meaning is attached to either: this
// exercises the aruco module, it is not reference data for any assay.
constexpr int kMarkerId = 7;
constexpr int kMarkerSidePixels = 64;

constexpr const char* kMeasurementClass = "nic/drugrepo/vision/CvMeasurement";
constexpr const char* kColorimetryClass = "nic/drugrepo/vision/CvColorimetryMeasurement";

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

// The whole CV stage in one call: JPEG bytes in, a structured measurement object out. Kotlin
// never sees an OpenCV type, a Mat, or a C++ object - only this flat, primitive constructor
// (see CvMeasurement.kt). The C++ side owns decoding, marker detection, ROI selection and
// colour measurement; nothing here computes a colour in Java.
//
// profileIndex selects which reference data the same measurement is compared against
// (reference_profile.h). Nothing else about the stage changes with it: one pipeline, two profiles.
extern "C" JNIEXPORT jobject JNICALL
Java_nic_drugrepo_vision_VisionNative_nativeProcessImage(JNIEnv* env, jobject /*thiz*/,
                                                          jbyteArray jpeg, jint profileIndex) {
    const jsize length = env->GetArrayLength(jpeg);
    // GetByteArrayRegion into a local vector rather than GetByteArrayElements: no pinned
    // buffer to release on any of the early returns below, and processJpeg copies out of it
    // synchronously anyway.
    std::vector<uint8_t> bytes(static_cast<size_t>(length));
    if (length > 0) {
        env->GetByteArrayRegion(jpeg, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
    }
    // An out-of-range index is not a crash and not a silent fall back to the field profile: a
    // caller that asked for the synthetic card and got a field measurement instead would be told
    // it had demonstrated something it had not. nullptr means "no profile named", which is
    // activeProfile().
    const ReferenceCardProfile* selected = profileAt(profileIndex);
    const CvFrame frame = processJpeg(bytes.data(), bytes.size(), selected);

    jclass measurementClass = env->FindClass(kMeasurementClass);
    if (measurementClass == nullptr) {
        return nullptr;  // pending NoClassDefFoundError; do not mask it with a null return
    }
    jmethodID ctor = env->GetMethodID(measurementClass, "<init>", kMeasurementCtorSignature);
    if (ctor == nullptr) {
        return nullptr;  // pending NoSuchMethodError, same reasoning
    }

    auto toJavaArray = [env](const std::vector<int>& source) -> jintArray {
        jintArray array = env->NewIntArray(static_cast<jsize>(source.size()));
        if (array == nullptr || source.empty()) {
            return array;
        }
        env->SetIntArrayRegion(array, 0, static_cast<jsize>(source.size()), source.data());
        return array;
    };

    const jintArray markerIds = toJavaArray(frame.markerIds);
    const jintArray markerCorners = toJavaArray(frame.markerCorners);
    jintArray regions = env->NewIntArray(8);
    if (regions == nullptr) {
        return nullptr;
    }
    env->SetIntArrayRegion(regions, 0, 8, frame.regions);

    // The colour stage is always reported, including on failure: a record that says
    // "no reference card found" has to say which profile was looking, or the status alone is
    // unreadable months later.
    const CvColorimetryFrame& colorimetry = frame.colorimetry;
    jclass colorimetryClass = env->FindClass(kColorimetryClass);
    if (colorimetryClass == nullptr) {
        return nullptr;
    }
    jmethodID colorimetryCtor = env->GetMethodID(colorimetryClass, "<init>",
                                                  kColorimetryCtorSignature);
    if (colorimetryCtor == nullptr) {
        return nullptr;
    }
    jobject colorimetryObject = env->NewObject(
        colorimetryClass, colorimetryCtor,
        static_cast<jint>(colorimetry.profileId),
        static_cast<jint>(colorimetry.profileValidation),
        static_cast<jint>(colorimetry.profileKind),
        static_cast<jint>(colorimetry.classification),
        static_cast<jint>(colorimetry.swatchCount),
        static_cast<jint>(colorimetry.rectifiedWidth),
        static_cast<jint>(colorimetry.rectifiedHeight),
        static_cast<jint>(colorimetry.anchorMatch),
        static_cast<jboolean>(colorimetry.ccmApplied != 0 ? JNI_TRUE : JNI_FALSE),
        static_cast<jdouble>(colorimetry.deltaE2000),
        static_cast<jdouble>(colorimetry.referenceRawR),
        static_cast<jdouble>(colorimetry.referenceRawG),
        static_cast<jdouble>(colorimetry.referenceRawB),
        static_cast<jdouble>(colorimetry.referenceR),
        static_cast<jdouble>(colorimetry.referenceG),
        static_cast<jdouble>(colorimetry.referenceB),
        static_cast<jdouble>(colorimetry.reactionRawR),
        static_cast<jdouble>(colorimetry.reactionRawG),
        static_cast<jdouble>(colorimetry.reactionRawB),
        static_cast<jdouble>(colorimetry.reactionR),
        static_cast<jdouble>(colorimetry.reactionG),
        static_cast<jdouble>(colorimetry.reactionB),
        static_cast<jdouble>(colorimetry.referenceLabL),
        static_cast<jdouble>(colorimetry.referenceLabA),
        static_cast<jdouble>(colorimetry.referenceLabB),
        static_cast<jdouble>(colorimetry.reactionLabL),
        static_cast<jdouble>(colorimetry.reactionLabA),
        static_cast<jdouble>(colorimetry.reactionLabB),
        static_cast<jdouble>(colorimetry.anchorDeltaE2000),
        env->NewStringUTF(colorimetry.profileVersion.c_str()),
        env->NewStringUTF(colorimetry.profileProvenance.c_str()),
        env->NewStringUTF(colorimetry.reagentType.c_str()),
        env->NewStringUTF(colorimetry.anchorLabel.c_str()));
    if (colorimetryObject == nullptr) {
        return nullptr;
    }

    return env->NewObject(
        measurementClass, ctor,
        colorimetryObject,
        static_cast<jint>(frame.status),
        static_cast<jint>(frame.imageWidth),
        static_cast<jint>(frame.imageHeight),
        static_cast<jint>(frame.geometrySource),
        static_cast<jdouble>(frame.laplacianVariance),
        static_cast<jdouble>(frame.glareFraction),
        static_cast<jdouble>(frame.referenceMeanB),
        static_cast<jdouble>(frame.referenceMeanG),
        static_cast<jdouble>(frame.referenceMeanR),
        static_cast<jdouble>(frame.reactionMeanB),
        static_cast<jdouble>(frame.reactionMeanG),
        static_cast<jdouble>(frame.reactionMeanR),
        markerIds, markerCorners, regions);
}
