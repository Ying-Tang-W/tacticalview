package tacticalview.fabric.render;

/**
 * 一帧要画的三角形网格：不可变、线程安全，在"提取阶段"创建，"绘制阶段"只读。
 *
 * 之后 GLB 网格（tacticalview-core 的 GltfModel / Skinner 输出）会被转换成这个结构交给渲染器。
 *
 * 坐标约定：positions 是相对于 anchor 的局部坐标，anchor 是它在世界里的位置。
 * 数组由构造器复制一份，调用者不要修改返回的数组。
 */
public record GeometryRenderState(
        double anchorX, double anchorY, double anchorZ,
        /** xyz 三元组，长度 = 顶点数 * 3。 */
        float[] positions,
        /** 每个顶点一个 0xAARRGGBB。 */
        int[] colors,
        /** 三角形索引列表（逆时针为正面），长度是 3 的倍数。 */
        int[] indices) {

    public GeometryRenderState {
        if (positions.length % 3 != 0) {
            throw new IllegalArgumentException("positions length must be a multiple of 3");
        }
        int vertexCount = positions.length / 3;
        if (colors.length != vertexCount) {
            throw new IllegalArgumentException("colors length must equal vertex count");
        }
        if (indices.length % 3 != 0) {
            throw new IllegalArgumentException("indices length must be a multiple of 3");
        }
        for (int index : indices) {
            if (index < 0 || index >= vertexCount) {
                throw new IllegalArgumentException("index out of range: " + index);
            }
        }
        positions = positions.clone();
        colors = colors.clone();
        indices = indices.clone();
    }

    public int vertexCount() {
        return positions.length / 3;
    }

    public int triangleCount() {
        return indices.length / 3;
    }

    /** 底面中心在 anchor 的单位立方体，六个面六种颜色，方便确认朝向和绘制是否正确。 */
    public static GeometryRenderState coloredCube(double anchorX, double anchorY, double anchorZ) {
        final float h = 0.5f;
        // 每个面 4 个顶点（从外向内看逆时针），顺序与 Fabric 文档示例的 renderFilledBox 一致
        float[][] faces = {
                {-h, 0, h, h, 0, h, h, 1, h, -h, 1, h},      // +Z 前
                {h, 0, -h, -h, 0, -h, -h, 1, -h, h, 1, -h},  // -Z 后
                {-h, 0, -h, -h, 0, h, -h, 1, h, -h, 1, -h},  // -X 左
                {h, 0, h, h, 0, -h, h, 1, -h, h, 1, h},      // +X 右
                {-h, 1, h, h, 1, h, h, 1, -h, -h, 1, -h},    // +Y 顶
                {-h, 0, -h, h, 0, -h, h, 0, h, -h, 0, h},    // -Y 底
        };
        int[] faceColors = {
                0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFFFDD835, 0xFF8E24AA, 0xFFFB8C00
        };
        float[] positions = new float[6 * 4 * 3];
        int[] colors = new int[6 * 4];
        int[] indices = new int[6 * 6];
        for (int f = 0; f < 6; f++) {
            System.arraycopy(faces[f], 0, positions, f * 12, 12);
            for (int v = 0; v < 4; v++) {
                colors[f * 4 + v] = faceColors[f];
            }
            int base = f * 4;
            int i = f * 6;
            indices[i] = base;
            indices[i + 1] = base + 1;
            indices[i + 2] = base + 2;
            indices[i + 3] = base;
            indices[i + 4] = base + 2;
            indices[i + 5] = base + 3;
        }
        return new GeometryRenderState(anchorX, anchorY, anchorZ, positions, colors, indices);
    }
}
