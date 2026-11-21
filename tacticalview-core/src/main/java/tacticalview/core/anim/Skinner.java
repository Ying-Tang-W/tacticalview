package tacticalview.core.anim;

import tacticalview.core.gltf.GltfModel;
import tacticalview.core.math.Mat4;

/**
 * CPU 蒙皮（线性混合蒙皮 LBS）。主要用途：作为 GPU 着色器的参考实现、
 * 单元测试、以及远处 LOD 不想占用 GPU 蒙皮 uniform 时的降级方案。
 */
public final class Skinner {
    private Skinner() {}

    /** @param out 长度 >= positions.length 的数组，写入蒙皮后的顶点位置。 */
    public static void skinPositions(GltfModel.Primitive p, Mat4[] jointMats, float[] out) {
        int n = p.vertexCount();
        if (p.joints == null || p.weights == null) {
            System.arraycopy(p.positions, 0, out, 0, n * 3);
            return;
        }
        float[] tmp = new float[3];
        for (int v = 0; v < n; v++) {
            float x = p.positions[v * 3], y = p.positions[v * 3 + 1], z = p.positions[v * 3 + 2];
            float ox = 0, oy = 0, oz = 0, wsum = 0;
            for (int k = 0; k < 4; k++) {
                float w = p.weights[v * 4 + k];
                if (w <= 0f) {
                    continue;
                }
                jointMats[p.joints[v * 4 + k]].transformPoint(x, y, z, tmp, 0);
                ox += tmp[0] * w;
                oy += tmp[1] * w;
                oz += tmp[2] * w;
                wsum += w;
            }
            if (wsum <= 0f) {
                out[v * 3] = x;
                out[v * 3 + 1] = y;
                out[v * 3 + 2] = z;
            } else {
                out[v * 3] = ox;
                out[v * 3 + 1] = oy;
                out[v * 3 + 2] = oz;
            }
        }
    }
}
