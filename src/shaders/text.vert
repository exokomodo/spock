#version 450

// Per-glyph push constants — no vertex buffer, quad generated in shader
layout(push_constant) uniform PushConstants {
    vec2 pos;      // NDC bottom-left of glyph quad
    vec2 size;     // NDC width/height of glyph quad
    vec2 uv_pos;   // UV top-left in atlas
    vec2 uv_size;  // UV width/height in atlas
    vec4 color;    // tint
} pc;

layout(location = 0) out vec2 fragUV;
layout(location = 1) out vec4 fragColor;

void main() {
    // Two triangles (CCW): TL TR BR  TL BR BL
    const vec2 offsets[6] = vec2[](
        vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(1.0, 1.0),
        vec2(0.0, 0.0), vec2(1.0, 1.0), vec2(0.0, 1.0)
    );

    vec2 off = offsets[gl_VertexIndex];
    gl_Position = vec4(pc.pos + off * pc.size, 0.0, 1.0);
    fragUV      = pc.uv_pos + off * pc.uv_size;
    fragColor   = pc.color;
}
