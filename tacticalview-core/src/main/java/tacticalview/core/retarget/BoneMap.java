package tacticalview.core.retarget;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import tacticalview.core.gltf.GltfModel;

/** 人形槽位 -> 模型节点下标。默认按 Mixamo 命名自动匹配，也可手动覆盖。 */
public final class BoneMap {
    private final Map<Slot, Integer> map = new EnumMap<>(Slot.class);

    public static BoneMap fromModel(GltfModel model) {
        BoneMap bm = new BoneMap();
        for (int i = 0; i < model.nodes.size(); i++) {
            String n = BoneNames.normalize(model.nodes.get(i).name);
            for (Slot s : Slot.values()) {
                if (s.key.equals(n) && !bm.map.containsKey(s)) {
                    bm.map.put(s, i);
                }
            }
        }
        return bm;
    }

    /** 手动覆盖，用于非 Mixamo 命名的骨架。 */
    public void set(Slot slot, int nodeIndex) {
        map.put(slot, nodeIndex);
    }

    /** 未映射返回 -1。 */
    public int get(Slot slot) {
        Integer v = map.get(slot);
        return v == null ? -1 : v;
    }

    public List<Slot> missing() {
        List<Slot> out = new ArrayList<>();
        for (Slot s : Slot.values()) {
            if (!map.containsKey(s)) {
                out.add(s);
            }
        }
        return out;
    }
}
