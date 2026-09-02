# 360 FOV

Minecraft mod that extends the FOV range from **110° to 400°** using a combination of projection techniques.

**[Download on Modrinth!](https://modrinth.com/project/360-fov)**

## Preview

<img width="720" height="405" alt="360 FOV preview" src="https://github.com/user-attachments/assets/45b6451e-feab-4d4f-87f5-b6c3046f80fb" />

## How it works

The mod renders the surrounding world into a six-face cubemap and reprojects it in a single shader pass.

Different projections are blended together as the FOV increases:

|         FOV | Projection                                                    |
| ----------: | ------------------------------------------------------------- |
|     `< 90°` | Rectilinear                                                   |
|  `90°–160°` | Rectilinear → Panini/stereographic hybrid                     |
| `160°–220°` | Panini/stereographic hybrid → Fisheye (azimuthal equidistant) |
| `220°–300°` | Fisheye → Mercator                                            |
| `300°–340°` | Mercator                                                      |
| `340°–360°` | Mercator → Equirectangular                                    |
|     `360°+` | Equirectangular                                               |

At **360°**, the entire sphere is visible. Above 360°, the view wraps around.

## Usage

The mod uses the vanilla FOV slider. Set the FOV in **Video Settings** and the projection will update automatically.

## Configuration

The configuration file is created at `config/fov360.json` on first launch.

Changes require a restart.

| Key                    | Default | Description                                                                                                                                       |
| ---------------------- | :-----: | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| `splitScreen`          | `false` | Splits the window into forward and rear halves, each using the full FOV. Intended for two monitors at 180° to create a seamless full-sphere view. |
| `invertSplitScreen`    | `false` | Swaps the forward/rear halves and the side on which the GUI is displayed.                                                                         |
| `faceSizeCap`          |  `2048` | Maximum resolution of each cubemap face. Higher values improve quality at high FOVs but increase VRAM usage. Range: `256–4096`.                   |
| `lowResTopBottomFaces` | `false` | Uses lower resolution for the top and bottom capture faces, except for the face currently being viewed.                                           |
| `antialiasSamples`     |   `4`   | Supersampling used during reprojection. Valid values: `1`, `2`, or `4`.                                                                           |

## Known issues

* **FOV mismatch:** Minecraft's FOV slider represents vertical FOV, while the projection shader uses horizontal FOV. The resulting angular range therefore varies with the aspect ratio and projection being used.
* **Performance:** The surround view can require up to six world render passes per frame instead of one.
* **Rendering edge cases:** Billboard and particle rendering, shader mod compatibility, and some corner geometry have not been fully tested.

## Attribution

Projection math is ported from [Flex FOV](https://github.com/shaunlebron/flex-fov) by Shaun LeBron, which is itself a fork of [Render360](https://github.com/18107/MC-Render360) by 18107.

## License

[GNU Affero General Public License v3.0 or later](https://www.gnu.org/licenses/agpl-3.0.html)
