#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D RearSampler;

layout(std140) uniform RearOverlayConfig {
    vec4 Rect;
    vec4 Params;
    vec4 BorderColor;
};

layout(location = 0) in vec2 localPixel;
layout(location = 0) out vec4 fragColor;

void main(void) {
  vec2 size = Rect.zw - Rect.xy;
  float border = Params.y;
  if (border > 0.0 && (any(lessThan(localPixel, vec2(border))) || any(greaterThanEqual(localPixel, size - border)))) {
    fragColor = BorderColor;
    return;
  }
  vec2 uv = localPixel / size;
  if (Params.x > 0.5) {
    uv.x = 1.0 - uv.x;
  }
  fragColor = vec4(texture(RearSampler, uv).rgb, 1.0);
}
