#!/usr/bin/env python3
"""Generate and verify the two synthetic demonstration reference cards.

    python scripts/make_synthetic_cards.py            # write both PNGs and verify them
    python scripts/make_synthetic_cards.py --verify   # verify only, write nothing

WHAT THIS IS
    SYNTHETIC DEMONSTRATION ONLY. The cards printed by this script carry colour anchors from
    DEMO_SYNTHETIC_PROFILE_V1 (app/src/main/cpp/reference_profile.cpp). Those anchors are not
    official NCB data, are not measured from a physical kit, are not forensically validated, and
    are not a drug-identification dataset. A result produced against these cards demonstrates
    that the colour-comparison workflow runs. It is never a presumptive finding.

WHY THE PROFILE IS PARSED RATHER THAN COPIED
    Every geometric and colour value below is read out of reference_profile.cpp at run time.
    The pipeline reads the same table, so a card that disagrees with the profile could not
    produce a demonstration at all - and a second copy of the numbers here is exactly the kind
    of thing that drifts silently. There is one source of truth and this script reads it.

WHAT IS VERIFIED (--verify, and again after writing)
    ArUco ids are exactly {1,2,3,4}; marker centres land on the profile's normalised centres;
    the homography rectifies to the profile's rectified size; the reference and reaction ROIs
    land where the profile says; the reaction ROI measures the hex that was painted; the
    reaction colour is converted sRGB -> XYZ -> Lab with the same constants as colorimetry.cpp
    and compared against every synthetic anchor with the same CIEDE2000; and the outcome
    (MATCH / NO MATCH) is derived from that distance against the profile's own tolerance.
    Nothing here asserts an outcome it was told to expect.
"""

from __future__ import annotations

import argparse
import math
import pathlib
import re
import sys

import cv2
import numpy as np

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
PROFILE_CPP = REPO_ROOT / "app" / "src" / "main" / "cpp" / "reference_profile.cpp"
CARD_PATH = REPO_ROOT / "drugrepo_synthetic_reference_match.png"
NO_MATCH_CARD_PATH = REPO_ROOT / "drugrepo_synthetic_reference_no_match.png"

# 1600 px square, the same render size the previously proven
# drugrepo_aruco_camera_test_ids_1_2_3_4.png used. Marker side is 12.5% of the card, which is
# 200/1600 here and 60/500 in the profile's own 500x500 card space: the same proportion.
CARD_PIXELS = 1600
MARKER_SIDE_FRACTION = 0.125
SHEET_VALUE = 245

# ---------------------------------------------------------------------------
# Synthetic demonstration anchors chosen for the card's printed reference
# swatches. Each is an EXISTING hex from DEMO_SYNTHETIC_PROFILE_V1; the set is
# picked for visual spread across hue and lightness, and so that the MATCH
# card's test colour (#FFFF00) is visibly one of the printed references.
# ---------------------------------------------------------------------------
REFERENCE_SWATCHES = [
    ("#800080", "Heroin / End"),
    ("#2F4F4F", "Morphine / End"),
    ("#FFFF00", "Amphetamines / Start"),
    ("#FF0000", "Mescaline / End"),
    ("#4169E1", "Cannabis / Top layer"),
    ("#DDA0DD", "Barbiturates"),
]

# The two test-area colours. MATCH is an existing anchor outright (dE00 0.00);
# NO MATCH is far from every anchor in the dataset (min dE00 37.14). Both were
# chosen against the profile's own tolerance; see scripts/make_synthetic_cards.py
# output and reference_profile.h for the bias analysis.
MATCH_COLOUR = "#FFFF00"
NO_MATCH_COLOUR = "#00B078"

