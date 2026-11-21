package tacticalview.core.anim;

import tacticalview.core.gltf.GltfModel;
import tacticalview.core.math.Mat4;
import tacticalview.core.math.Quat;

/** 骨骼动画采样、混合与世界矩阵/关节矩阵计算。无状态（除绑定的模型外），可多线程共用。 */
public final class Animator {
    private final GltfModel model;

    public Animator(GltfModel model) {
        this.model = model;
    }

    public Pose restPose() {
        return Pose.rest(model);
    }

    /**
     * 把动画在 time 秒处的值写入 pose（只覆盖动画里出现的通道，其余保持原值）。
     * 调用前通常先 pose.reset(model)。
     */
    public void sample(GltfModel.Animation anim, float time, boolean loop, Pose pose) {
        float t = time;
        if (anim.duration > 0) {
            if (loop) {
                t = t % anim.duration;
                if (t < 0) {
                    t += anim.duration;
                }
            } else {
                t = Math.max(0f, Math.min(t, anim.duration));
            }
        } else {
            t = 0f;
        }
        for (GltfModel.Channel c : anim.channels) {
            if (c.node < 0 || c.node >= model.nodes.size()) {
                continue;
            }
            sampleChannel(c, t, pose);
        }
    }

    private void sampleChannel(GltfModel.Channel c, float t, Pose pose) {
        GltfModel.Sampler sm = c.sampler;
        boolean rot = c.path.equals("rotation");
        int comps = rot ? 4 : 3;
        float[] out = new float[comps];
        float[] times = sm.times;
        int n = times.length;
        if (n == 0) {
            return;
        }
        boolean cubic = sm.interpolation.equals("CUBICSPLINE");
        boolean step = sm.interpolation.equals("STEP");

        if (n == 1 || t <= times[0]) {
            read(sm.values, valueIndex(0, cubic, comps), comps, out);
        } else if (t >= times[n - 1]) {
            read(sm.values, valueIndex(n - 1, cubic, comps), comps, out);
        } else {
            int i = segment(times, t);
            float t0 = times[i], t1 = times[i + 1];
            float dt = t1 - t0;
            float u = dt <= 0 ? 0 : (t - t0) / dt;
            if (step) {
                read(sm.values, valueIndex(i, false, comps), comps, out);
            } else if (cubic) {
                float[] p0 = new float[comps], m0 = new float[comps], p1 = new float[comps], m1 = new float[comps];
                read(sm.values, (i * 3 + 1) * comps, comps, p0);
                read(sm.values, (i * 3 + 2) * comps, comps, m0);
                read(sm.values, ((i + 1) * 3 + 1) * comps, comps, p1);
                read(sm.values, ((i + 1) * 3) * comps, comps, m1);
                float u2 = u * u, u3 = u2 * u;
                float h00 = 2 * u3 - 3 * u2 + 1, h10 = u3 - 2 * u2 + u, h01 = -2 * u3 + 3 * u2, h11 = u3 - u2;
                for (int k = 0; k < comps; k++) {
                    out[k] = h00 * p0[k] + h10 * dt * m0[k] + h01 * p1[k] + h11 * dt * m1[k];
                }
                if (rot) {
                    Quat.normalize(out, 0);
                }
            } else {
                float[] a = new float[comps], b = new float[comps];
                read(sm.values, i * comps, comps, a);
                read(sm.values, (i + 1) * comps, comps, b);
                if (rot) {
                    Quat.slerp(a, 0, b, 0, u, out, 0);
                } else {
                    for (int k = 0; k < comps; k++) {
                        out[k] = a[k] + (b[k] - a[k]) * u;
                    }
                }
            }
        }
        float[] dst;
        int off;
        switch (c.path) {
            case "translation": dst = pose.t; off = c.node * 3; break;
            case "rotation": dst = pose.r; off = c.node * 4; break;
            default: dst = pose.s; off = c.node * 3; break;
        }
        System.arraycopy(out, 0, dst, off, comps);
    }

    private static int valueIndex(int key, boolean cubic, int comps) {
        return (cubic ? key * 3 + 1 : key) * comps;
    }

    private static void read(float[] src, int off, int n, float[] dst) {
        System.arraycopy(src, off, dst, 0, n);
    }

    /** 返回 i 使得 times[i] <= t < times[i+1]。 */
    private static int segment(float[] times, float t) {
        int lo = 0, hi = times.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (times[mid] <= t) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** out = a 与 b 按权重 w(0..1) 混合；位移/缩放线性，旋转球面插值。out 可与 a 或 b 相同。 */
    public static void blend(Pose a, Pose b, float w, Pose out) {
        for (int i = 0; i < a.t.length; i++) {
            out.t[i] = a.t[i] + (b.t[i] - a.t[i]) * w;
        }
        for (int i = 0; i < a.s.length; i++) {
            out.s[i] = a.s[i] + (b.s[i] - a.s[i]) * w;
        }
        for (int i = 0; i < a.r.length; i += 4) {
            Quat.slerp(a.r, i, b.r, i, w, out.r, i);
        }
    }

    /** 所有节点的世界矩阵（按节点下标）。 */
    public Mat4[] computeWorld(Pose pose) {
        int n = model.nodes.size();
        Mat4[] w = new Mat4[n];
        for (int i = 0; i < n; i++) {
            resolve(i, pose, w);
        }
        return w;
    }

    private Mat4 resolve(int i, Pose pose, Mat4[] w) {
        if (w[i] != null) {
            return w[i];
        }
        Mat4 local = Mat4.fromTRS(pose.t, i * 3, pose.r, i * 4, pose.s, i * 3);
        int par = model.nodes.get(i).parent;
        w[i] = par < 0 ? local : resolve(par, pose, w).mul(local);
        return w[i];
    }

    /**
     * 蒙皮关节矩阵：inverse(meshNodeWorld) * jointWorld * inverseBind。
     * 结果可直接作为 GPU 蒙皮着色器的 uniform 数组。
     */
    public Mat4[] jointMatrices(GltfModel.Skin skin, int meshNode, Mat4[] world) {
        Mat4 invMesh = meshNode >= 0 ? world[meshNode].inverseAffine() : new Mat4();
        Mat4[] out = new Mat4[skin.joints.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = invMesh.mul(world[skin.joints[i]]).mul(new Mat4(skin.inverseBind[i]));
        }
        return out;
    }
}
