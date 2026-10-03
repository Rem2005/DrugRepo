#include "cv_pipeline.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <map>
#include <numeric>
#include <vector>

#include <opencv2/core.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/objdetect/aruco_detector.hpp>
#include <opencv2/objdetect/aruco_dictionary.hpp>

#include "colorimetry.h"

namespace {

// Scale a normalized profile rectangle onto the rectified card. Returns false when the rectangle
// falls outside the card or is too small to be a measurement, which is a profile failure rather
// than something to silently shrink.
bool toCardRect(const NormalizedRect& normalized, int width, int height, cv::Rect& out) {
    const double x = normalized.x * width;
    const double y = normalized.y * height;
    const double w = normalized.width * width;
    const double h = normalized.height * height;
    const int x0 = static_cast<int>(std::lround(x));
    const int y0 = static_cast<int>(std::lround(y));
    const int x1 = static_cast<int>(std::lround(x + w));
    const int y1 = static_cast<int>(std::lround(y + h));
    if (x0 < 0 || y0 < 0 || x1 > width || y1 > height) {
        return false;
    }
    if (x1 - x0 < kMinimumRoiPixels || y1 - y0 < kMinimumRoiPixels) {
        return false;
    }
    out = cv::Rect(x0, y0, x1 - x0, y1 - y0);
    return true;
}

// Mean of a rect's channels as R,G,B in 8-bit scale. cv::mean() returns BGR, so the order is
// reversed here once instead of at every use site.
cv::Vec3d meanRgb(const cv::Mat& card, const cv::Rect& rect) {
    const cv::Scalar bgr = cv::mean(card(rect));
    return cv::Vec3d(bgr[2], bgr[1], bgr[0]);
}

Lab toLab(const cv::Vec3d& rgb8) {
    return srgbToLab(rgb8[0] / 255.0, rgb8[1] / 255.0, rgb8[2] / 255.0);
}

LinearRgb toLinear(const cv::Vec3d& rgb8) {
    LinearRgb linear;
    linear.r = srgbToLinear(rgb8[0] / 255.0);
    linear.g = srgbToLinear(rgb8[1] / 255.0);
    linear.b = srgbToLinear(rgb8[2] / 255.0);
    return linear;
}

CvClassification classify(const ReferenceCardProfile& profile, double deltaE) {
    // A field profile may only produce POSITIVE or NEGATIVE from validated numerical calibration
    // AND validated decision boundaries. Official qualitative colour names ("pink to mauve") are
    // protocol data, not calibration data, and no image can become a finding from them.
    if (profile.kind == ProfileKind::kField &&
        (profile.validation != ProfileValidation::kValidated ||
         profile.classification.unvalidated ||
         !profile.classification.hasValidatedNumericalCalibration)) {
        return CvClassification::kInconclusive;
    }
    // A synthetic demonstration profile is allowed to classify against its demonstration
    // boundaries - showing the comparison working is the entire point of it - and the caller is
    // required to report the outcome as a demonstration. Boundary table; between the two bounds
    // the result is INCONCLUSIVE.
    if (deltaE <= profile.classification.positiveAtOrBelow) {
        return CvClassification::kPositive;
    }
    if (deltaE >= profile.classification.negativeAtOrAbove) {
        return CvClassification::kNegative;
    }
    return CvClassification::kInconclusive;
}

}  // namespace

const CvConfig& cvConfig() {
    static const CvConfig config;
    return config;
}

