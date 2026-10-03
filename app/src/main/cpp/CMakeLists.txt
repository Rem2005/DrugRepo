cmake_minimum_required(VERSION 3.22.1)

# The 3.22.1 floor is deliberate. CMake 4.x removed compatibility with
# cmake_minimum_required(VERSION < 3.5); the SDK ships CMake 4.1.2, so declaring a
# realistic minimum keeps this file valid under both 3.x and 4.x.

project(drugvision LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# OpenCV is linked statically into libdrugvision.so. No OpenCV type ever crosses the JNI
# boundary (architecture.md section 3), and the OpenCV Java bindings are deliberately unused.
#
# The SDK is vendored under third_party/ and gitignored; fetch it with
# scripts/fetch-opencv.ps1, which pins the upstream checksum.
#
# OpenCV_DIR points at the per-ABI config rather than the sdk/native/jni root: the root
# config is a dispatcher that picks a subdirectory using the legacy ANDROID_NDK_ABI_NAME
# variable, which modern NDK toolchain files are not obliged to set.
set(OPENCV_SDK_ROOT
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/opencv/OpenCV-android-sdk/sdk/native")
set(OpenCV_DIR "${OPENCV_SDK_ROOT}/jni/abi-${ANDROID_ABI}")

# objdetect carries the aruco module (architecture.md section 4.3) and the ArucoDetector
# API required for OpenCV 4.7+. imgproc and core are the rest of the Phase 3/4 pipeline.
# imgcodecs is the JPEG decode/encode half of the captured frame (cv::imdecode in
# cv_pipeline.cpp); it is a separate OpenCV module, hence a separate component here.
# The OpenCV config declares the full transitive closure of third-party static libs
# (tbb, protobuf, libjpeg-turbo, ...) via INTERFACE_LINK_LIBRARIES, so linking these three
# targets is sufficient.
find_package(OpenCV REQUIRED COMPONENTS core imgproc imgcodecs objdetect)

# ponytail: libdrugvision.so is ~10 MB and the APK ~51 MB, because objdetect's imported
# target drags in dnn -> protobuf/tbb and the whole 3rdparty closure even though Phase 3/4
# only touch core/imgproc/objdetect. If APK size becomes a real constraint, build OpenCV
# from source with -DBUILD_LIST=core,imgproc,objdetect instead of linking the prebuilt .a.

add_library(drugvision SHARED
    vision_jni.cpp
    cv_pipeline.cpp
    colorimetry.cpp
    reference_profile.cpp
)

target_link_libraries(drugvision
    PRIVATE
        ${OpenCV_LIBS}
)

# TODO.md Phase 0 task 4: native test harness. Opt-in via -DVISION_NATIVE_TEST=ON, which only
# the Gradle debug build type passes, so it cannot reach a release artifact.
#
# Do NOT gate this on CMAKE_BUILD_TYPE: AGP 9.4 maps the release variant to
# CMAKE_BUILD_TYPE=RelWithDebInfo, not "Release", so a `STREQUAL "Debug"` test is true for
# neither and silently shipped ~10 MB of test code to release builds.
#
# It is a SHARED library, not an executable. Android forbids apps targeting SDK 29+ from
# executing files out of their own home directory, so a staged-executable harness always dies
# with EACCES from execve. Loading a library needs no such permission, and running the asserts
# in-process is what we want anyway.
#
# It compiles the sources directly rather than linking libdrugvision.so because the JNI
# exports are declared JNIEXPORT = visibility("default"), which makes them symbol-local and
# unlinkable from another module. Including vision_jni.cpp here also means the harness asserts
# the same arucoSelfTest() implementation the JNI layer exposes, not a copy of it.
if(VISION_NATIVE_TEST)
    add_library(vision_native_test SHARED
        vision_test.cpp
        vision_jni.cpp
        cv_pipeline.cpp
        colorimetry.cpp
        reference_profile.cpp
    )

    # Same mixed-case spelling as drugvision above, and required to match: the SDK config
    # defines OpenCV_LIBS and no all-caps variant, so ${OPENCV_LIBS} expands to nothing and
    # the target silently loses OpenCV's include dirs and static libs.
    target_link_libraries(vision_native_test
        PRIVATE
            ${OpenCV_LIBS}
    )

    # AGP collects every lib*.so in the CMake output directory into the APK, so this harness
    # statically links OpenCV and is ~10 MB. It stays out of any release artifact because the
    # target only exists in debug builds in the first place.
    add_custom_target(vision_native_test_built ALL DEPENDS vision_native_test)
endif()
