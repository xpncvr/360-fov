# 360 FOV (Up to 400° FOV)

This mod blends various projections to allow FOV to be set up to 400° and still maintain high visual quality.

**[Download it from Modrinth here](https://modrinth.com/project/360-fov)**

## Preview

<img width="720" height="405" alt="demo" src="https://github.com/user-attachments/assets/45b6451e-feab-4d4f-87f5-b6c3046f80fb" />

110° to 400°

## What it does

The effect is always on while a world is loaded. The mod captures the world as a six-face cubemap and reprojects it in a
single shader pass. As you raise the FOV slider (now 400 instead of vanilla's 110) the
shader switches through a chain of projections:

| FOV range | Projection |
| --- | --- |
| below 90 | Rectilinear, vanilla projection |
| 90 to 160 | Cross-fade rectilinear into the Panini/stereographic hybrid |
| 160 to 220 | Cross-fade the hybrid into fisheye (azimuthal equidistant) |
| 220 to 300 | Cross-fade fisheye into Mercator |
| 300 to 340 | Pure Mercator |
| 340 to 360 | Linear cross-fade Mercator into equirectangular |
| 360 and above | Equirectangular, the full sphere is shown at 360, past it the view wraps |

## Usage

Raise the FOV slider in vanilla video settings and the projection follows it automatically.

## Config

`config/fov360.json` is created on first launch and read once per session (edits need a
restart):

| Key | Default | Effect |
| --- | --- | --- |
| `splitScreen` | `false` | Splits the window into a forward half and a rear half, each at the full slider FOV. Meant for two monitors at slider 180, giving a seamless full-sphere view. |
| `invertSplitScreen` | `false` | Swaps which half is forward/rear (and where the GUI lives) in split screen. |
| `faceSizeCap` | `2048` | Upper bound in pixels on the six capture faces. Raising it sharpens high FOVs at a steep VRAM cost. Clamped to 256-4096. |
| `lowResTopBottomFaces` | `false` | Halves resolution on the up/down capture faces (except the one you're looking at) to save performance |
| `antialiasSamples` | `4` | Supersample tap count in the reprojection pass: 1, 2, or 4. |

> Note: \
> **FOV mismatch:** Vanilla's slider sets a vertical FOV while the projection shader works in horizontal FOV, so the same number maps to a different angular extent depending on your aspect ratio and which projection stage you're in. \
> **Performance:** Rendering a full surround view can take up to six world passes per frame instead of one (can decrease FPS) \
> **Bugs:** Several rendering edge cases (billboard/particle handling, shader mod compatibility, untested corner geometry, etc.) haven't been verified in-game yet


## Attribution

The projection math is ported from [Flex FOV](https://github.com/shaunlebron/flex-fov) by Shaun
LeBron, itself a fork of [Render360](https://github.com/18107/MC-Render360) by 18107.

Licensed under the GNU Affero General Public License v3.0 or later
