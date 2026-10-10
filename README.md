# 360 FOV

Minecraft mod that extends the FOV range from **110° to 400°** using a combination of projection techniques.

**[Download on Modrinth!](https://modrinth.com/project/360-fov)**

## Preview

<img width="720" height="405" alt="360 FOV preview" src="https://github.com/user-attachments/assets/45b6451e-feab-4d4f-87f5-b6c3046f80fb" />

## How it works

The mod renders the surrounding world into a six-face cubemap and reprojects it in a single shader pass.

Different projections are blended together as the FOV increases. By default:

|         FOV | Projection                                                    |
| ----------: | ------------------------------------------------------------- |
|    `< 120°` | Rectilinear                                                   |
| `120°–160°` | Rectilinear → Panini/stereographic hybrid                     |
| `160°–220°` | Panini/stereographic hybrid → Fisheye (azimuthal equidistant) |
| `220°–300°` | Fisheye → Mercator                                            |
| `300°–340°` | Mercator                                                      |
| `340°–360°` | Mercator → Equirectangular                                    |
|     `360°+` | Equirectangular                                               |

At **360°**, the entire sphere is visible. Above 360°, the view wraps around.

The thresholds can be changed in game under **360 FOV Settings → FOV Thresholds...**. The bar covers every FOV from 30° to 400°. Each point on the bar sets the projection used from that horizontal FOV upward, and the view blends into the next point's projection between two points. Click the bar to add a point (up to 12), drag to move one, and right-click to remove one. The available projections are Rectilinear, Panini/Stereographic (the pitch-dependent hybrid above), Panini, Stereographic, Fisheye, Mercator and Equirectangular. Blends can be Ease Out, Linear or Smooth. Rectilinear stops widening above 170°, and Panini and Stereographic stop widening above 330°.

Wherever the view is pure Rectilinear, the mod hands the frame back to Minecraft's normal renderer at the matching FOV, so normal FOVs cost no extra performance. Every other FOV is drawn from the cubemap.

## Usage

The mod uses the vanilla FOV slider. Set the FOV in **Video Settings** and the projection will update automatically.

## Configuration

Settings can be changed in game from **Video Settings → 360 FOV Settings...**, or with the **Open 360 FOV Settings** keybind (unbound by default, under **Controls → Key Binds → 360 FOV**). The keybind also works when Sodium replaces the Video Settings screen.

Settings are saved to `config/fov360.json`, which is created on first launch and can also be edited by hand. Edits made to the file while the game is running require a restart. **Reset All** in the settings screen restores every default.

| Key                    | Default | Description                                                                                                                                       |
| ---------------------- | :-----: | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| `splitScreen`          | `false` | Splits the window into forward and rear halves, each using the full FOV. Intended for two monitors at 180° to create a seamless full-sphere view. |
| `invertSplitScreen`    | `false` | Swaps the forward/rear halves and the side on which the GUI is displayed.                                                                         |
| `faceSizeCap`          |  `2048` | Maximum resolution of each cubemap face. Higher values improve quality at high FOVs but increase VRAM usage. Range: `256–4096`.                   |
| `lowResTopBottomFaces` | `false` | Uses lower resolution for the top and bottom capture faces, except for the face currently being viewed.                                           |
| `antialiasSamples`     |   `4`   | Supersampling used during reprojection. Valid values: `1`, `2`, or `4`.                                                                           |
| `projectionPoints`     |   see above   | List of `{ "fov", "projection", "blend" }` points, `fov` from `30` to `400`. `projection` is one of `RECTILINEAR`, `PANINI_STEREOGRAPHIC`, `PANINI`, `STEREOGRAPHIC`, `FISHEYE`, `MERCATOR`, `EQUIRECTANGULAR`. `blend` is `EASE_OUT`, `LINEAR` or `SMOOTH` and controls the blend from the previous point. |

## Known issues

* **FOV mismatch:** Minecraft's FOV slider represents vertical FOV, while the projection shader uses horizontal FOV. The resulting angular range therefore varies with the aspect ratio and projection being used.
* **Performance:** The surround view can require up to six world render passes per frame instead of one.
* **Rendering edge cases:** Billboard and particle rendering, shader mod compatibility, and some corner geometry have not been fully tested.

## Attribution

Projection math is ported from [Flex FOV](https://github.com/shaunlebron/flex-fov) by Shaun LeBron, which is itself a fork of [Render360](https://github.com/18107/MC-Render360) by 18107.

## License

[GNU Affero General Public License v3.0 or later](https://www.gnu.org/licenses/agpl-3.0.html)
