#include "reference_profile.h"

namespace {

// PROVISIONAL PROFILE - READ THIS BEFORE CHANGING ANY NUMBER BELOW.
//
// Nothing in this file came from a kit manufacturer, a printed NCB reference card, or a published
// colour dataset. The project documentation specifies only the SHAPE of the data: four corner
// ArUco markers, rectification to 500x500, a CCM from at least 6 swatches, and a configurable
// dE00 boundary table with an INCONCLUSIVE band. Every concrete value here is an arbitrary
// placeholder that makes the pipeline executable end to end.
//
// What is real: the marker ids are real ids of a real ArUco dictionary, and the rectangles are real
// rectangles. What is arbitrary: WHICH ids, WHERE the patches sit, WHERE the reaction window sits,
// and the two dE00 boundaries.
//
// Replacing this file's contents with validated data is the intended path to a non-demo build:
// fill in the marker ids and centres from the printed card, fill in the patch rectangles and their
// measured ideal colours, set validation to kValidated, set calibrationMode to
// kColorCorrectionMatrix, and bump the version. The pipeline must not be edited to do that.

// Marker ids 1..4 of DICT_4X4_50, in card order top-left, top-right, bottom-right, bottom-left.
// PROVISIONAL: these are the first four ids of the dictionary, not ids printed on any real card.
constexpr int kProvisionalMarkerIds[4] = {1, 2, 3, 4};

// PROVISIONAL: marker centres inset 10% from each card corner.
constexpr NormalizedRect kProvisionalMarkerCentres[4] = {
    {0.10, 0.10, 0.0, 0.0},
    {0.90, 0.10, 0.0, 0.0},
    {0.90, 0.90, 0.0, 0.0},
    {0.10, 0.90, 0.0, 0.0},
};

// PROVISIONAL: two rows of three neutral swatches between the marker band and the reaction
// window. The count (6) is the architecture's own CCM stability recommendation, which is why it is
// 6 and not 3. The positions must also stay clear of the marker footprints at the card corners,
// because a swatch a marker overlaps is not a measurement of the swatch.
constexpr ReferencePatch kProvisionalPatches[6] = {
    {{0.20, 0.22, 0.16, 0.10}, 0.0, 0.0, 0.0, false},
    {{0.42, 0.22, 0.16, 0.10}, 0.0, 0.0, 0.0, false},
    {{0.68, 0.22, 0.16, 0.10}, 0.0, 0.0, 0.0, false},
    {{0.20, 0.35, 0.16, 0.10}, 0.0, 0.0, 0.0, false},
    {{0.42, 0.35, 0.16, 0.10}, 0.0, 0.0, 0.0, false},
    {{0.68, 0.35, 0.16, 0.10}, 0.0, 0.0, 0.0, false},
};

// cv::aruco::DICT_4X4_50. Spelled numerically to keep this header free of OpenCV includes;
// vision_test.cpp asserts it equals the OpenCV enum value, so a wrong constant fails there rather
// than silently detecting nothing at runtime.
constexpr int kProvisionalArucoDictionary = 0;

// PROVISIONAL: 2.0 and 5.0 are demonstration boundaries chosen to leave a visible INCONCLUSIVE
// band between them. They are NOT the reaction threshold of any reagent.
constexpr ClassificationPolicy kProvisionalClassification = {2.0, 5.0, true};

constexpr ReferenceCardProfile kProfiles[] = {
    {
        /* id */ "NCB-CARD",
        /* version */ "0.1.0-PROVISIONAL",
        /* validation */ ProfileValidation::kUnvalidated,
        /* reagentType */ "MARQUIS",
        /* arucoDictionary */ kProvisionalArucoDictionary,
        /* markerIds */ {kProvisionalMarkerIds[0], kProvisionalMarkerIds[1], kProvisionalMarkerIds[2],
                         kProvisionalMarkerIds[3]},
        /* markerCentres */ {kProvisionalMarkerCentres[0], kProvisionalMarkerCentres[1],
                             kProvisionalMarkerCentres[2], kProvisionalMarkerCentres[3]},
        /* rectifiedWidth */ 500,
        /* rectifiedHeight */ 500,
        /* patches */ {kProvisionalPatches[0], kProvisionalPatches[1], kProvisionalPatches[2],
                       kProvisionalPatches[3], kProvisionalPatches[4], kProvisionalPatches[5]},
        /* patchCount */ 6,
        /* reactionRoi */ {0.35, 0.55, 0.30, 0.30},
        /* calibrationMode */ CalibrationMode::kNoneProvisional,
        /* comparisonTarget */ ComparisonTarget::kMeasuredReferencePatches,
        /* classification */ kProvisionalClassification,
        /* provenance */
        "PROVISIONAL: marker ids, swatch positions, reaction window and dE00 boundaries are "
        "placeholders that exercise the pipeline. No kit manufacturer, printed card or published "
        "colour dataset supplied them. Not validated for any reagent.",
    },
};

static_assert(sizeof(kProfiles) / sizeof(kProfiles[0]) > 0, "profile table must not be empty");

}  // namespace

const ReferenceCardProfile& activeProfile() {
    return kProfiles[0];
}

int profileCount() {
    return static_cast<int>(sizeof(kProfiles) / sizeof(kProfiles[0]));
}

const ReferenceCardProfile* profileAt(int index) {
    if (index < 0 || index >= profileCount()) {
        return nullptr;
    }
    return &kProfiles[index];
}

int activeProfileIndex() {
    return 0;
}