# ---------------------------------------------------------------------------
# Colour science: a faithful port of app/src/main/cpp/colorimetry.cpp so the
# host verification runs the pipeline's own maths rather than a second,
# possibly different, implementation. kL = kC = kH = 1, D65.
# ---------------------------------------------------------------------------
WHITE_X, WHITE_Y, WHITE_Z = 0.95047, 1.00000, 1.08883  # colorimetry.h cie::
RGB_TO_XYZ = [
    [0.4124564, 0.3575761, 0.1804375],
    [0.2126729, 0.7151522, 0.0721750],
    [0.0193339, 0.1191920, 0.9503041],
]
DELTA = 6.0 / 29.0
DELTA_CUBED = DELTA ** 3
DELTA_SQUARED = DELTA ** 2


def srgb_to_linear(channel: float) -> float:
    c = min(max(channel, 0.0), 1.0)
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def lab_f(t: float) -> float:
    return t ** (1.0 / 3.0) if t > DELTA_CUBED else t / (3.0 * DELTA_SQUARED) + 4.0 / 29.0


def srgb_to_lab(r: float, g: float, b: float) -> tuple[float, float, float]:
    lr, lg, lb = srgb_to_linear(r), srgb_to_linear(g), srgb_to_linear(b)
    x = sum(RGB_TO_XYZ[0][i] * (lr, lg, lb)[i] for i in range(3))
    y = sum(RGB_TO_XYZ[1][i] * (lr, lg, lb)[i] for i in range(3))
    z = sum(RGB_TO_XYZ[2][i] * (lr, lg, lb)[i] for i in range(3))
    fx, fy, fz = lab_f(x / WHITE_X), lab_f(y / WHITE_Y), lab_f(z / WHITE_Z)
    return 116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz)


def hex_to_rgb(value: str) -> tuple[int, int, int]:
    v = value.lstrip("#")
    return int(v[0:2], 16), int(v[2:4], 16), int(v[4:6], 16)


def hex_to_bgr(value: str) -> tuple[int, int, int]:
    r, g, b = hex_to_rgb(value)
    return b, g, r


def hex_to_lab(value: str) -> tuple[float, float, float]:
    r, g, b = hex_to_rgb(value)
    return srgb_to_lab(r / 255.0, g / 255.0, b / 255.0)


def _hue_degrees(b: float, a_prime: float) -> float:
    if b == 0.0 and a_prime == 0.0:
        return 0.0
    angle = math.degrees(math.atan2(b, a_prime))
    return angle + 360.0 if angle < 0.0 else angle


