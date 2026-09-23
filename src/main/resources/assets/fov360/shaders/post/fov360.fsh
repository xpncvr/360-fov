#version 330
#extension GL_ARB_separate_shader_objects : require

#define M_PI 3.14159265

uniform sampler2D Face0Sampler;
uniform sampler2D Face1Sampler;
uniform sampler2D Face2Sampler;
uniform sampler2D Face3Sampler;
uniform sampler2D Face4Sampler;
uniform sampler2D Face5Sampler;

layout(std140) uniform PaniniConfig {
    mat4 CoordFrames[6];
    vec4 Params;
    vec4 OutSize;
    vec4 Scales;
    vec4 Scales2;
    vec4 FaceEnabled[2];
};

#define fovx (Params.x)
#define aspect (Params.y)
#define pitch (Params.z)

#define outlineMode (Scales2.y > 0.5)

const int textureCount = 6;
const float TEX_FOV = 90.0;
const bool DEBUG_FACES = false;
bool splitRear = false;

vec4 background() {
  return outlineMode ? vec4(0.0, 0.0, 0.0, 0.0) : vec4(0.0, 0.0, 0.0, 1.0);
}

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

vec3 latlon_to_ray(float lat, float lon) {
  return vec3(
    sin(lon)*cos(lat),
    sin(lat),
    cos(lon)*cos(lat)
  );
}

vec3 standard_inverse(vec2 lenscoord) {
  float x = lenscoord.x;
  float y = lenscoord.y;
  float r = length(lenscoord);
  float theta = atan(r);
  float s = sin(theta);
  return vec3(x/r*s, y/r*s, cos(theta));
}
vec3 standard_ray(vec2 lenscoord) {
  return standard_inverse(lenscoord * Scales.x);
}

vec3 panini_inverse(vec2 lenscoord) {
  float x = lenscoord.x;
  float y = lenscoord.y;
  float d = 1.0;
  float k = x*x/((d+1.0)*(d+1.0));
  float dscr = k*k*d*d - (k+1.0)*(k*d*d-1.0);
  float clon = (-k*d+sqrt(dscr))/(k+1.0);
  float S = (d+1.0)/(d+clon);
  float lon = atan(x,S*clon);
  float lat = atan(y,S);
  return latlon_to_ray(lat, lon);
}
vec3 panini_ray(vec2 lenscoord) {
  return panini_inverse(lenscoord * Scales.y);
}

vec3 fisheye_inverse(vec2 lenscoord) {
  float x = lenscoord.x;
  float y = lenscoord.y;
  float r = length(lenscoord);
  float theta = r;
  float s = sin(theta);
  return vec3(x/r*s, y/r*s, cos(theta));
}
vec3 fisheye_ray(vec2 lenscoord) {
  return fisheye_inverse(lenscoord * Scales2.z);
}

vec3 mercator_inverse(vec2 lenscoord) {
  float lon = lenscoord.x;
  float lat = atan(sinh(lenscoord.y));
  return latlon_to_ray(lat, lon);
}
vec3 mercator_ray(vec2 lenscoord) {
  return mercator_inverse(lenscoord * Scales.w);
}

vec3 equirect_inverse(vec2 lenscoord) {
  if (abs(lenscoord.y) > M_PI/2.0) {
    return vec3(0.0, 0.0, 0.0);
  }
  float lon = lenscoord.x;
  float lat = lenscoord.y;
  return latlon_to_ray(lat, lon);
}
vec3 equirect_ray(vec2 lenscoord) {
  return equirect_inverse(lenscoord * Scales2.x);
}

vec3 stereographic_inverse(vec2 lenscoord) {
  float x = lenscoord.x;
  float y = lenscoord.y;
  float angleScale = 0.5;
  float r = length(lenscoord);
  float theta = atan(r)/angleScale;
  float s = sin(theta);
  return vec3(x/r*s, y/r*s, cos(theta));
}
vec3 stereographic_ray(vec2 lenscoord) {
  return stereographic_inverse(lenscoord * Scales.z);
}

vec3 hybrid_stereo_ray(vec2 c) {
  return mix(panini_ray(c), stereographic_ray(c), abs(pitch) / 90.0);
}

vec4 rubix_color(vec2 uv, int i) {
  if (!DEBUG_FACES) {
    return vec4(0.0, 0.0, 0.0, 0.0);
  }
  float numCells = 10.0;
  float cellSize = 4.0;
  float padSize = 1.0;

  float blockSize = padSize + cellSize;
  float numUnits = numCells * blockSize + padSize;

  bool onGrid = (
    mod(uv.x * numUnits, blockSize) < padSize ||
    mod(uv.y * numUnits, blockSize) < padSize
  );

  vec3 hue;
  switch (i) {
    case 0: hue = vec3(1.0, 1.0, 1.0); break;
    case 1: hue = vec3(0.0, 0.0, 1.0); break;
    case 2: hue = vec3(1.0, 0.0, 0.0); break;
    case 3: hue = vec3(0.0, 1.0, 0.0); break;
    case 4: hue = vec3(1.0, 1.0, 0.0); break;
    case 5: hue = vec3(0.0, 1.0, 1.0); break;
  }
  return onGrid ? vec4(0.0, 0.0, 0.0, 0.0) : vec4(hue, 0.3);
}

