#ifndef DRUGREPO_VISION_JNI_H
#define DRUGREPO_VISION_JNI_H

// Cross-language ABI contract. VisionNative.VISION_ABI_VERSION in Kotlin must equal
// kVisionAbiVersion here; NativeLibLoadTest fails if either side drifts.
//
// 2: added the processImage entry point, which fills in the CvFrame fields that did not exist
// in ABI 1 (image geometry, measured colour means, ROI rectangles).
// 3: homography rectification against a reference card profile, and the colour stage: corrected
// reference and reaction RGB, CIE L*a*b*, CIEDE2000, the presumptive classification, and the
// profile version plus validation status that make those numbers interpretable. Carried as a
// nested CvColorimetryMeasurement so the flat part of CvMeasurement did not have to grow to
// twenty-five arguments.
constexpr int kVisionAbiVersion = 3;

// TODO.md Phase 0 task 2/4. The pinned OpenCV release this native library is built against.
// Asserted both on-device and by the native harness, so a drifted or mis-fetched SDK cannot
// pass a gate silently.
constexpr const char* kExpectedOpenCvVersion = "4.14.0";

// JNI descriptors of the nic.drugrepo.vision.CvMeasurement and
// nic.drugrepo.vision.CvColorimetryMeasurement primary constructors. They live here rather than
// in vision_jni.cpp because vision_test.cpp asserts they resolve inside the app process: a wrong
// int/double count in these strings is invisible to the C++ compiler and would otherwise surface
// as NoSuchMethodError on the first capture, on a device, mid-sprint. Keep them in step with the
// Kotlin constructors.
//
// CvMeasurement: (colorimetry, status, imageWidth, imageHeight, geometrySource, laplacianVariance,
// glareFraction, referenceMeanB/G/R, reactionMeanB/G/R, markerIds, markerCorners, regions)
constexpr const char* kMeasurementCtorSignature =
    "(Lnic/drugrepo/vision/CvColorimetryMeasurement;IIIIDDDDDDDD[I[I[I)V";

// CvColorimetryMeasurement: six ints (profileId, profileValidation, classification, swatchCount,
// rectifiedWidth, rectifiedHeight), one boolean (ccmApplied), nineteen doubles (deltaE2000,
// referenceRaw R/G/B, reference R/G/B, reactionRaw R/G/B, reaction R/G/B, referenceLab L/A/B,
// reactionLab L/A/B) and three strings (profileVersion, profileProvenance, reagentType).
constexpr const char* kColorimetryCtorSignature =
    "(IIIIIIZDDDDDDDDDDDDDDDDDDDLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V";

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
