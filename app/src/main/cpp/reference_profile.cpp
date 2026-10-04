#include "reference_profile.h"

#include <string>

namespace {

// TWO PROFILES, ONE PIPELINE. READ THIS BEFORE CHANGING ANY NUMBER BELOW.
//
// This file is the only place in the C++ tree that describes reference data. The CV stage reads
// whichever profile it is handed and computes identically either way; the profile decides what the
// numbers may be compared against and what the result is allowed to claim. Nothing here may be
// presented as kit data unless it came from a kit.
//
// PROFILE 1 - NCB-STANDARD-NARCOTICS-DD-KIT (ProfileKind::kField)
// Qualitative protocol transcribed from the NICFS Forensic Guide, Chapter 8, Figure 8.13. Official,
// and strictly qualitative: it names colours in words and never supplies a number. Every numeric
// field below (marker ids, centres, swatch rectangles, reaction window, dE00 boundaries) is
// therefore still an arbitrary placeholder.
//
// PROFILE 2 - DEMO_SYNTHETIC_PROFILE_V1 (ProfileKind::kSyntheticDemonstration)
// Synthetic hex anchors whose only purpose is to prove the shared pipeline executes end to end.
// They are not official NCB data, not physical calibration data, not forensic validation data and
// not a drug-classification dataset. They never enter profile 1: the table is constexpr and
// profile 1's syntheticAnchorCount is 0, so there is no field on it that could hold one.
//
// Replacing either profile's contents with validated data is the intended path to a non-demo build:
// fill in the marker ids and centres from the printed card, fill in the patch rectangles and their
// measured ideal colours, set validation to kValidated, set calibrationMode to
// kColorCorrectionMatrix, and bump the version. The pipeline must not be edited to do that.

// Marker ids 1..4 of DICT_4X4_50, in card order top-left, top-right, bottom-right, bottom-left.
// PROVISIONAL: these are the first four ids of the dictionary, not ids printed on any real card.
// Shared by both profiles because neither physical card has been characterised; when a real card
// is measured, the synthetic profile's ids change with it and its demonstration label does not.
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
constexpr ClassificationPolicy kProvisionalClassification = {2.0, 5.0, true, false};

// DEMONSTRATION ONLY: the same two numbers, restated under the profile they belong to so that a
// reader of the synthetic table never has to go and find the field one. A synthetic profile is
// allowed to classify (that is the demonstration); the result is labelled DEMONSTRATIVE at the UI
// and reference_data_version boundaries and is never a presumptive finding.
constexpr ClassificationPolicy kSyntheticDemonstrationClassification = {2.0, 5.0, true, false};

// NICFS Forensic Guide, Chapter 8, Figure 8.13. These are verbatim qualitative outcomes from
// the official guide's Tests A, B and E. The guide excerpt does not show Tests C or D, so this
// table deliberately does not infer, name or implement them.
constexpr QualitativeTestProtocol kNcbQualitativeProtocols[3] = {
    {
        "TEST A",
        "Opium, morphine, codeine, heroin, amphetamines and mescaline",
        {{"Opium: place a match-head-sized sample on the spot plate."},
         {"Opium: add 2 or 3 drops of water and smear with the supplied glass rod or spatula for 1 or 2 minutes."},
         {"Opium: transfer one drop of the liquid to another part of the spot plate; add 1 drop of A1, then 3 drops of A2."},
         {"Morphine, codeine, heroin, amphetamines and mescaline: place a match-head-sized sample on the spot plate."},
         {"For morphine, codeine, heroin, amphetamines and mescaline: add 1 drop of A1, then 3 drops of A2."}},
        5,
        {{"Opium", "red-brown"}, {"Morphine", "purple to grey"},
         {"Codeine", "pink to grey"}, {"Heroin", "pink to mauve"},
         {"Amphetamines", "orange to dark grey"}, {"Mescaline", "orange to red"}},
        6,
        "Observe the qualitative colour transition after A1 then A2.",
    },
    {
        "TEST B",
        "Marijuana, hashish and hashish oil",
        {{"Place a match-head-sized sample in a test tube supplied with the kit."},
         {"Add a match-head-sized amount of B1."}, {"Add 25 drops of B2 and shake for 1 minute."},
         {"Add 25 drops of B3 and shake for 2 minutes."}, {"Allow the test tube to stand for 2 minutes."}},
        5,
        {{"Marijuana", "lower liquid layer red to light pink"},
         {"Hashish", "lower liquid layer red to light pink"},
         {"Hashish oil", "lower liquid layer red to light pink"}},
        3,
        "Read only the lower liquid layer; ignore the upper layer.",
    },
    {
        "TEST E",
        "Cocaine and methaqualone",
        {{"For E1/E2: if the material is a tablet, grind it to a fine powder; place a match-head-sized sample in a supplied test tube."},
         {"Add 1 drop of E1 and shake for 10 seconds."}, {"Add 1 drop of E2 and shake for 10 seconds."},
         {"For E3/E4 differentiation: place a small amount of suspected material in a test tube; add 5 drops of E3."},
         {"Add 3 drops of E4."}},
        5,
        {{"Cocaine (E1 + E2)", "blue"}, {"Methaqualone (E1 + E2)", "blue"},
         {"Cocaine (E3 + E4)", "green"}, {"Methaqualone (E3 + E4)", "yellow"}},
        4,
        "E1 + E2 produces blue; E3 + E4 differentiates cocaine (green) from methaqualone (yellow).",
    },
};

// SYNTHETIC DEMONSTRATION DATASET - NOT OFFICIAL DATA OF ANY KIND.
//
// Ten anchors, stored exactly as the demonstration dataset defines them. They are:
//   * not measured from a physical NCB kit;
//   * not published by NCB, NICFS, ISO or any other authority;
//   * not reference colours for any reagent;
//   * not a drug-classification dataset.
//
// Their only purpose is to demonstrate that the shared pipeline performs image -> geometry -> ROI
// -> colour measurement -> Lab -> CIEDE2000 -> reference comparison -> a labelled demonstration
// outcome. Note in particular that "FLOW III / TEST C" below is the SYNTHETIC demonstration
// dataset's own Test C entry: the field profile above has no Test C at all, and these numbers must
// never be copied into it.
//
// role1/hex1 is always the starting or principal colour; role2/hex2 is the second colour of a
// two-colour transition, or nullptr for a single-colour anchor. Empty timingNote means the dataset
// specifies no timing.
// Unsized on purpose: the array's own size is then exactly the number of anchors written below,
// so the static_assert below cannot drift away from the data the way a declared capacity can.
constexpr SyntheticAnchor kSyntheticAnchors[] = {
    // --- Flow I / Test A -------------------------------------------------------------
    {"FLOW I / TEST A", "Heroin", "Reagent A1 + A2", "Pink to Purple", "",
     "Start", "#FFC0CB", "End", "#800080"},
    {"FLOW I / TEST A", "Morphine", "Reagent A1 + A2", "Purple to Black/Grey", "",
     "Start", "#800080", "End", "#2F4F4F"},
    {"FLOW I / TEST A", "Codeine", "Reagent A1 + A2", "Purple to Dark Purple", "",
     "Start", "#800080", "End", "#4B0082"},
    {"FLOW I / TEST A", "Amphetamines", "Reagent A1 + A2", "Yellow to Black", "",
     "Start", "#FFFF00", "End", "#000000"},
    {"FLOW I / TEST A", "Mescaline", "Reagent A1 + A2", "Orange to Red", "",
     "Start", "#FFA500", "End", "#FF0000"},
    // --- Flow II / Test B ------------------------------------------------------------
    {"FLOW II / TEST B", "Cannabis / Hashish", "Reagent B1 + B2 + B3",
     "Purplish-Blue over Dark Purple", "",
     "Top layer", "#4169E1", "Base", "#300130"},
    // --- Flow III / Test C (synthetic dataset only; field profile has no Test C) ------
    {"FLOW III / TEST C", "Cocaine", "Reagent C1 + C2 + C3", "Brilliant Blue Specks in Pink", "",
     "Base", "#FFB6C1", "Specks", "#0000FF"},
    // --- Synthetic blister tests -----------------------------------------------------
    {"BLISTER TEST 01", "Methamphetamine", "Marquis", "Orange-Brown", "under 12 seconds",
     "Anchor", "#CD853F", nullptr, nullptr},
    {"BLISTER TEST 01", "MDMA / Ecstasy", "Marquis", "Orange to Black", "",
     "Start", "#FF8C00", "End", "#050505"},
    {"BLISTER TEST 04", "LSD", "Ehrlich's", "Clear Lavender Purple", "",
     "Start", "#E6E6FA", "End", "#9370DB"},
    {"BLISTER TEST 03", "Barbiturates", "Dille-Koppanyi", "Clear Lavender", "",
     "Anchor", "#DDA0DD", nullptr, nullptr},
};

// Counted from the table above, not guessed: eleven anchors, and the last is the barbiturates
// blister anchor. A count below the real size silently drops an anchor from the profile, and a
// count above it hands out zeroed entries that read as real data.
static_assert(sizeof(kSyntheticAnchors) / sizeof(kSyntheticAnchors[0]) == 11,
              "the synthetic dataset above and its declared size have drifted apart");

constexpr int kSyntheticAnchorCount = 11;

static_assert(kSyntheticAnchorCount <= kMaxSyntheticAnchors,
              "raise kMaxSyntheticAnchors rather than truncating the synthetic dataset");

// Not source-backed, so this profile carries no official protocol and no expected qualitative
// colours. Only the two array slots exist for the zero/one element initialiser.
constexpr QualitativeTestProtocol kNoQualitativeProtocol = {"", "", {{""}}, 0, {{""}}, 0, ""};

constexpr ReferenceCardProfile kProfiles[] = {
    {
        /* id */ "NCB-STANDARD-NARCOTICS-DD-KIT",
        /* version */ "NCB-STANDARD-KIT-QUALITATIVE-2020-UNVALIDATED",
        /* validation */ ProfileValidation::kUnvalidated,
        /* kind */ ProfileKind::kField,
        /* reagentType */ "NCB_STANDARD_NARCOTICS_DD_KIT",
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
        /* qualitativeProtocols */ {kNcbQualitativeProtocols[0], kNcbQualitativeProtocols[1],
                                    kNcbQualitativeProtocols[2]},
        /* qualitativeProtocolCount */ 3,
        /* sourceTitle */ "NICFS Forensic Guide, Chapter 8, Figure 8.13; NCB Annual Report 2023-24",
        /* sourceUrl */ "https://police.py.gov.in/Brief%20on%20Narcotics%20Drugs%20and%20Psychotrophic%20Substances%20-%20Chapter%208.pdf | https://narcoticsindia.nic.in/Publication/ncb-annual-report-2023-24.pdf",
        /* sourceStatus */ "OFFICIAL_QUALITATIVE_REFERENCE. NUMERICAL CALIBRATION: UNVALIDATED. DECISION BOUNDARIES: UNVALIDATED.",
        /* syntheticAnchors */ {},
        /* syntheticAnchorCount */ 0,
        /* syntheticAnchorMatchAtOrBelow */ 0.0,
        /* provenance */
        "OFFICIAL QUALITATIVE REFERENCE: NCB / NICFS documentation supplies the Test A, B and E "
        "procedures and expected colour names only. NUMERICAL CALIBRATION: UNVALIDATED - no "
        "official source supplies RGB, Lab or hex values. DECISION BOUNDARIES: UNVALIDATED - marker "
        "ids, swatch positions, reaction window and dE00 boundaries are pipeline placeholders. "
        "Without validated numerics this profile can only ever return INCONCLUSIVE.",
    },
    {
        /* id */ "DEMO_SYNTHETIC_PROFILE_V1",
        /* version */ "DEMO_SYNTHETIC_PROFILE_V1-SYNTHETIC_DEMONSTRATION_ONLY",
        /* validation */ ProfileValidation::kUnvalidated,
        /* kind */ ProfileKind::kSyntheticDemonstration,
        /* reagentType */ "SYNTHETIC_DEMONSTRATION_ONLY",
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
        // No colour correction matrix here either: a CCM fitted to synthetic targets would report
        // a synthetic colour as a corrected measurement, which is the one thing a colour
        // correction matrix must never do.
        /* calibrationMode */ CalibrationMode::kNoneProvisional,
        // SYNTHETIC DEMONSTRATION ONLY: the measured reaction colour is compared against the
        // nearest of this profile's own synthetic anchors rather than against the card's printed
        // swatches. Both comparisons run the same RGB -> Lab -> CIEDE2000 path; only the thing the
        // measured colour is compared to differs. The field profile keeps
        // kMeasuredReferencePatches, so nothing about real casework changes.
        /* comparisonTarget */ ComparisonTarget::kNearestSyntheticAnchor,
        /* classification */ kSyntheticDemonstrationClassification,
        /* qualitativeProtocols */ {kNoQualitativeProtocol},
        /* qualitativeProtocolCount */ 0,
        /* sourceTitle */ "MODEL / SYNTHETIC COLOUR ANCHORS",
        /* sourceUrl */ "NONE. Not derived from any kit, standard, publication or measurement.",
        /* sourceStatus */ "SYNTHETIC_DEMONSTRATION_ONLY. NOT OFFICIAL NCB DATA. NOT PHYSICAL CALIBRATION. NOT FORENSICALLY VALIDATED.",
        /* syntheticAnchors */ {kSyntheticAnchors[0], kSyntheticAnchors[1], kSyntheticAnchors[2],
                                kSyntheticAnchors[3], kSyntheticAnchors[4], kSyntheticAnchors[5],
                                kSyntheticAnchors[6], kSyntheticAnchors[7], kSyntheticAnchors[8],
                                kSyntheticAnchors[9], kSyntheticAnchors[10]},
        /* syntheticAnchorCount */ kSyntheticAnchorCount,
        // The demonstration tolerance, and the reasoning behind 18.0, are documented on the field
        // itself in reference_profile.h. It is a boundary for a synthetic dataset and says nothing
        // about any reagent or substance.
        /* syntheticAnchorMatchAtOrBelow */ 18.0,
        /* provenance */
        "SYNTHETIC COLOUR ANCHORS: values chosen for this project, not measured from a physical "
        "NCB kit and not published by NCB or NICFS. NUMERICAL CALIBRATION: SYNTHETIC. DECISION "
        "BOUNDARIES: DEMONSTRATION_ONLY. Every outcome this profile produces is a demonstration of "
        "the colour-comparison workflow and is never a presumptive finding; any RGB, Lab or dE00 "
        "derived from these anchors is arithmetic on a synthetic number.",
    },
};

static_assert(sizeof(kProfiles) / sizeof(kProfiles[0]) > 0, "profile table must not be empty");
static_assert(sizeof(kProfiles) / sizeof(kProfiles[0]) > kSyntheticProfileIndex,
              "the synthetic profile must exist in the table");

}  // namespace