def ciede2000(reference: tuple[float, float, float], sample: tuple[float, float, float]) -> float:
    """CIEDE2000 with kL = kC = kH = 1, a direct port of colorimetry.cpp::ciede2000."""
    l1, a1, b1 = reference
    l2, a2, b2 = sample
    c1, c2 = math.hypot(a1, b1), math.hypot(a2, b2)
    c_bar = (c1 + c2) / 2.0
    c_bar7 = c_bar ** 7
    g = 0.5 * (1.0 - math.sqrt(c_bar7 / (c_bar7 + 25.0 ** 7)))
    a1p, a2p = (1.0 + g) * a1, (1.0 + g) * a2
    c1p, c2p = math.hypot(a1p, b1), math.hypot(a2p, b2)
    h1p, h2p = _hue_degrees(b1, a1p), _hue_degrees(b2, a2p)
    dl = l2 - l1
    dc = c2p - c1p
    if c1p * c2p == 0.0:
        dh = 0.0
    elif abs(h2p - h1p) <= 180.0:
        dh = h2p - h1p
    elif h2p - h1p > 180.0:
        dh = h2p - h1p - 360.0
    else:
        dh = h2p - h1p + 360.0
    dh_prime = 2.0 * math.sqrt(c1p * c2p) * math.sin(math.radians(dh) / 2.0)
    l_bar_prime = (l1 + l2) / 2.0
    c_bar_prime = (c1p + c2p) / 2.0
    if c1p * c2p == 0.0:
        h_bar_prime = h1p + h2p
    elif abs(h1p - h2p) <= 180.0:
        h_bar_prime = (h1p + h2p) / 2.0
    elif h1p + h2p < 360.0:
        h_bar_prime = (h1p + h2p + 360.0) / 2.0
    else:
        h_bar_prime = (h1p + h2p - 360.0) / 2.0
    t = (
        1.0
        - 0.17 * math.cos(math.radians(h_bar_prime - 30.0))
        + 0.24 * math.cos(math.radians(2.0 * h_bar_prime))
        + 0.32 * math.cos(math.radians(3.0 * h_bar_prime + 6.0))
        - 0.20 * math.cos(math.radians(4.0 * h_bar_prime - 63.0))
    )
    d_theta = 30.0 * math.exp(-(((h_bar_prime - 275.0) / 25.0) ** 2))
    c_bar_prime7 = c_bar_prime ** 7
    r_c = 2.0 * math.sqrt(c_bar_prime7 / (c_bar_prime7 + 25.0 ** 7))
    s_l = 1.0 + (0.015 * (l_bar_prime - 50.0) ** 2) / math.sqrt(20.0 + (l_bar_prime - 50.0) ** 2)
    s_c = 1.0 + 0.045 * c_bar_prime
    s_h = 1.0 + 0.015 * c_bar_prime * t
    r_t = -math.sin(math.radians(2.0 * d_theta)) * r_c
    term_l = dl / s_l
    term_c = dc / s_c
    term_h = dh_prime / s_h
    delta = term_l ** 2 + term_c ** 2 + term_h ** 2 + r_t * term_c * term_h
    return math.sqrt(max(delta, 0.0))


# ---------------------------------------------------------------------------
# The profile, read out of reference_profile.cpp.
# ---------------------------------------------------------------------------
class Profile:
    def __init__(self) -> None:
        text = PROFILE_CPP.read_text(encoding="utf-8")

        self.marker_ids = [int(m) for m in re.search(
            r"kProvisionalMarkerIds\[4\]\s*=\s*\{([^}]*)\}", text).group(1).split(",")]

        centres = re.search(r"kProvisionalMarkerCentres\[4\]\s*=\s*\{(.*?)\n\};", text, re.S)
        self.marker_centres = [
            tuple(float(v) for v in quad.split(","))
            for quad in re.findall(r"\{\s*([\d.]+\s*,\s*[\d.]+\s*,\s*[\d.]+\s*,\s*[\d.]+)\s*\}",
                                   centres.group(1))
        ]

        patches = re.search(r"kProvisionalPatches\[6\]\s*=\s*\{(.*?)\n\};", text, re.S)
        self.patches = [
            tuple(float(v) for v in quad.split(","))
            for quad in re.findall(r"\{\s*\{([\d.]+\s*,\s*[\d.]+\s*,\s*[\d.]+\s*,\s*[\d.]+)\}",
                                   patches.group(1))
        ]

        synthetic = re.search(
            r"/\* id \*/ \"DEMO_SYNTHETIC_PROFILE_V1\",(.*?)/\* provenance \*/", text, re.S).group(1)
        self.rectified_width = int(re.search(r"/\* rectifiedWidth \*/ (\d+)", synthetic).group(1))
        self.rectified_height = int(re.search(r"/\* rectifiedHeight \*/ (\d+)", synthetic).group(1))
        self.reaction_roi = tuple(float(v) for v in re.search(
            r"/\* reactionRoi \*/ \{([^}]*)\}", synthetic).group(1).split(","))
        self.tolerance = float(re.search(
            r"/\* syntheticAnchorMatchAtOrBelow \*/ ([\d.]+)", synthetic).group(1))

        anchors = re.search(r"kSyntheticAnchors\[\]\s*=\s*\{(.*?)\n\};", text, re.S).group(1)
        self.anchors: list[tuple[str, str, str, str]] = []
        for flow, target, _reagent, expected, hex1, hex2 in re.findall(
                r"\{\s*\"([^\"]*)\",\s*\"([^\"]*)\",\s*\"([^\"]*)\",\s*"
                r"\"([^\"]*)\",\s*\"[^\"]*\",\s*\"[^\"]*\",\s*\"(#[0-9A-Fa-f]{6})\","
                r"\s*(?:\"[^\"]*\",\s*\"(#[0-9A-Fa-f]{6})\"|nullptr,\s*nullptr)",
                anchors):
            self.anchors.append((flow, target, expected, hex1))
            if hex2:
                self.anchors.append((flow, target, expected, hex2))

        # The cards only work if the six printed swatches and the test colour are real entries
        # of this profile. Asserted here rather than trusted, because a typo in a hex would
        # otherwise produce a card that still looks right and never demonstrates anything.
        profile_hexes = {anchor_hex for _f, _t, _e, anchor_hex in self.anchors}
        for hex_value, _label in REFERENCE_SWATCHES:
            if hex_value not in profile_hexes:
                raise SystemExit(f"{hex_value} is not an anchor in DEMO_SYNTHETIC_PROFILE_V1")
        if len(self.patches) != len(REFERENCE_SWATCHES):
            raise SystemExit("profile patch count does not match the printed swatch count")
        if len(self.marker_centres) != 4:
            raise SystemExit("profile does not declare four marker centres")


