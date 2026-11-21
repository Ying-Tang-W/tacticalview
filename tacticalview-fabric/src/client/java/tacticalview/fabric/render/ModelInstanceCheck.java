package tacticalview.fabric.render;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tacticalview.core.retarget.BoneMap;
import tacticalview.core.retarget.Slot;
import tacticalview.core.validate.AssetValidator;

/**
 * 不依赖 Minecraft 的自检：java -cp ... tacticalview.fabric.render.ModelInstanceCheck <glb>
 * 放在客户端源码集里只是为了和被测代码同包；游戏运行时不会调用它。
 */
public final class ModelInstanceCheck {
    private static int checks;

    private static void check(boolean ok, String msg) {
        checks++;
        if (!ok) {
            throw new AssertionError("FAILED: " + msg);
        }
    }

    public static void main(String[] args) throws Exception {
        byte[] glb = Files.readAllBytes(Path.of(args[0]));
        ModelInstance m = ModelInstance.load(glb, "Walk");

        check(m.triangleCount() == 216, "triangle count " + m.triangleCount());
        check("Walk".equals(m.animationName()), "animation " + m.animationName());
        check(BoneMap.fromModel(m.model()).missing().isEmpty(),
                "humanoid bones missing: " + BoneMap.fromModel(m.model()).missing());
        check(BoneMap.fromModel(m.model()).get(Slot.R_HAND) >= 0, "right hand bound");

        List<AssetValidator.Issue> issues = AssetValidator.validate(m.model(), AssetValidator.DEFAULT_TRIANGLE_BUDGET);
        check(issues.stream().noneMatch(i -> i.severity() == AssetValidator.Severity.ERROR), "validator errors: " + issues);

        GeometryRenderState rest = m.build(10, 64, -5, 0f, 1f, 0f);
        check(rest.triangleCount() == 216 && rest.vertexCount() == 648, "state counts");
        float[] b = bounds(rest);
        check(b[1] > -0.01f && b[1] < 0.02f, "feet at y=0, got " + b[1]);
        check(b[4] > 1.75f && b[4] < 1.9f, "height ~1.8, got " + b[4]);
        check(b[0] > -0.35f && b[3] < 0.35f, "width within +-0.35, got " + b[0] + ".." + b[3]);
        check(volume(rest) > 0.05f, "outward winding: signed volume " + volume(rest));

        // 走路动画：t=0.25 时左腿向前（脚底 z 增大），右腿向后
        GeometryRenderState a = m.build(0, 0, 0, 0f, 1f, 0.25f);
        GeometryRenderState c = m.build(0, 0, 0, 0f, 1f, 0.75f);
        check(!java.util.Arrays.equals(a.positions(), rest.positions()), "animation changes pose");
        check(!java.util.Arrays.equals(a.positions(), c.positions()), "t=0.25 differs from t=0.75");
        GeometryRenderState loop = m.build(0, 0, 0, 0f, 1f, 1.25f);
        check(java.util.Arrays.equals(a.positions(), loop.positions()), "animation loops (t=1.25 == t=0.25)");
        check(volume(a) > 0.05f, "walk pose keeps outward winding");

        // yaw：旋转 90° 后，原先朝 +Z 的点应转到 +X
        GeometryRenderState yaw = m.build(0, 0, 0, (float) (Math.PI / 2), 1f, 0f);
        float[] by = bounds(yaw);
        float wx = b[3] - b[0], wz = b[5] - b[2];
        float wxYaw = by[3] - by[0], wzYaw = by[5] - by[2];
        // 绕 Y 轴 +90°：x、z 方向的范围精确互换；原来朝前(+Z)的最远点转到 +X
        check(Math.abs(wxYaw - wz) < 1e-3f && Math.abs(wzYaw - wx) < 1e-3f,
                "yaw 90 deg swaps x/z extents: " + wx + "," + wz + " -> " + wxYaw + "," + wzYaw);
        check(Math.abs(by[3] - b[5]) < 1e-3f, "forward (+Z) maps to +X: " + by[3] + " vs " + b[5]);
        check(Math.abs(by[4] - b[4]) < 1e-4f, "yaw keeps height");
        check(wx > 0.4f && wz > 0.2f, "model has plausible footprint " + wx + " x " + wz);

        // 颜色：一部分三角形应比另一部分更亮（有明暗）
        int lo = 255, hi = 0;
        for (int col : rest.colors()) {
            int g = (col >> 8) & 0xFF;
            lo = Math.min(lo, g);
            hi = Math.max(hi, g);
        }
        check(hi - lo > 10, "shading varies, lo=" + lo + " hi=" + hi);

        System.out.println("ModelInstance OK (" + checks + " checks): " + m.model().meshes.size() + " mesh, "
                + m.triangleCount() + " triangles, height " + b[4] + ", warnings=" + issues.size());
    }

    private static float[] bounds(GeometryRenderState s) {
        float[] r = {1e9f, 1e9f, 1e9f, -1e9f, -1e9f, -1e9f};
        float[] p = s.positions();
        for (int i = 0; i < p.length; i += 3) {
            for (int k = 0; k < 3; k++) {
                r[k] = Math.min(r[k], p[i + k]);
                r[k + 3] = Math.max(r[k + 3], p[i + k]);
            }
        }
        return r;
    }

    /** 所有三角形的有向体积之和：闭合网格且法线朝外（逆时针）时为正。 */
    private static float volume(GeometryRenderState s) {
        float[] p = s.positions();
        double v = 0;
        for (int i = 0; i < p.length; i += 9) {
            double ax = p[i], ay = p[i + 1], az = p[i + 2];
            double bx = p[i + 3], by = p[i + 4], bz = p[i + 5];
            double cx = p[i + 6], cy = p[i + 7], cz = p[i + 8];
            v += ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx);
        }
        return (float) (v / 6.0);
    }
}