CvFrame processJpeg(const uint8_t* data, size_t length,
                    const ReferenceCardProfile* profileOverride) {
    CvFrame frame;
    const ReferenceCardProfile& profile = profileOverride != nullptr ? *profileOverride : activeProfile();
    frame.colorimetry.profileId = profileOverride != nullptr ? -1 : activeProfileIndex();
    frame.colorimetry.profileValidation = static_cast<int>(profile.validation);
    frame.colorimetry.profileKind = static_cast<int>(profile.kind);
    frame.colorimetry.classification = 0;  // replaced by classify() once the dE00 is known
    frame.colorimetry.rectifiedWidth = profile.rectifiedWidth;
    frame.colorimetry.rectifiedHeight = profile.rectifiedHeight;
    frame.colorimetry.profileVersion = profile.version;
    frame.colorimetry.profileProvenance = profile.provenance;
    frame.colorimetry.reagentType = profile.reagentType;

    if (data == nullptr || length == 0) {
        frame.status = kCvStatusEmptyInput;
        return frame;
    }

    try {
        // imdecode needs a contiguous buffer it does not modify.
        const std::vector<uint8_t> encoded(data, data + length);
        const cv::Mat image = cv::imdecode(encoded, cv::IMREAD_COLOR);
        if (image.empty()) {
            frame.status = kCvStatusDecodeFailed;
            return frame;
        }
        if (image.cols <= 0 || image.rows <= 0 || image.channels() != 3) {
            frame.status = kCvStatusInvalidGeometry;
            return frame;
        }
        frame.imageWidth = image.cols;
        frame.imageHeight = image.rows;

        // Quality metrics are measured here and gated in a later phase (architecture.md
        // section 4.2), so this stage records the numbers without inventing a pass/fail.
        cv::Mat gray;
        cv::cvtColor(image, gray, cv::COLOR_BGR2GRAY);
        cv::Mat laplacian;
        cv::Laplacian(gray, laplacian, CV_64F, 3);
        cv::Scalar laplacianMean;
        cv::Scalar laplacianStdDev;
        cv::meanStdDev(laplacian, laplacianMean, laplacianStdDev);
        frame.laplacianVariance = laplacianStdDev[0] * laplacianStdDev[0];

        cv::Mat blown;
        cv::threshold(gray, blown, cvConfig().glareBrightLevel, 255.0, cv::THRESH_BINARY);
        frame.glareFraction = static_cast<double>(cv::countNonZero(blown)) / gray.total();

        // STEP 1: detect the profile's markers (architecture.md section 4.3).
        cv::aruco::DetectorParameters parameters;
        parameters.cornerRefinementMethod = cv::aruco::CORNER_REFINE_SUBPIX;
        const cv::aruco::Dictionary dictionary =
            cv::aruco::getPredefinedDictionary(profile.arucoDictionary);
        const cv::aruco::ArucoDetector detector(dictionary, parameters);

        std::vector<int> ids;
        std::vector<std::vector<cv::Point2f>> corners;
        detector.detectMarkers(image, corners, ids);

        for (size_t i = 0; i < ids.size(); ++i) {
            frame.markerIds.push_back(ids[i]);
            for (const cv::Point2f& corner : corners[i]) {
                frame.markerCorners.push_back(static_cast<int>(std::lround(corner.x)));
                frame.markerCorners.push_back(static_cast<int>(std::lround(corner.y)));
            }
        }

        if (ids.empty()) {
            // No centre fallback on this path. An explicit "there is no card in this frame" is
            // what the record needs; a plausible-looking centre crop is how a wrong result gets
            // sealed.
            frame.status = kCvStatusNoMarkers;
            return frame;
        }

        // Match detections against the profile's four ids, keeping the profile's card order.
        std::map<int, const std::vector<cv::Point2f>*> detected;
        for (size_t i = 0; i < ids.size(); ++i) {
            detected.emplace(ids[i], &corners[i]);
        }
        std::array<const std::vector<cv::Point2f>*, 4> matched{};
        int matchedCount = 0;
        for (size_t i = 0; i < 4; ++i) {
            const auto it = detected.find(profile.markerIds[i]);
            if (it != detected.end()) {
                matched[i] = it->second;
                ++matchedCount;
            }
        }

        if (matchedCount == 0) {
            // Markers of this dictionary were found, but not this profile's. Almost always the
            // wrong card, or a card laid out for a different profile version.
            frame.status = kCvStatusUnexpectedMarkerIds;
            return frame;
        }
        if (matchedCount < 4) {
            frame.status = kCvStatusIncompleteMarkerSet;
            return frame;
        }
        // All four present, but foreign markers as well. Silently measuring anyway would risk
        // reporting the wrong card: an overlapping second card can displace detections for the id
        // we matched, and the homography below has no way to notice.
        if (detected.size() > matchedCount) {
            frame.status = kCvStatusUnexpectedMarkerIds;
            return frame;
        }

        // STEP 2: homography from the matched marker centres to the rectified card
        // (architecture.md section 4.3: getPerspectiveTransform + warpPerspective).
        cv::Point2f source[4];
        cv::Point2f destination[4];
        for (int i = 0; i < 4; ++i) {
            const std::vector<cv::Point2f>& marker = *matched[static_cast<size_t>(i)];
            cv::Point2f centre(0.0f, 0.0f);
            for (const cv::Point2f& corner : marker) {
                centre += corner;
            }
            centre *= 0.25f;
            source[i] = centre;
            destination[i] = cv::Point2f(
                static_cast<float>(profile.markerCentres[i].x * profile.rectifiedWidth),
                static_cast<float>(profile.markerCentres[i].y * profile.rectifiedHeight));
        }

        const cv::Mat homography = cv::getPerspectiveTransform(source, destination);
        if (homography.empty() || homography.total() != 9) {
            frame.status = kCvStatusInvalidHomography;
            return frame;
        }
        // A degenerate arrangement (collinear or coincident centres) yields a singular matrix; the
        // inverse check below is the cheapest honest test.
        cv::Mat inverted;
        if (!cv::invert(homography, inverted) || inverted.empty()) {
            frame.status = kCvStatusInvalidHomography;
            return frame;
        }

        const cv::Size cardSize(profile.rectifiedWidth, profile.rectifiedHeight);
        cv::Mat card;
        cv::warpPerspective(image, card, homography, cardSize, cv::INTER_LINEAR, cv::BORDER_CONSTANT,
                            cv::Scalar(255, 255, 255));
        if (card.empty() || card.cols != cardSize.width || card.rows != cardSize.height) {
            frame.status = kCvStatusInvalidHomography;
            return frame;
        }
        frame.geometrySource = kCvGeometryHomographyRectified;

        // STEP 3: reference patches and the reaction window, in rectified card coordinates
        // (sprint STEP 3).
        std::vector<cv::Rect> patchRects;
        std::vector<cv::Vec3d> patchMeans;
        for (int i = 0; i < profile.patchCount; ++i) {
            cv::Rect rect;
            if (!toCardRect(profile.patches[i].rect, cardSize.width, cardSize.height, rect)) {
                frame.status = kCvStatusRoiOutsideImage;
                return frame;
            }
            patchRects.push_back(rect);
            patchMeans.push_back(meanRgb(card, rect));
        }
        if (patchMeans.empty()) {
            frame.status = kCvStatusRoiOutsideImage;
            return frame;
        }

        cv::Rect reactionRect;
        if (!toCardRect(profile.reactionRoi, cardSize.width, cardSize.height, reactionRect)) {
            frame.status = kCvStatusRoiOutsideImage;
            return frame;
        }

        // The reference region recorded in the result is the union of the measured swatches, so
        // the number shown next to "reference" is the area the reference colour came from.
        cv::Rect referenceRect = patchRects.front();
        for (const cv::Rect& rect : patchRects) {
            referenceRect |= rect;
        }
        frame.regions[0] = referenceRect.x;
        frame.regions[1] = referenceRect.y;
        frame.regions[2] = referenceRect.width;
        frame.regions[3] = referenceRect.height;
        frame.regions[4] = reactionRect.x;
        frame.regions[5] = reactionRect.y;
        frame.regions[6] = reactionRect.width;
        frame.regions[7] = reactionRect.height;

        // Average of the swatch means. Equal weight per swatch, not per pixel, so a swatch twice
        // the size of another cannot dominate the reference colour.
        cv::Vec3d referenceRaw(0.0, 0.0, 0.0);
        for (const cv::Vec3d& mean : patchMeans) {
            referenceRaw += mean;
        }
        referenceRaw /= static_cast<double>(patchMeans.size());
        const cv::Vec3d reactionRaw = meanRgb(card, reactionRect);

        frame.referenceMeanR = referenceRaw[0];
        frame.referenceMeanG = referenceRaw[1];
        frame.referenceMeanB = referenceRaw[2];
        frame.reactionMeanR = reactionRaw[0];
        frame.reactionMeanG = reactionRaw[1];
        frame.reactionMeanB = reactionRaw[2];

        // STEP 4: calibration. Applied in linear light, because a matrix fitted in gamma space is
        // not a colour correction (architecture.md section 4.4).
        //
        // The corrected values are carried as linear light, not as 8-bit sRGB. A CCM multiplies
        // linear tristimulus, so its output is linear whether or not you like it; re-encoding it
        // here and companding it again in toLab() would apply the transfer function twice and
        // produce a plausible, meaningless Lab.
        LinearRgb referenceLinear = toLinear(referenceRaw);
        LinearRgb reactionLinear = toLinear(reactionRaw);
        if (profile.calibrationMode == CalibrationMode::kColorCorrectionMatrix) {
            std::vector<LinearRgb> observed;
            std::vector<LinearRgb> ideal;
            for (int i = 0; i < profile.patchCount; ++i) {
                if (!profile.patches[i].hasAuthoritativeColour) {
                    continue;
                }
                observed.push_back(toLinear(patchMeans[static_cast<size_t>(i)]));
                LinearRgb target;
                target.r = srgbToLinear(profile.patches[i].idealR);
                target.g = srgbToLinear(profile.patches[i].idealG);
                target.b = srgbToLinear(profile.patches[i].idealB);
                ideal.push_back(target);
            }
            double matrix[3][3] = {{0.0}};
            if (solveColorCorrectionMatrix(observed, ideal, matrix)) {
                const auto apply = [&matrix](const LinearRgb& in) {
                    return LinearRgb{matrix[0][0] * in.r + matrix[0][1] * in.g + matrix[0][2] * in.b,
                                    matrix[1][0] * in.r + matrix[1][1] * in.g + matrix[1][2] * in.b,
                                    matrix[2][0] * in.r + matrix[2][1] * in.g + matrix[2][2] * in.b};
                };
                referenceLinear = apply(referenceLinear);
                reactionLinear = apply(reactionLinear);
                frame.colorimetry.ccmApplied = 1;
            }
            // A CCM that cannot be fitted is reported by ccmApplied staying 0 while the pipeline
            // continues uncorrected; the alternative, aborting, would throw away real measurements
            // over a calibration that is already flagged unvalidated.
        }

        // STEP 5 and 6: linear light -> Lab -> CIEDE2000 against the profile's comparison target.
        const Lab referenceLab = xyzToLab(linearRgbToXyz(referenceLinear));
        const Lab reactionLab = xyzToLab(linearRgbToXyz(reactionLinear));
        const double deltaE = ciede2000(referenceLab, reactionLab);

        // Reported values go back on an 8-bit sRGB scale, because that is what the fields promise
        // and what an operator reads. Re-encoding here, once, keeps that promise honest.
        const cv::Vec3d referenceCorrected(
            255.0 * linearToSrgb(referenceLinear.r), 255.0 * linearToSrgb(referenceLinear.g),
            255.0 * linearToSrgb(referenceLinear.b));
        const cv::Vec3d reactionCorrected(255.0 * linearToSrgb(reactionLinear.r),
                                          255.0 * linearToSrgb(reactionLinear.g),
                                          255.0 * linearToSrgb(reactionLinear.b));

        frame.colorimetry.deltaE2000 = deltaE;
        frame.colorimetry.referenceRawR = referenceRaw[0];
        frame.colorimetry.referenceRawG = referenceRaw[1];
        frame.colorimetry.referenceRawB = referenceRaw[2];
        frame.colorimetry.referenceR = referenceCorrected[0];
        frame.colorimetry.referenceG = referenceCorrected[1];
        frame.colorimetry.referenceB = referenceCorrected[2];
        frame.colorimetry.reactionRawR = reactionRaw[0];
        frame.colorimetry.reactionRawG = reactionRaw[1];
        frame.colorimetry.reactionRawB = reactionRaw[2];
        frame.colorimetry.reactionR = reactionCorrected[0];
        frame.colorimetry.reactionG = reactionCorrected[1];
        frame.colorimetry.reactionB = reactionCorrected[2];
        frame.colorimetry.referenceLabL = referenceLab.l;
        frame.colorimetry.referenceLabA = referenceLab.a;
        frame.colorimetry.referenceLabB = referenceLab.b;
        frame.colorimetry.reactionLabL = reactionLab.l;
        frame.colorimetry.reactionLabA = reactionLab.a;
        frame.colorimetry.reactionLabB = reactionLab.b;
        frame.colorimetry.swatchCount = static_cast<int>(patchMeans.size());
        frame.colorimetry.classification = static_cast<int>(classify(profile, deltaE));

        frame.status = kCvStatusOk;
        return frame;
    } catch (const cv::Exception&) {
        frame.status = kCvStatusCvException;
        return frame;
    } catch (...) {
        frame.status = kCvStatusCvException;
        return frame;
    }
}
