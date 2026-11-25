package tacticalview.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tacticalview.core.gltf.GltfLoader;
import tacticalview.core.gltf.GltfModel;
import tacticalview.core.mesh.StaticMesh;
import tacticalview.core.mesh.StaticMeshBaker;

/** java -cp out tacticalview.core.StaticMeshCheck [model.glb]；不带参数时只跑合成数据检查。 */
public final class StaticMeshCheck {
    static int checks;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            System.err.println("FAIL: " + what);
            System.exit(1);
        }
    }

    public static void main(String[] args) throws Exception {
        // 合成：一个单位直角三角形，节点平移 (1,2,3)、绕 Y 转 90°、缩放 2
        GltfModel m = new GltfModel();
        GltfModel.Primitive p = new GltfModel.Primitive();
        p.positions = new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0};
        p.normals = new float[] {0, 0, 1, 0, 0, 1, 0, 0, 1};
        p.uvs = new float[] {0, 1, 1, 1, 0, 0}; // v 向下：y 增大对应 v 减小
        p.indices = new int[] {0, 1, 2};
        GltfModel.Mesh mesh = new GltfModel.Mesh();
        mesh.primitives.add(p);
        m.meshes.add(mesh);
        GltfModel.Node n = new GltfModel.Node();
        n.name = "blade";
        n.mesh = 0;
        n.t = new float[] {1, 2, 3};
        float h = (float) Math.sqrt(0.5);
        n.r = new float[] {0, h, 0, h};
        n.s = new float[] {2, 2, 2};
        m.nodes.add(n);
        StaticMesh s = StaticMeshBaker.bake(m, null);
        // 绕 Y 转 90°：x 轴 -> -z 轴，z 轴 -> +x 轴
        check(close(s.positions[3], 1f) && close(s.positions[5], 3f - 2f), "vertex1 baked (1,2,1)");
        check(close(s.normals[0], 1f) && close(s.normals[2], 0f), "normal rotated to +X");
        float tl = (float) Math.sqrt(s.tangents[0] * s.tangents[0] + s.tangents[1] * s.tangents[1] + s.tangents[2] * s.tangents[2]);
        check(close(tl, 1f), "tangent unit length");
        float dot = s.tangents[0] * s.normals[0] + s.tangents[1] * s.normals[1] + s.tangents[2] * s.normals[2];
        check(close(dot, 0f), "tangent perpendicular to normal");
        check(StaticMeshBaker.bake(m, List.of("bla")).triangleCount() == 1, "include filter matches");
        boolean threw = false;
        try { StaticMeshBaker.bake(m, List.of("zzz")); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "include filter with no match throws");
        check(s.interleave().length == 3 * StaticMesh.FLOATS_PER_VERTEX, "interleave size");

        if (args.length > 0) {
            GltfModel real = GltfLoader.loadGlb(Files.readAllBytes(Path.of(args[0])));
            StaticMesh r = StaticMeshBaker.bake(real, List.of("obj_model_01"));
            check(r.triangleCount() == 26658, "real model triangles");
            for (float v : r.tangents) check(!Float.isNaN(v), "tangent not NaN");
            float[] b = r.bounds;
            System.out.printf("real bounds: x[%.3f,%.3f] y[%.3f,%.3f] z[%.3f,%.3f] mat=%d%n", b[0], b[3], b[1], b[4], b[2], b[5], r.material);
        }
        System.out.println("StaticMeshCheck OK (" + checks + " checks)");
    }

    static boolean close(float a, float b) {
        return Math.abs(a - b) < 1e-4f;
    }
}
