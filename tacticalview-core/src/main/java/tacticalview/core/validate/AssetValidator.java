package tacticalview.core.validate;

import java.util.ArrayList;
import java.util.List;
import tacticalview.core.gltf.GltfModel;
import tacticalview.core.retarget.BoneMap;
import tacticalview.core.retarget.Slot;

/** 按资产标准（面数预算、PBR 贴图齐全、2K 贴图、Mixamo 骨骼）检查一个模型，供内容包作者自检。 */
public final class AssetValidator {
    private AssetValidator() {}

    public static final int DEFAULT_TRIANGLE_BUDGET = 40_000;
    public static final int MAX_TEXTURE_SIZE = 2048;

    public enum Severity { ERROR, WARN }

    public record Issue(Severity severity, String message) {
        @Override
        public String toString() {
            return severity + ": " + message;
        }
    }

    public static List<Issue> validate(GltfModel m, int triangleBudget) {
        List<Issue> out = new ArrayList<>();
        ModelStats st = ModelStats.of(m);
        if (st.triangles() > triangleBudget) {
            out.add(new Issue(Severity.ERROR,
                    "triangle count " + st.triangles() + " exceeds budget " + triangleBudget));
        }
        for (GltfModel.Skin s : m.skins) {
            if (s.joints.length > 255) {
                out.add(new Issue(Severity.WARN, "skin '" + s.name + "' has " + s.joints.length + " joints (> 255)"));
            }
        }
        for (GltfModel.Mesh mesh : m.meshes) {
            for (GltfModel.Primitive p : mesh.primitives) {
                if (p.normals == null) {
                    out.add(new Issue(Severity.WARN, "mesh '" + mesh.name + "' has no normals"));
                }
                if (p.joints != null && p.weights == null) {
                    out.add(new Issue(Severity.ERROR, "mesh '" + mesh.name + "' has JOINTS_0 but no WEIGHTS_0"));
                }
            }
        }
        for (GltfModel.Material mat : m.materials) {
            List<String> missing = new ArrayList<>();
            if (mat.baseColorImage < 0) missing.add("albedo");
            if (mat.normalImage < 0) missing.add("normal");
            if (mat.metallicRoughnessImage < 0) missing.add("metallic/roughness");
            if (mat.occlusionImage < 0) missing.add("AO");
            if (!missing.isEmpty()) {
                out.add(new Issue(Severity.WARN, "material '" + mat.name + "' lacks textures: " + String.join(", ", missing)));
            }
        }
        for (int i = 0; i < m.images.size(); i++) {
            GltfModel.Image img = m.images.get(i);
            int[] wh = ImageInfo.dimensions(img.data);
            String label = "image #" + i + (img.name.isEmpty() ? "" : " '" + img.name + "'");
            if (wh == null) {
                out.add(new Issue(Severity.WARN, label + ": size unknown (unsupported format or missing data)"));
                continue;
            }
            if (Math.max(wh[0], wh[1]) > MAX_TEXTURE_SIZE) {
                out.add(new Issue(Severity.WARN, label + ": " + wh[0] + "x" + wh[1] + " exceeds 2K (" + MAX_TEXTURE_SIZE + ")"));
            }
            if (Integer.bitCount(wh[0]) != 1 || Integer.bitCount(wh[1]) != 1) {
                out.add(new Issue(Severity.WARN, label + ": " + wh[0] + "x" + wh[1] + " is not power-of-two"));
            }
        }
        if (!m.skins.isEmpty()) {
            List<Slot> missing = BoneMap.fromModel(m).missing();
            if (!missing.isEmpty()) {
                out.add(new Issue(Severity.WARN, "humanoid bones not found (Mixamo naming expected): " + missing));
            }
        }
        for (String w : m.warnings) {
            out.add(new Issue(Severity.WARN, w));
        }
        return out;
    }
}
