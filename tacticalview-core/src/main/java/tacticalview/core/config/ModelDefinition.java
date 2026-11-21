package tacticalview.core.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tacticalview.core.json.Json;
import tacticalview.core.lod.LodSelector;
import tacticalview.core.socket.Socket;
import tacticalview.core.socket.SocketSet;

/**
 * 内容包里一个模型的配置文件（*.model.json）。核心库只负责解析与校验，不关心具体资产。
 *
 * 示例见 tacticalview-content 的 assets 目录。
 */
public final class ModelDefinition {
    public String id;
    /** 最精细 LOD 的模型路径（相对内容包资源根）；若给了 lods 则以 lods 为准。 */
    public String model;
    public float scale = 1f;
    public float[] offset = {0, 0, 0};
    public float yawOffsetDeg = 0f;
    public final List<LodEntry> lods = new ArrayList<>();
    public final SocketSet sockets = new SocketSet();
    /** 逻辑动作名 -> 模型内动画片段名，例如 "reload" -> "Rifle_Reload"。 */
    public final Map<String, String> animations = new LinkedHashMap<>();
    public FirstPerson firstPerson;

    public static final class LodEntry {
        public final String model;
        /** 该级别最大使用距离；最后一级可省略（视为无穷远）。 */
        public final float maxDistance;

        public LodEntry(String model, float maxDistance) {
            this.model = model;
            this.maxDistance = maxDistance;
        }
    }

    public static final class FirstPerson {
        public float[] offset = {0, 0, 0};
        public float[] rotationDeg = {0, 0, 0};
        public float scale = 1f;
        public float[] aimOffset = {0, 0, 0};
    }

    public static ModelDefinition parse(String json) {
        Map<String, Object> root = Json.obj(Json.parse(json));
        if (root == null) {
            throw new IllegalArgumentException("Model definition must be a JSON object");
        }
        ModelDefinition d = new ModelDefinition();
        d.id = Json.str(root, "id", null);
        if (d.id == null || d.id.isEmpty()) {
            throw new IllegalArgumentException("Model definition needs an 'id'");
        }
        d.model = Json.str(root, "model", null);
        d.scale = Json.f(root, "scale", 1f);
        d.offset = Json.floats(root, "offset", d.offset);
        d.yawOffsetDeg = Json.f(root, "yawOffsetDeg", 0f);

        List<Object> lods = Json.arr(root.get("lods"));
        if (lods != null) {
            for (int i = 0; i < lods.size(); i++) {
                Map<String, Object> lj = Json.obj(lods.get(i));
                String path = Json.str(lj, "model", null);
                if (path == null) {
                    throw new IllegalArgumentException("lods[" + i + "] needs a 'model'");
                }
                boolean last = i == lods.size() - 1;
                float dist = Json.f(lj, "maxDistance", Float.POSITIVE_INFINITY);
                if (!last && Float.isInfinite(dist)) {
                    throw new IllegalArgumentException("lods[" + i + "] needs 'maxDistance' (only the last level may omit it)");
                }
                d.lods.add(new LodEntry(path, dist));
            }
        }
        if (d.model == null && d.lods.isEmpty()) {
            throw new IllegalArgumentException("Model definition needs 'model' or 'lods'");
        }

        List<Object> socks = Json.arr(root.get("sockets"));
        if (socks != null) {
            for (Object o : socks) {
                Map<String, Object> sj = Json.obj(o);
                String name = Json.str(sj, "name", null);
                String bone = Json.str(sj, "bone", null);
                if (name == null || bone == null) {
                    throw new IllegalArgumentException("Socket needs 'name' and 'bone'");
                }
                d.sockets.add(new Socket(name, bone,
                        Json.floats(sj, "position", new float[] {0, 0, 0}),
                        Json.floats(sj, "rotation", new float[] {0, 0, 0, 1}),
                        Json.floats(sj, "scale", new float[] {1, 1, 1}),
                        Json.str(sj, "effect", null)));
            }
        }

        Map<String, Object> anims = Json.obj(root.get("animations"));
        if (anims != null) {
            for (Map.Entry<String, Object> e : anims.entrySet()) {
                if (e.getValue() instanceof String) {
                    d.animations.put(e.getKey(), (String) e.getValue());
                }
            }
        }

        Map<String, Object> fp = Json.obj(root.get("firstPerson"));
        if (fp != null) {
            FirstPerson f = new FirstPerson();
            f.offset = Json.floats(fp, "offset", f.offset);
            f.rotationDeg = Json.floats(fp, "rotationDeg", f.rotationDeg);
            f.scale = Json.f(fp, "scale", 1f);
            f.aimOffset = Json.floats(fp, "aimOffset", f.aimOffset);
            d.firstPerson = f;
        }
        return d;
    }

    /** 由 lods 生成距离选择器；没有 lods 时返回只有一级的选择器。 */
    public LodSelector lodSelector(float hysteresis) {
        if (lods.size() <= 1) {
            return new LodSelector(new float[0], hysteresis);
        }
        float[] d = new float[lods.size() - 1];
        for (int i = 0; i < d.length; i++) {
            d[i] = lods.get(i).maxDistance;
        }
        return new LodSelector(d, hysteresis);
    }
}
