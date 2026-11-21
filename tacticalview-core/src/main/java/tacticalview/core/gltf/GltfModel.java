package tacticalview.core.gltf;

import java.util.ArrayList;
import java.util.List;
import tacticalview.core.retarget.BoneNames;

/** 解析后的 glTF 2.0 模型（与 GPU 无关的纯数据）。 */
public final class GltfModel {
    public final List<Node> nodes = new ArrayList<>();
    public final List<Mesh> meshes = new ArrayList<>();
    public final List<Skin> skins = new ArrayList<>();
    public final List<Material> materials = new ArrayList<>();
    public final List<Image> images = new ArrayList<>();
    public final List<Animation> animations = new ArrayList<>();
    public final List<Integer> rootNodes = new ArrayList<>();
    /** 解析时遇到的非致命问题（例如跳过了非三角形图元）。 */
    public final List<String> warnings = new ArrayList<>();

    public static final class Node {
        public String name = "";
        public int parent = -1;
        public int[] children = new int[0];
        public int mesh = -1;
        public int skin = -1;
        public float[] t = {0, 0, 0};
        public float[] r = {0, 0, 0, 1};
        public float[] s = {1, 1, 1};
    }

    public static final class Mesh {
        public String name = "";
        public final List<Primitive> primitives = new ArrayList<>();
    }

    public static final class Primitive {
        public float[] positions = new float[0];
        public float[] normals;
        public float[] uvs;
        /** 每顶点 4 个关节索引（指向 Skin.joints 的下标）。 */
        public int[] joints;
        public float[] weights;
        public int[] indices = new int[0];
        public int material = -1;

        public int vertexCount() {
            return positions.length / 3;
        }

        public int triangleCount() {
            return indices.length / 3;
        }
    }

    public static final class Skin {
        public String name = "";
        /** 关节对应的节点下标。 */
        public int[] joints = new int[0];
        /** 每个关节一个 16 float 的逆绑定矩阵（列主序）。 */
        public float[][] inverseBind = new float[0][];
    }

    public static final class Material {
        public String name = "";
        public float[] baseColor = {1, 1, 1, 1};
        public float metallic = 1f;
        public float roughness = 1f;
        public float[] emissive = {0, 0, 0};
        /** 纹理对应的 Image 下标，-1 表示没有。 */
        public int baseColorImage = -1;
        public int normalImage = -1;
        public int metallicRoughnessImage = -1;
        public int occlusionImage = -1;
        public int emissiveImage = -1;
    }

    public static final class Image {
        public String name = "";
        public String mimeType;
        /** 编码后的图片字节（PNG/JPEG），无法获取时为 null。 */
        public byte[] data;
    }

    public static final class Animation {
        public String name = "";
        public final List<Channel> channels = new ArrayList<>();
        public float duration;
    }

    public static final class Channel {
        public int node;
        /** "translation" | "rotation" | "scale" */
        public String path;
        public Sampler sampler;
    }

    public static final class Sampler {
        public float[] times;
        public float[] values;
        /** "LINEAR" | "STEP" | "CUBICSPLINE" */
        public String interpolation = "LINEAR";
    }

    /** 按规范化名字查找节点，找不到返回 -1。 */
    public int findNode(String name) {
        String want = BoneNames.normalize(name);
        for (int i = 0; i < nodes.size(); i++) {
            if (BoneNames.normalize(nodes.get(i).name).equals(want)) {
                return i;
            }
        }
        return -1;
    }

    public Animation findAnimation(String name) {
        for (Animation a : animations) {
            if (a.name.equals(name)) {
                return a;
            }
        }
        return null;
    }
}
