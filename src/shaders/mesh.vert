#version 450

layout(location = 0) in vec3 inPos;
layout(location = 1) in vec3 inNormal;
layout(location = 2) in vec2 inUV;

// Push constants: model matrix (64 bytes) + VP matrix (64 bytes) + color (16 bytes) = 144 bytes
layout(push_constant) uniform PushConstants {
    mat4 model;
    mat4 vp;
    vec4 color;
} pc;

layout(location = 0) out vec3 fragNormal;
layout(location = 1) out vec4 fragColor;

void main() {
    vec4 worldPos = pc.model * vec4(inPos, 1.0);
    gl_Position   = pc.vp * worldPos;

    // Transform normal into world space (simplified — assumes uniform scale)
    fragNormal = normalize(mat3(pc.model) * inNormal);
    fragColor  = pc.color;
}
