#version 450

layout(location = 0) in  vec3 fragNormal;
layout(location = 1) in  vec4 fragColor;
layout(location = 0) out vec4 outColor;

void main() {
    // Simple Phong: single directional light from above-right
    vec3 lightDir = normalize(vec3(1.0, 2.0, 1.0));
    float diffuse = max(dot(fragNormal, lightDir), 0.0);
    float ambient = 0.25;
    float light   = ambient + diffuse * 0.75;
    outColor = vec4(fragColor.rgb * light, fragColor.a);
}