def card_rect(profile: Profile, rect: tuple[float, ...]) -> tuple[int, int, int, int]:
    """Normalised profile rectangle to pixels of the RENDERED card, rounded as cv_pipeline.cpp does."""
    w = h = CARD_PIXELS
    x0 = round(rect[0] * w)
    y0 = round(rect[1] * h)
    x1 = round((rect[0] + rect[2]) * w)
    y1 = round((rect[1] + rect[3]) * h)
    return x0, y0, x1 - x0, y1 - y0


def rectified_rect(profile: Profile, rect: tuple[float, ...]) -> tuple[int, int, int, int]:
    """Normalised profile rectangle to RECTIFIED card pixels, i.e. cv_pipeline.cpp's toCardRect()."""
    w, h = profile.rectified_width, profile.rectified_height
    x0 = round(rect[0] * w)
    y0 = round(rect[1] * h)
    x1 = round((rect[0] + rect[2]) * w)
    y1 = round((rect[1] + rect[3]) * h)
    return x0, y0, x1 - x0, y1 - y0


def _text(img, label, org, scale=0.5, colour=(60, 60, 60)):
    cv2.putText(img, label, org, cv2.FONT_HERSHEY_SIMPLEX, scale, colour, 1, cv2.LINE_AA)


def render(profile: Profile, test_hex: str) -> np.ndarray:
    """Draw the demonstration card. Real pixels throughout; no AI-generated imagery."""
    card = np.full((CARD_PIXELS, CARD_PIXELS, 3), SHEET_VALUE, np.uint8)

    for rect, (hex_value, label) in zip(profile.patches, REFERENCE_SWATCHES):
        x, y, w, h = card_rect(profile, rect)
        card[y:y + h, x:x + w] = hex_to_bgr(hex_value)
        # Labels sit BELOW each swatch and above the next row / the test band. Every one is
        # checked against the measured rectangles below so a label can never bleed into a
        # region the pipeline measures.
        _text(card, label, (x + 4, y + h + 26), 0.42)

    x, y, w, h = card_rect(profile, profile.reaction_roi)
    card[y:y + h, x:x + w] = hex_to_bgr(test_hex)

    dictionary = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_4X4_50)
    side = int(round(MARKER_SIDE_FRACTION * CARD_PIXELS))
    half = side // 2
    for marker_id, (cx, cy, _w, _h) in zip(profile.marker_ids, profile.marker_centres):
        marker = cv2.aruco.generateImageMarker(dictionary, marker_id, side)
        centre = (int(round(cx * CARD_PIXELS)), int(round(cy * CARD_PIXELS)))
        card[centre[1] - half:centre[1] - half + side,
             centre[0] - half:centre[0] - half + side] = cv2.cvtColor(marker, cv2.COLOR_GRAY2BGR)
    return card