vec4 texuv_color(int i, vec2 uv) {
  vec4 color;
  switch (i) {
    case 0: color = texture(Face0Sampler, uv); break;
    case 1: color = texture(Face1Sampler, uv); break;
    case 2: color = texture(Face2Sampler, uv); break;
    case 3: color = texture(Face3Sampler, uv); break;
    case 4: color = texture(Face4Sampler, uv); break;
    case 5: color = texture(Face5Sampler, uv); break;
  }
  if (outlineMode) {
    return color;
  }
  vec4 rubix = rubix_color(uv, i);
  float a = rubix.a;
  return vec4((1.0-a)*color.rgb + a*rubix.rgb, 1.0);
}

vec3 frame_forward(mat3 coordFrame) {
  return vec3(
    -coordFrame[0].z,
    -coordFrame[1].z,
    -coordFrame[2].z
  );
}

bool face_on(int i) {
  return FaceEnabled[i >> 2][i & 3] > 0.5;
}

int ray_to_texture_index(vec3 ray) {
  int index = -1;
  float maxd = -2.0;
  for (int i=0; i<textureCount; i++) {
    if (!face_on(i)) {
      continue;
    }
    float d = dot(ray, frame_forward(mat3(CoordFrames[i])));
    if (d > maxd) {
      maxd = d;
      index = i;
    }
  }
  return index;
}

vec4 ray_to_color(vec3 ray) {
  int i = ray_to_texture_index(ray);
  if (i < 0) {
    return background();
  }
  vec3 ray2 = mat3(CoordFrames[i]) * ray;
  const float d = 0.5;
  vec2 uv = vec2(
    -ray2.x / ray2.z * d + 0.5,
    -ray2.y / ray2.z * d + 0.5
  );
  uv = clamp(uv, 0.0, 1.0);
  return texuv_color(i, uv);
}

vec2 tex_to_screen(vec2 tex) {
  return (tex - vec2(0.5, 0.5)) * vec2(2.0, 2.0/aspect);
}

vec3 screen_to_ray(vec2 c) {
  vec3 ray;
  if (fovx < 90.0) {
    ray = standard_ray(c);
  } else if (fovx < 160.0) {
    float linear = (fovx - 90.0)/ 70.0;
    float parabola = 1.0-(linear-1.0)*(linear-1.0);
    ray = mix(standard_ray(c), hybrid_stereo_ray(c), parabola);
  } else if (fovx < 220.0) {
    float linear = (fovx - 160.0)/ 60.0;
    float parabola = 1.0-(linear-1.0)*(linear-1.0);
    ray = mix(hybrid_stereo_ray(c), fisheye_ray(c), parabola);
  } else if (fovx < 300.0) {
    float linear = (fovx - 220.0)/ 80.0;
    float parabola = 1.0-(linear-1.0)*(linear-1.0);
    ray = mix(fisheye_ray(c), mercator_ray(c), parabola);
  } else if (fovx < 340.0) {
    ray = mercator_ray(c);
  } else if (fovx < 360.0) {
    ray = mix(mercator_ray(c), equirect_ray(c), (fovx - 340.0)/20.0);
  } else {
    ray = equirect_ray(c);
  }
  ray.z *= -1.0;
  if (splitRear) {
    ray.x = -ray.x;
    ray.z = -ray.z;
  }
  return ray;
}

vec4 screen_color(vec2 screen) {
  vec3 ray = screen_to_ray(screen);
  if (length(ray) == 0.0) {
    return background();
  }
  return ray_to_color(ray);
}

vec4 screen_color_antialias(vec2 screen, vec2 pixelOffset[4]) {
  int n = int(Params.w + 0.5);
  if (n <= 1) {
    return screen_color(screen);
  }
  vec4 c = vec4(0.0, 0.0, 0.0, 0.0);
  for (int j = 0; j < 4; j++) {
    if (j >= n) {
      break;
    }
    c += screen_color(screen + pixelOffset[j]);
  }
  return c / float(n);
}

void main(void) {
  vec2 tex = texCoord;
  if (OutSize.z > 0.5) {
    splitRear = (tex.x >= 0.5) != (OutSize.w > 0.5);
    tex = vec2(fract(tex.x * 2.0), tex.y);
  }
  vec2 screen = tex_to_screen(tex);

  if (abs(screen.x) > 1.0) {
    fragColor = background();
    return;
  }

  vec2 sx = vec2(2.0/OutSize.x, 0.0);
  vec2 sy = vec2(0.0, (2.0/aspect)/OutSize.y);
  vec2 pixelOffset[4];
  pixelOffset[0] =  0.125*sx + 0.375*sy;
  pixelOffset[1] =  0.375*sx - 0.125*sy;
  pixelOffset[2] = -0.125*sx - 0.375*sy;
  pixelOffset[3] = -0.375*sx + 0.125*sy;

  fragColor = screen_color_antialias(screen, pixelOffset);
}
