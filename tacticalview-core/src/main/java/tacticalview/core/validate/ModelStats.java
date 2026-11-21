package tacticalview.core.validate;

import tacticalview.core.gltf.GltfModel;

/** 调试面板用的面数/资源统计。 */
public record ModelStats(int meshes, int primitives, int vertices, int triangles,
                         int joints, int materials, int images, int animations) {

    public static ModelStats of(GltfModel m) {
        int prims = 0, verts = 0, tris = 0;
        for (GltfModel.Mesh mesh : m.meshes) {
            for (GltfModel.Primitive p : mesh.primitives) {
                prims++;
                verts += p.vertexCount();
                tris += p.triangleCount();
            }
        }
        int joints = 0;
        for (GltfModel.Skin s : m.skins) {
            joints += s.joints.length;
        }
        return new ModelStats(m.meshes.size(), prims, verts, tris, joints,
                m.materials.size(), m.images.size(), m.animations.size());
    }

    @Override
    public String toString() {
        return "meshes=" + meshes + " primitives=" + primitives + " vertices=" + vertices
                + " triangles=" + triangles + " joints=" + joints + " materials=" + materials
                + " images=" + images + " animations=" + animations;
    }
}
