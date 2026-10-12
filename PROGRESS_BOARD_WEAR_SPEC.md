# Progress Board Wear OS Tile & Standalone App Architecture

## 1. Overview
A Wear OS Tile and full standalone App allowing rapid wrist monitoring of the live test and runner progress from `https://progress-board.dansemur.workers.dev/watch` and `https://app-progress-board.pages.dev`.

---

## 2. Component Structure

### A. Wear OS Glanceable Tile (`ProgressBoardTileService`)
- **Access**: 1 swipe right from the Ironman watch face.
- **Protocol**: Jetpack ProtoLayout + `androidx.wear.tiles` (Material3 DSL).
- **Layout**:
  - **Header**: Project Tag (`BSPLINE` / `DDCS`) + Live Dot.
  - **Center Hero**: Circular Progress Ring (or horizontal track) displaying **86%** with test count badge (`18/21 pass`).
  - **Active Run**: Active runner name (e.g. `bspline-matrix-parallel`) + ETA countdown (`-4m`).
  - **Action Target**: Tap anywhere opens the full Progress Board App on the watch.

### B. Standalone Wear OS App (`ProgressBoardActivity`)
- **UI Framework**: Wear OS Compose / Scrollable Cards with rotary crown support.
- **Screen 1: Active Test Runs (Live Workers)**
  - Real-time status cards for active seats (`Seat A · Worker`, `Advisor`, `Reviewer`).
  - Visual progress bars, pass/fail indicators, waiting timers.
- **Screen 2: Project Health**
  - DDCS Studio current release (`V2026.10.11.13`), tickets in progress.
  - B-spline status and active branches.
- **Screen 3: Shared PC Status & Screenshot Glance**
  - Displays `SHARED PC` status.
  - Tap to view a mini zoomable preview of the latest CAD/drawing snapshot on the watch screen.
