package tacticalview.core.retarget;

import java.util.Locale;

/** 骨骼/节点名规范化：去掉 Mixamo 前缀与分隔符并转小写，便于跨导出工具匹配。 */
public final class BoneNames {
    private BoneNames() {}

    /** "mixamorig:LeftHand" / "mixamorig_LeftHand" / "Left_Hand" 都会得到 "lefthand"。 */
    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String n = name.toLowerCase(Locale.ROOT);
        int colon = n.lastIndexOf(':');
        if (colon >= 0) {
            n = n.substring(colon + 1);
        } else if (n.startsWith("mixamorig")) {
            n = n.substring("mixamorig".length());
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