const ReferenceCardProfile& ncbProfile() {
    return kProfiles[kNcbProfileIndex];
}

const ReferenceCardProfile& syntheticProfile() {
    return kProfiles[kSyntheticProfileIndex];
}

namespace {

/**
 * Parses a stored "#RRGGBB" anchor colour into Lab with the pipeline's own colour science, so the
 * demonstration comparison cannot drift from the one every other number in the app goes through.
 *
 * Returns false for anything that is not exactly seven characters of "#" followed by six hex
 * digits. That strictness is the point: the anchors are deliberately stored as text, so a value
 * that is not a colour must be skipped rather than coerced into one, and a malformed entry must
 * never become a distance that some threshold could be compared against.
 */
bool hexToLab(const char* hex, Lab& out) {
    if (hex == nullptr) {
        return false;
    }
    // strlen without <cstring>: the anchors are NUL-terminated literals in the table above, so the
    // scan is bounded by the literal itself rather than trusted to a fixed length.
    int length = 0;
    while (hex[length] != '\0' && length < 7) {
        ++length;
    }
    if (length != 7 || hex[0] != '#') {
        return false;
    }
    int channel[3] = {0, 0, 0};
    for (int c = 0; c < 3; ++c) {
        int value = 0;
        for (int d = 0; d < 2; ++d) {
            const char ch = hex[1 + c * 2 + d];
            int digit;
            if (ch >= '0' && ch <= '9') {
                digit = ch - '0';
            } else if (ch >= 'a' && ch <= 'f') {
                digit = ch - 'a' + 10;
            } else if (ch >= 'A' && ch <= 'F') {
                digit = ch - 'A' + 10;
            } else {
                return false;
            }
            value = value * 16 + digit;
        }
        channel[c] = value;
    }
    out = srgbToLab(channel[0] / 255.0, channel[1] / 255.0, channel[2] / 255.0);
    return true;
}

}  // namespace

