#version 450

// Per-vertex: 2D position + UV coordinates
layout(location = 0) in vec2 inPos;
layout(location = 1) in vec2 inUV;

// Push constants (std430, 48 bytes):
//   offset  0: vec2  translation  (8 bytes)
//   offset  8: float rotation     (4 bytes)
//   offset 12: float _pad0        (4 bytes) — align vec2 to 8-byte boundary
//   offset 16: vec2  scale        (8 bytes)
//   offset 24: vec2  _pad1        (8 bytes) — align vec4 to 16-byte boundary
//   offset 32: vec4  color        (16 bytes)
//   total: 48 bytes
layout(push_constant) uniform PushConstants {
    vec2  translation;
    float rotation;
    float _pad0;
    vec2  scale;
    vec2  _pad1;
    vec4  color;
} pc;

layout(location = 0) out vec2 fragUV;
layout(location = 1) out vec4 fragColor;

void main() {
    float c = cos(pc.rotation);
    float s = sin(pc.rotation);

    // Scale then rotate then translate
    vec2 scaled  = inPos * pc.scale;
    vec2 rotated = vec2(
        scaled.x * c - scaled.y * s,
        scaled.x * s + scaled.y * c
    );
    vec2 pos = rotated + pc.translation;

    gl_Position = vec4(pos, 0.0, 1.0);
    fragUV      = inUV;
    fragColor   = pc.color;
}
