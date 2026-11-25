#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec3 Normal;
layout(location = 2) in vec2 UV0;
layout(location = 3) in vec4 Tangent;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec2 texCoord0;
layout(location = 3) out vec3 viewPosition;
layout(location = 4) out vec3 viewNormal;
layout(location = 5) out vec4 viewTangent;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPos;

    sphericalVertexDistance = fog_spherical_distance(viewPos.xyz);
    cylindricalVertexDistance = fog_cylindrical_distance(viewPos.xyz);

    // 模型矩阵只含旋转 + 均匀缩放，所以法线可以直接用 mat3(ModelViewMat)。
    mat3 normalMat = mat3(ModelViewMat);
    viewPosition = viewPos.xyz;
    viewNormal = normalMat * Normal;
    viewTangent = vec4(normalMat * Tangent.xyz, Tangent.w);
    texCoord0 = UV0;
}