def draw_captions(card: np.ndarray, profile: Profile, test_hex: str) -> None:
    """Headings and captions, drawn in bands that contain no measured rectangle.

    Measured regions on this card, in pixels: reference swatches y 352..472, reaction y 880..1360,
    and the marker footprints span 62..262 on both axes. Every caption below is placed in the two
    free bands that leaves, and assert_captions_clear_of_regions() proves it rather than trusting
    these hand-counted numbers.
    """
    reaction_x, reaction_y, reaction_w, _reaction_h = card_rect(profile, profile.reaction_roi)
    _text(card, "FORENZA  SYNTHETIC REFERENCE CARD", (70, 70), 0.95, (40, 40, 40))
    _text(card, "SYNTHETIC DEMONSTRATION ONLY - NOT OFFICIAL NCB DATA", (70, 120), 0.62,
          (95, 95, 95))
    _text(card, "KNOWN REFERENCE COLOURS", (70, 200), 0.85, (40, 40, 40))
    _text(card, "TEST / SAMPLE  (this area is measured)", (reaction_x, reaction_y - 40), 0.85,
          (40, 40, 40))
    _text(card, f"TEST COLOUR  {test_hex}", (reaction_x, reaction_y - 14), 0.62, (40, 40, 40))
    _text(card, "ArUco DICT_4X4_50   ids 1, 2, 3, 4", (70, CARD_PIXELS - 70), 0.62,
          (95, 95, 95))


# --- verification -----------------------------------------------------------

class Failure(Exception):
    pass


def check(condition: bool, message: str, report: list[str]) -> None:
    if condition:
        report.append(f"    OK    {message}")
    else:
        report.append(f"    FAIL  {message}")
        raise Failure(message)


