#version 330
#extension GL_ARB_separate_shader_objects : require

layout(std140) uniform RearOverlayConfig {
    vec4 Rect;
    vec4 Params;
    vec4 BorderColor;
};

layout(location = 0) out vec2 localPixel;

const int CORNERS[6] = int[6](0, 1, 2, 2, 1, 3);

void main() {
  int c = CORNERS[gl_VertexIndex];
  vec2 pixel = mix(Rect.xy, Rect.zw, vec2(c & 1, c >> 1));
  gl_Position = vec4(pixel / Params.zw * 2.0 - 1.0, 0.0, 1.0);
  localPixel = pixel - Rect.xy;
}
