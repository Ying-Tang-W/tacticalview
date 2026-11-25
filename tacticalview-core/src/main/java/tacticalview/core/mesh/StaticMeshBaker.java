package tacticalview.core.mesh;

import java.util.ArrayList;
import java.util.List;
import tacticalview.core.gltf.GltfModel;
import tacticalview.core.math.Mat4;

/**
 * 把 GLB 里的静态网格烘焙成一份可直接渲染的数据：
 * 节点层级变换烘进顶点、法线用逆转置变换、没有切线时按 UV 生成。
 * 只合并"使用同一个材质"的图元；include 为 null 时所有带网格的节点都参与，
 * 否则只取名字包含任一 include 子串的节点（用来挑掉模型里重复的网格）。
 */
public final class StaticMeshBaker {
    private StaticMeshBaker() {}

    public static StaticMesh bake(GltfModel model, List<String> include) {
        List<float[]> pos = new ArrayList<>();
        List<float[]> nor = new ArrayList<>();
        List<float[]> uv = new ArrayList<>();
        List<int[]> idx = new ArrayList<>();
        int material = -2;
        int total = 0;
        for (int ni = 0; ni < model.nodes.size(); ni++) {
            GltfModel.Node node = model.nodes.get(ni);
            if (node.mesh < 0 || !matches(node.name, include)) {
                continue;
            }
            Mat4 world = worldMatrix(model, ni);
            Mat4 inv = world.inverseAffine();
            for (GltfModel.Primitive p : model.meshes.get(node.mesh).primitives) {
                if (p.indices.length == 0) {
                    continue;
                }
                if (material == -2) {
                    material = p.material;
                } else if (material != p.material) {
                    model.warnings.add("StaticMeshBaker: skipped a primitive with a different material (" + p.material + ")");
                    continue;
                }
                int n = p.vertexCount();
                float[] pp = new float[n * 3];
                float[] nn = new float[n * 3];
                for (int i = 0; i < n; i++) {
                    world.transformPoint(p.positions[i * 3], p.positions[i * 3 + 1], p.positions[i * 3 + 2], pp, i * 3);
                    float nx = 0, ny = 1, nz = 0;
                    if (p.normals != null) {
                        nx = p.normals[i * 3];
                        ny = p.normals[i * 3 + 1];
                        nz = p.normals[i * 3 + 2];
                    }
                    // 法线矩阵 = (M^-1)^T
                    float ox = inv.m[0] * nx + inv.m[1] * ny + inv.m[2] * nz;
                    float oy = inv.m[4] * nx + inv.m[5] * ny + inv.m[6] * nz;
                    float oz = inv.m[8] * nx + inv.m[9] * ny + inv.m[10] * nz;
                    float len = (float) Math.sqrt(ox * ox + oy * oy + oz * oz);
                    if (len < 1e-8f) {
                        ox = 0; oy = 1; oz = 0; len = 1;
                    }
                    nn[i * 3] = ox / len;
                    nn[i * 3 + 1] = oy / len;
                    nn[i * 3 + 2] = oz / len;
                }
                float[] uu = p.uvs != null ? p.uvs : new float[n * 2];
                pos.add(pp);
                nor.add(nn);
                uv.add(uu);
                // 偏移索引
                int[] ii = new int[p.indices.length];
                for (int i = 0; i < ii.length; i++) {
                    ii[i] = p.indices[i] + total;
                }
                idx.add(ii);
                total += n;
            }
        }
        if (total == 0) {
            throw new IllegalArgumentException("No static mesh found" + (include != null ? " for " + include : ""));
        }
        float[] P = concat(pos, total * 3);
        float[] N = concat(nor, total * 3);
        float[] U = concat(uv, total * 2);
        int idxLen = 0;
        for (int[] a : idx) idxLen += a.length;
        int[] I = new int[idxLen];
        int o = 0;
        for (int[] a : idx) {
            System.arraycopy(a, 0, I, o, a.length);
            o += a.length;
        }
        float[] T = tangents(P, N, U, I);
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < total; i++) {
            for (int k = 0; k < 3; k++) {
                b[k] = Math.min(b[k], P[i * 3 + k]);
                b[k + 3] = Math.max(b[k + 3], P[i * 3 + k]);
            }
        }
        return new StaticMesh(P, N, U, T, I, b, material == -2 ? -1 : material);
    }

    private static boolean matches(String name, List<String> include) {
        if (include == null || include.isEmpty()) {
            return true;
        }
        for (String s : include) {
            if (name != null && name.contains(s)) {
                return true;
            }
        }
        return false;
    }

    static Mat4 worldMatrix(GltfModel model, int node) {
        Mat4 m = new Mat4();
        int cur = node;
        int guard = 0;
        while (cur >= 0 && guard++ < 10_000) {
            GltfModel.Node n = model.nodes.get(cur);
            m = Mat4.fromTRS(n.t, 0, n.r, 0, n.s, 0).mul(m);
            cur = n.parent;
        }
        return m;
    }

    private static float[] concat(List<float[]> parts, int len) {
        float[] out = new float[len];
        int o = 0;
        for (float[] a : parts) {
            System.arraycopy(a, 0, out, o, a.length);
            o += a.length;
        }
        return out;
    }

    /**
     * 逐三角形累加切线/副切线，再对法线做 Gram-Schmidt。
     * 约定与 glTF 一致：法线贴图 +Y 朝上，UV 的 V 向下，所以计算时用 1-v。
     * w = 手性，shader 里 bitangent = cross(N, T) * w。
     */
    static float[] tangents(float[] P, float[] N, float[] U, int[] I) {
        int n = P.length / 3;
        float[] tan = new float[n * 3];
        float[] bit = new float[n * 3];
        for (int t = 0; t + 2 < I.length; t += 3) {
            int a = I[t], b = I[t + 1], c = I[t + 2];
            float e1x = P[b * 3] - P[a * 3], e1y = P[b * 3 + 1] - P[a * 3 + 1], e1z = P[b * 3 + 2] - P[a * 3 + 2];
            float e2x = P[c * 3] - P[a * 3], e2y = P[c * 3 + 1] - P[a * 3 + 1], e2z = P[c * 3 + 2] - P[a * 3 + 2];
            float du1 = U[b * 2] - U[a * 2], dv1 = -(U[b * 2 + 1] - U[a * 2 + 1]);
            float du2 = U[c * 2] - U[a * 2], dv2 = -(U[c * 2 + 1] - U[a * 2 + 1]);
            float det = du1 * dv2 - du2 * dv1;
            if (Math.abs(det) < 1e-12f) {
                continue;
            }
            float r = 1f / det;
            float tx = (e1x * dv2 - e2x * dv1) * r, ty = (e1y * dv2 - e2y * dv1) * r, tz = (e1z * dv2 - e2z * dv1) * r;
            float bx = (e2x * du1 - e1x * du2) * r, by = (e2y * du1 - e1y * du2) * r, bz = (e2z * du1 - e1z * du2) * r;
            for (int v : new int[] {a, b, c}) {
                tan[v * 3] += tx; tan[v * 3 + 1] += ty; tan[v * 3 + 2] += tz;
                bit[v * 3] += bx; bit[v * 3 + 1] += by; bit[v * 3 + 2] += bz;
            }
        }
        float[] out = new float[n * 4];
        for (int i = 0; i < n; i++) {
            float nx = N[i * 3], ny = N[i * 3 + 1], nz = N[i * 3 + 2];
            float tx = tan[i * 3], ty = tan[i * 3 + 1], tz = tan[i * 3 + 2];
            float d = nx * tx + ny * ty + nz * tz;
            tx -= nx * d; ty -= ny * d; tz -= nz * d;
            float len = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (len < 1e-8f) {
                // 退化：随便选一个与法线垂直的方向
                if (Math.abs(nx) < 0.9f) { tx = 0; ty = -nz; tz = ny; } else { tx = nz; ty = 0; tz = -nx; }
                len = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
            }
            tx /= len; ty /= len; tz /= len;
            float cx = ny * tz - nz * ty, cy = nz * tx - nx * tz, cz = nx * ty - ny * tx;
            float w = (cx * bit[i * 3] + cy * bit[i * 3 + 1] + cz * bit[i * 3 + 2]) < 0 ? -1f : 1f;
            out[i * 4] = tx; out[i * 4 + 1] = ty; out[i * 4 + 2] = tz; out[i * 4 + 3] = w;
        }
        return out;
    }
}
