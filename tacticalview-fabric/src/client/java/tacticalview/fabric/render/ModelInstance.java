package tacticalview.fabric.render;

import tacticalview.core.anim.Animator;
import tacticalview.core.anim.Pose;
import tacticalview.core.anim.Skinner;
import tacticalview.core.gltf.GltfLoader;
import tacticalview.core.gltf.GltfModel;
import tacticalview.core.math.Mat4;

/**
 * 一个 GLB 模型的运行时实例：采样动画 -> CPU 蒙皮 -> 平面着色 -> {@link GeometryRenderState}。
 *
 * 不依赖 Minecraft，因此可以在没有游戏的环境里单元测试。
 * 目前的渲染管线只有顶点色（没有贴图/法线/PBR），所以这里用材质基础色加一盏固定方向光做简单明暗，
 * 之后换成 PBR 着色器管线时，只需要把"顶点色"换成"法线+UV"即可。
 *
 * 性能说明：每次 build 都重新分配数组并在 CPU 上蒙皮，面数大（3~4 万）时应改成 GPU 蒙皮。
 * 这是第一版，目的是验证整条链路。
 */
public final class ModelInstance {
    /** 世界空间的光照方向（指向光源），已归一化。 */
    private static final float LX, LY, LZ;

    static {
        float x = 0.35f, y = 0.85f, z = 0.40f;
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        LX = x / len;
        LY = y / len;
        LZ = z / len;
    }

    private final GltfModel model;
    private final Animator animator;
    private final GltfModel.Animation animation;
    private final int triangleCount;

    private ModelInstance(GltfModel model, GltfModel.Animation animation) {
        this.model = model;
        this.animator = new Animator(model);
        this.animation = animation;
        int tris = 0;
        for (GltfModel.Node n : model.nodes) {
            if (n.mesh >= 0) {
                for (GltfModel.Primitive p : model.meshes.get(n.mesh).primitives) {
                    tris += p.triangleCount();
                }
            }
        }
        this.triangleCount = tris;
    }

    /** @param preferredAnimation 优先播放的动画名，找不到就用第一个；模型没有动画则保持休息姿态。 */
    public static ModelInstance load(byte[] glb, String preferredAnimation) {
        GltfModel model = GltfLoader.loadGlb(glb);
        GltfModel.Animation anim = model.findAnimation(preferredAnimation);
        if (anim == null && !model.animations.isEmpty()) {
            anim = model.animations.get(0);
        }
        return new ModelInstance(model, anim);
    }

    public GltfModel model() {
        return model;
    }

    public int triangleCount() {
        return triangleCount;
    }

    public String animationName() {
        return animation == null ? null : animation.name;
    }

    /**
     * @param ax,ay,az  模型脚底中心的世界坐标
     * @param yawRad    绕 Y 轴旋转（弧度）。模型默认面朝 +Z；yaw = atan2(目标方向.x, 目标方向.z) 可让它面朝该方向
     * @param scale     整体缩放
     * @param timeSeconds 动画时间（循环播放）
     */
    public GeometryRenderState build(double ax, double ay, double az, float yawRad, float scale, float timeSeconds) {
        Pose pose = animator.restPose();
        if (animation != null) {
            animator.sample(animation, timeSeconds, true, pose);
        }
        Mat4[] world = animator.computeWorld(pose);

        float cos = (float) Math.cos(yawRad);
        float sin = (float) Math.sin(yawRad);

        float[] positions = new float[triangleCount * 9];
        int[] colors = new int[triangleCount * 3];
        int[] indices = new int[triangleCount * 3];

        float[] tmp = new float[3];
        float[] tri = new float[9];
        int t = 0;
        for (int ni = 0; ni < model.nodes.size(); ni++) {
            GltfModel.Node node = model.nodes.get(ni);
            if (node.mesh < 0) {
                continue;
            }
            Mat4 meshWorld = world[ni];
            Mat4[] jointMats = node.skin >= 0
                    ? animator.jointMatrices(model.skins.get(node.skin), ni, world) : null;

            for (GltfModel.Primitive p : model.meshes.get(node.mesh).primitives) {
                float[] src = p.positions;
                if (jointMats != null) {
                    src = new float[p.positions.length];
                    Skinner.skinPositions(p, jointMats, src);
                }
                float[] base = baseColor(p);
                for (int i = 0; i + 2 < p.indices.length; i += 3) {
                    for (int k = 0; k < 3; k++) {
                        int v = p.indices[i + k] * 3;
                        // jointMatrices 给出的是网格节点局部空间，这里统一乘回网格节点的世界矩阵
                        meshWorld.transformPoint(src[v], src[v + 1], src[v + 2], tmp, 0);
                        float x = tmp[0] * scale, y = tmp[1] * scale, z = tmp[2] * scale;
                        // 绕 Y 旋转
                        tri[k * 3] = x * cos + z * sin;
                        tri[k * 3 + 1] = y;
                        tri[k * 3 + 2] = -x * sin + z * cos;
                    }
                    System.arraycopy(tri, 0, positions, t * 9, 9);
                    colors[t * 3] = colors[t * 3 + 1] = colors[t * 3 + 2] = shade(tri, base);
                    indices[t * 3] = t * 3;
                    indices[t * 3 + 1] = t * 3 + 1;
                    indices[t * 3 + 2] = t * 3 + 2;
                    t++;
                }
            }
        }
        return new GeometryRenderState(ax, ay, az, positions, colors, indices);
    }

    private float[] baseColor(GltfModel.Primitive p) {
        if (p.material >= 0 && p.material < model.materials.size()) {
            return model.materials.get(p.material).baseColor;
        }
        return new float[] {0.8f, 0.8f, 0.8f, 1f};
    }

    /** 平面着色：面法线（由顶点顺序算出）与固定方向光的点积。返回 0xAARRGGBB。 */
    private static int shade(float[] tri, float[] base) {
        float ux = tri[3] - tri[0], uy = tri[4] - tri[1], uz = tri[5] - tri[2];
        float vx = tri[6] - tri[0], vy = tri[7] - tri[1], vz = tri[8] - tri[2];
        float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        float light = 0.6f;
        if (len > 1e-8f) {
            float d = (nx * LX + ny * LY + nz * LZ) / len;
            light = 0.45f + 0.55f * Math.max(0f, d);
        }
        int r = clamp255(base[0] * light);
        int g = clamp255(base[1] * light);
        int b = clamp255(base[2] * light);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int clamp255(float v) {
        int i = Math.round(v * 255f);
        return i < 0 ? 0 : Math.min(i, 255);
    }
}