SyntheticAnchorMatch nearestSyntheticAnchor(const ReferenceCardProfile& profile, Lab measuredLab) {
    SyntheticAnchorMatch best;
    for (int i = 0; i < profile.syntheticAnchorCount && i < kMaxSyntheticAnchors; ++i) {
        const SyntheticAnchor& anchor = profile.syntheticAnchors[i];
        // Both roles are scanned: an anchor is a colour transition, so either end of it is a
        // demonstration colour the measured reaction could legitimately match.
        for (int role = 0; role < 2; ++role) {
            const char* hex = role == 0 ? anchor.hex1 : anchor.hex2;
            const char* roleName = role == 0 ? anchor.role1 : anchor.role2;
            Lab anchorLab;
            if (hex == nullptr || roleName == nullptr || !hexToLab(hex, anchorLab)) {
                continue;
            }
            const double delta = ciede2000(anchorLab, measuredLab);
            if (best.found && delta >= best.deltaE) {
                continue;
            }
            best.found = true;
            best.deltaE = delta;
            best.anchorIndex = i;
            best.roleIndex = role + 1;
            best.label = std::string(anchor.flow) + " / " + anchor.target + " / " + roleName +
                         " (" + hex + "; expected " + anchor.expectedPhrase + ")";
        }
    }
    return best;
}

const ReferenceCardProfile& activeProfile() {
    // The default is the source-backed field profile, not the synthetic one. A caller that forgets
    // to name a profile therefore gets the conservative INCONCLUSIVE field path rather than a
    // demonstration result.
    return ncbProfile();
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
    return kNcbProfileIndex;
}
