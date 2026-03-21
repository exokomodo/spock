#version 450

// Quad UVs and positions — hardcoded, no vertex buffer
// Two triangles = 6 vertices for a unit quad centered at origin in NDC

layout(push_constant) uniform PushConstants {
    vec2  translation;  // NDC position
    float rotation;
    float padding;
    vec2  scale;        // width, height in NDC
    vec2  padding2;
    vec4  color;        // tint
} pc;

layout(location = 0) out vec2 fragUV;
layout(location = 1) out vec4 fragColor;

void main() {
    // unit quad vertices (NDC, two triangles)
    const vec2 positions[6] = vec2[](
        vec2(-0.5, -0.5), vec2( 0.5, -0.5), vec2( 0.5,  0.5),
        vec2(-0.5, -0.5), vec2( 0.5,  0.5), vec2(-0.5,  0.5)
    );
    const vec2 uvs[6] = vec2[](
        vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(1.0, 1.0),
        vec2(0.0, 0.0), vec2(1.0, 1.0), vec2(0.0, 1.0)
    );

    vec2 pos = positions[gl_VertexIndex];
    // apply scale
    pos *= pc.scale;
    // apply rotation
    float c = cos(pc.rotation);
    float s = sin(pc.rotation);
    pos = vec2(pos.x * c - pos.y * s, pos.x * s + pos.y * c);
    // apply translation
    pos += pc.translation;

    gl_Position = vec4(pos, 0.0, 1.0);
    fragUV = uvs[gl_VertexIndex];
    fragColor = pc.color;
}
