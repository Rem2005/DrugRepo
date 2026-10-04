# Forenza

### Digital Companion for Field Drug Testing

**Forenza** is an offline-first Android prototype designed to assist officers during field drug testing by providing objective colour analysis, structured test records, and an auditable digital trail.

> **SIH Problem Statement:** PS 26231 — Digital Companion for Field Drug Testing

---

## Overview

Field drug-testing kits commonly rely on visual interpretation of colour-change reactions. This can make results subjective and difficult to standardise, while traditional field workflows may provide limited digital evidence of how a test was performed.

Forenza explores a digital workflow where a mobile device can:

- Capture the physical test card using the device camera
- Detect and verify the reference card geometry
- Correct perspective using ArUco markers
- Extract predefined reference and reaction regions
- Measure the observed colour
- Convert colour values into CIE Lab
- Compare colours using CIEDE2000
- Preserve capture and analysis metadata
- Maintain an integrity-linked record of the test

The application is designed around an **offline-first** workflow so that core field operations do not depend on network connectivity.
<p align="center">
<img width="300"  alt="Screenshot_2026-10-04-18-07-26-63_18d2b75f7e632145edbd616008505952 jpg" src="https://github.com/user-attachments/assets/9d2cc8e3-8a45-426d-89c5-a22c459a3a89" />
<img width="300" alt="Screenshot_2026-10-04-18-07-19-38_18d2b75f7e632145edbd616008505952 jpg" src="https://github.com/user-attachments/assets/79bac806-c7c1-47bf-a480-e11388d89d11" />
</p>
---

## Key Features

### 📷 Camera-Based Testing

Uses Android Camera2 to capture the physical test/reference card.

The capture pipeline includes:

- JPEG capture
- SHA-256 image hashing
- Image-quality checks
- ArUco marker detection
- Card geometry validation
- Perspective correction
- Region-of-interest extraction

### 🎨 Objective Colour Analysis

The captured reaction region is measured rather than relying solely on manual visual interpretation.

The pipeline performs:

```text
Captured Image
      ↓
Reference Card Detection
      ↓
ArUco Geometry
      ↓
Perspective Rectification
      ↓
ROI Extraction
      ↓
Colour Measurement
      ↓
RGB → XYZ → CIE Lab
      ↓
CIEDE2000
      ↓
Analysis Result
```
<p align="center">
<img width="250" alt="Screenshot_2026-10-04-18-08-32-87_18d2b75f7e632145edbd616008505952 jpg" src="https://github.com/user-attachments/assets/c8e03861-520b-4410-8a37-89a3cb3f0a4e" />
<img width="250" alt="Screenshot_2026-10-04-18-08-47-14_18d2b75f7e632145edbd616008505952 jpg" src="https://github.com/user-attachments/assets/d0a6ce04-4dea-49d7-973e-dcd99b886854" />
<img width="250" alt="Screenshot_2026-10-04-18-08-39-24_18d2b75f7e632145edbd616008505952 jpg" src="https://github.com/user-attachments/assets/334938d7-e1b4-48ad-b9d5-107a4921e9df" />
</p>
