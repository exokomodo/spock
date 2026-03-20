#version 450

// Per-vertex input: 2D position
layout(location = 0) in vec2 inPos;

// Push constants: translation, rotation (radians), color
layout(push_constant) uniform PushConstants {
    vec2  translation;
    float rotation;
    vec4  color;
} pc;

layout(location = 0) out vec4 fragColor;

void main() {
    float c = cos(pc.rotation);
    float s = sin(pc.rotation);
    vec2 rotated = vec2(
        inPos.x * c - inPos.y * s,
        inPos.x * s + inPos.y * c
    );
    vec2 final = rotated + pc.translation;
    gl_Position = vec4(final, 0.0, 1.0);
    fragColor = pc.color;
}
