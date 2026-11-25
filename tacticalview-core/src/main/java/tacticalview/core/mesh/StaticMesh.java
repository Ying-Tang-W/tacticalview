package tacticalview.core.mesh;

/**
 * 烘焙后的静态网格：节点变换已经写进顶点，没有骨骼，可以直接上传 GPU。
 * 所有数组按顶点平铺：positions/normals 每顶点 3 个，uvs 2 个，tangents 4 个（xyz + 手性 w）。
 */
public final class StaticMesh {
    public final float[] positions;
    public final float[] normals;
    public final float[] uvs;
    public final float[] tangents;
    public final int[] indices;
    /** 包围盒 minX,minY,minZ,maxX,maxY,maxZ。 */
    public final float[] bounds;
    /** 使用的材质下标（对应 GltfModel.materials），无材质为 -1。 */
    public final int material;

    public StaticMesh(float[] positions, float[] normals, float[] uvs, float[] tangents,
                      int[] indices, float[] bounds, int material) {
        this.positions = positions;
        this.normals = normals;
        this.uvs = uvs;
        this.tangents = tangents;
        this.indices = indices;
        this.bounds = bounds;
        this.material = material;
    }

    public int vertexCount() {
        return positions.length / 3;
    }

    public int triangleCount() {
        return indices.length / 3;
    }

    /** 交错写入：pos(3) normal(3) uv(2) tangent(4) = 12 个 float / 顶点。 */
    public static final int FLOATS_PER_VERTEX = 12;

    public float[] interleave() {
        int n = vertexCount();
        float[] out = new float[n * FLOATS_PER_VERTEX];
        for (int i = 0; i < n; i++) {
            int o = i * FLOATS_PER_VERTEX;
            System.arraycopy(positions, i * 3, out, o, 3);
            System.arraycopy(normals, i * 3, out, o + 3, 3);
            System.arraycopy(uvs, i * 2, out, o + 6, 2);
            System.arraycopy(tangents, i * 4, out, o + 8, 4);
        }
        return out;
    }
}
