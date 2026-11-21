package tacticalview.core.retarget;

import tacticalview.core.anim.Animator;
import tacticalview.core.anim.Pose;
import tacticalview.core.gltf.GltfModel;
import tacticalview.core.math.Mat4;
import tacticalview.core.math.Quat;

/**
 * 把 HumanoidPose（原版实体姿态）叠加到干员模型的 Pose 上。
 *
 * 关键点：Mixamo 各骨骼的局部坐标轴并不统一，所以不能直接把 "手臂前后摆" 当作局部 X 轴旋转。
 * 这里在角色空间里定义增量旋转 Dw，再换算到骨骼局部：local' = (PW^-1 * Dw * PW) * local，
 * 其中 PW 是父骨骼当前的世界旋转。这样无论骨骼局部轴怎么朝向，摆动方向都在角色空间里保持一致。
 *
 * 已知简化：所有槽位的 PW 取自同一次世界矩阵计算（叠加前），父子同时被叠加时的二阶误差被忽略，
 * 对小到中等幅度的原版姿态足够。
 */
public final class Retargeter {
    private Retargeter() {}

    /** @return 实际被修改的骨骼数量（映射缺失或角度为 0 的不计）。 */
    public static int apply(Animator animator, GltfModel model, Pose pose, BoneMap map, HumanoidPose hp) {
        Mat4[] world = animator.computeWorld(pose);
        int n = 0;
        n += rotate(model, pose, world, map, Slot.HIPS, 0, 1, 0, hp.bodyYaw);
        n += rotate(model, pose, world, map, Slot.SPINE, 1, 0, 0, hp.crouch * 0.6f);
        n += rotate(model, pose, world, map, Slot.HEAD, 0, 1, 0, hp.headYaw);
        n += rotate(model, pose, world, map, Slot.HEAD, 1, 0, 0, hp.headPitch);
        n += rotate(model, pose, world, map, Slot.R_ARM, 1, 0, 0, hp.rightArmPitch);
        n += rotate(model, pose, world, map, Slot.L_ARM, 1, 0, 0, hp.leftArmPitch);
        n += rotate(model, pose, world, map, Slot.R_UPLEG, 1, 0, 0, hp.rightLegPitch);
        n += rotate(model, pose, world, map, Slot.L_UPLEG, 1, 0, 0, hp.leftLegPitch);
        return n;
    }

    private static int rotate(GltfModel model, Pose pose, Mat4[] world, BoneMap map, Slot slot,
                              float ax, float ay, float az, float angle) {
        if (Math.abs(angle) < 1e-6f) {
            return 0;
        }
        int node = map.get(slot);
        if (node < 0) {
            return 0;
        }
        int parent = model.nodes.get(node).parent;
        float[] pw = Quat.identity();
        if (parent >= 0) {
            float[] t = new float[3], s = new float[3];
            world[parent].decompose(t, pw, s);
        }
        float[] dw = Quat.fromAxisAngle(ax, ay, az, angle);
        float[] delta = Quat.mul(Quat.mul(Quat.conj(pw), dw), pw);
        float[] local = {pose.r[node * 4], pose.r[node * 4 + 1], pose.r[node * 4 + 2], pose.r[node * 4 + 3]};
        float[] res = Quat.mul(delta, local);
        Quat.normalize(res, 0);
        System.arraycopy(res, 0, pose.r, node * 4, 4);
        return 1;
    }
}