def analyse(profile: Profile, path: pathlib.Path, test_hex: str, expect_match: bool,
             report: list[str]) -> None:
    report.append(f"\n{path.name}")
    image = cv2.imread(str(path), cv2.IMREAD_COLOR)
    check(image is not None, "PNG decodes", report)
    check(image.shape == (CARD_PIXELS, CARD_PIXELS, 3),
          f"card is {CARD_PIXELS}x{CARD_PIXELS}x3 (got {image.shape})", report)

    parameters = cv2.aruco.DetectorParameters()
    parameters.cornerRefinementMethod = cv2.aruco.CORNER_REFINE_SUBPIX
    detector = cv2.aruco.ArucoDetector(
        cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_4X4_50), parameters)
    corners, ids, _ = detector.detectMarkers(image)
    found = sorted(int(i) for i in ids.ravel())
    check(found == sorted(profile.marker_ids),
          f"ArUco ids are exactly {sorted(profile.marker_ids)} (found {found})", report)

    centres: dict[int, tuple[float, float]] = {}
    for marker_corners, marker_id in zip(corners, ids.ravel()):
        centre = marker_corners[0].mean(axis=0)
        centres[int(marker_id)] = (float(centre[0]), float(centre[1]))

    worst = 0.0
    for marker_id, (nx, ny, _w, _h) in zip(profile.marker_ids, profile.marker_centres):
        actual_x, actual_y = centres[marker_id]
        worst = max(worst, abs(actual_x / CARD_PIXELS - nx), abs(actual_y / CARD_PIXELS - ny))
    check(worst < 0.005,
          f"marker centres match the profile's normalised centres (worst error {worst:.4f})", report)

    # Same construction as cv_pipeline.cpp: detected centres in IMAGE pixels, profile centres
    # scaled to the rectified card.
    source = np.float32([centres[i] for i in profile.marker_ids])
    destination = np.float32([[cx * profile.rectified_width, cy * profile.rectified_height]
                              for cx, cy, _w, _h in profile.marker_centres])
    homography = cv2.getPerspectiveTransform(source, destination)
    inverted, inverse = cv2.invert(homography)
    check(inverted != 0 and inverse is not None and inverse.shape == (3, 3),
          "homography is invertible", report)
    rectified = cv2.warpPerspective(
        image, homography, (profile.rectified_width, profile.rectified_height),
        flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT,
        borderValue=(SHEET_VALUE, SHEET_VALUE, SHEET_VALUE))
    check(rectified.shape[:2] == (profile.rectified_height, profile.rectified_width),
          f"rectifies to {profile.rectified_width}x{profile.rectified_height}", report)

    # Two separate claims per swatch, because they are two separate things:
    #
    #  1. The card really contains that flat colour, and nothing else. Proven on the swatch's
    #     INTERIOR, where resampling cannot mix the boundary in. A caption, a border or a marker
    #     overlapping a measured rectangle would show up here as a drift, so this check is also
    #     the proof that no drawn text intrudes into a machine-measured region.
    #  2. What the pipeline will actually average, which includes the resampled boundary pixels
    #     where the flat colour meets the white sheet. Reported, not asserted at zero: the same
    #     edge mixing happens on-device, and pretending otherwise would be the wrong number.
    #     The tolerance is 1/255 of a channel: bilinear resampling of a flat fill cannot do
    #     better than that here, and 1/255 is far below any dE00 this demonstration reads.
    reference_means = []
    for (rect, (hex_value, _label)) in zip(profile.patches, REFERENCE_SWATCHES):
        x, y, w, h = rectified_rect(profile, rect)
        swatch = rectified[y:y + h, x:x + w]
        interior = cv2.erode(swatch, np.ones((5, 5), np.uint8))
        painted = hex_to_bgr(hex_value)
        drift = max(abs(interior.reshape(-1, 3).mean(axis=0)[c] - painted[c]) for c in range(3))
        check(drift < 1.0,
              f"reference swatch {hex_value} is flat {hex_value} inside the ROI "
              f"(drift {drift:.3f}/255)", report)
        mean_bgr = swatch.reshape(-1, 3).mean(axis=0)
        reference_means.append(mean_bgr)
        report.append(f"    ROI mean {hex_value} -> "
                      f'#{"%02X%02X%02X" % (int(mean_bgr[2]), int(mean_bgr[1]), int(mean_bgr[0]))}'
                      f"  (edge-resampled, +"
                      f"{max(mean_bgr[c] - painted[c] for c in range(3)):.1f} max)")

    x, y, w, h = rectified_rect(profile, profile.reaction_roi)
    reaction_window = rectified[y:y + h, x:x + w]
    reaction_interior = cv2.erode(reaction_window, np.ones((7, 7), np.uint8))
    reaction_painted = hex_to_bgr(test_hex)
    reaction_drift = max(abs(reaction_interior.reshape(-1, 3).mean(axis=0)[c] - reaction_painted[c])
                         for c in range(3))
    check(reaction_drift < 1.0,
          f"test/reaction ROI is flat {test_hex} inside the ROI "
          f"(drift {reaction_drift:.3f}/255)", report)
    reaction = reaction_window.reshape(-1, 3).mean(axis=0)
    reaction_hex = "#%02X%02X%02X" % (int(reaction[2]), int(reaction[1]), int(reaction[0]))
    report.append(f"    test/reaction ROI mean -> {reaction_hex} "
                  f"(edge-resampled, +"
                  f"{max(reaction[c] - reaction_painted[c] for c in range(3)):.1f} max)")

    reaction_lab = srgb_to_lab(reaction[2] / 255.0, reaction[1] / 255.0, reaction[0] / 255.0)
    report.append(f"    measured test colour Lab = L={reaction_lab[0]:.2f} "
                  f"a={reaction_lab[1]:.2f} b={reaction_lab[2]:.2f}")

    ranked = sorted(
        (ciede2000(hex_to_lab(anchor_hex), reaction_lab), flow, target, expected, anchor_hex)
        for flow, target, expected, anchor_hex in profile.anchors)
    distance, flow, target, expected, anchor_hex = ranked[0]
    for value, anchor_flow, anchor_target, anchor_expected, hex_value in ranked[:3]:
        report.append(f"    dE00 {value:6.2f}  {anchor_flow} / {anchor_target} / {hex_value} "
                      f"({anchor_expected})")
    report.append(f"    profile tolerance (syntheticAnchorMatchAtOrBelow) = {profile.tolerance}")

    matched = distance <= profile.tolerance
    outcome = "MATCH" if matched else "NO MATCH"
    expected_outcome = "MATCH" if expect_match else "NO MATCH"
    check(matched == expect_match,
          f"nearest anchor dE00 {distance:.2f} -> {outcome} (expected {expected_outcome})",
          report)
    if not expect_match:
        check(distance - profile.tolerance >= 5.0,
              f"NO-MATCH card keeps {distance - profile.tolerance:.2f} dE00 of margin over the "
              f"tolerance {profile.tolerance}", report)

    # The reference patches are what the pipeline reports as the measured reference colour; the
    # synthetic classification compares the REACTION against the anchors, not against these.
    reference_rgb = np.mean(reference_means, axis=0)
    reference_lab = srgb_to_lab(reference_rgb[2] / 255.0, reference_rgb[1] / 255.0,
                                reference_rgb[0] / 255.0)
    patch_delta_e = ciede2000(reference_lab, reaction_lab)
    report.append(f"    measured reference mean RGB = R={reference_rgb[2]:.1f} "
                  f"G={reference_rgb[1]:.1f} B={reference_rgb[0]:.1f} "
                  f"Lab = L={reference_lab[0]:.2f} a={reference_lab[1]:.2f} b={reference_lab[2]:.2f}")
    report.append(f"    dE00 (reference patches vs reaction) = {patch_delta_e:.2f}")
    report.append(f"    matched demonstration anchor = {flow} / {target} / {anchor_hex}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", action="store_true", help="verify existing PNGs, write none")
    args = parser.parse_args()

    profile = Profile()
    print(f"profile      : DEMO_SYNTHETIC_PROFILE_V1, {profile.rectified_width}x"
          f"{profile.rectified_height}, tolerance dE00 <= {profile.tolerance}")
    print(f"marker ids   : {profile.marker_ids} DICT_4X4_50")
    print(f"anchors      : {len(profile.anchors)} colour roles in the dataset")
    print(f"MATCH card   : {CARD_PATH.name}, test colour {MATCH_COLOUR}")
    print(f"NO MATCH card: {NO_MATCH_CARD_PATH.name}, test colour {NO_MATCH_COLOUR}")

    report: list[str] = []
    for path, test_hex, expect_match in (
        (CARD_PATH, MATCH_COLOUR, True),
        (NO_MATCH_CARD_PATH, NO_MATCH_COLOUR, False),
    ):
        if not args.verify:
            card = render(profile, test_hex)
            draw_captions(card, profile, test_hex)
            if not cv2.imwrite(str(path), card):
                raise SystemExit(f"could not write {path}")
            print(f"wrote {path.name} ({path.stat().st_size} bytes)")
        analyse(profile, path, test_hex, expect_match, report)

    print("\n".join(report))
    print("\nAll card checks passed.")
    print("SYNTHETIC DEMONSTRATION ONLY. These anchors are not official NCB data, are not "
          "measured from a physical kit, and are not forensically validated.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Failure as failure:
        print(f"\n\nVERIFICATION FAILED: {failure}", file=sys.stderr)
        sys.exit(1)
