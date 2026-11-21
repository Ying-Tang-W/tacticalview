package tacticalview.core.retarget;

/**
 * 与引擎无关的人形姿态输入（弧度，glTF 角色空间：+Y 向上、面朝 +Z；
 * pitch 为绕 +X 的旋转，yaw 为绕 +Y 的旋转）。
 * Fabric 适配层负责把原版实体的 ModelPart 角度换算成这里的值（含符号约定）。
 */
public final class HumanoidPose {
    public float bodyYaw;
    public float headYaw;
    public float headPitch;
    public float rightArmPitch;
    public float leftArmPitch;
    public float rightLegPitch;
    public float leftLegPitch;
    /** 0..1，下蹲程度，驱动脊柱前倾。 */
    public float crouch;
}
