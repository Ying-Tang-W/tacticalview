package tacticalview.core.anim;

import tacticalview.core.gltf.GltfModel;

/** 每个节点的局部 TRS（扁平数组：t[n*3]、r[n*4]、s[n*3]）。 */
public final class Pose {
    public final float[] t;
    public final float[] r;
    public final float[] s;

    public Pose(int nodeCount) {
        t = new float[nodeCount * 3];
        r = new float[nodeCount * 4];
        s = new float[nodeCount * 3];
    }

    public static Pose rest(GltfModel model) {
        Pose p = new Pose(model.nodes.size());
        p.reset(model);
        return p;
    }

    public void reset(GltfModel model) {
        for (int i = 0; i < model.nodes.size(); i++) {
            GltfModel.Node n = model.nodes.get(i);
            System.arraycopy(n.t, 0, t, i * 3, 3);
            System.arraycopy(n.r, 0, r, i * 4, 4);
            System.arraycopy(n.s, 0, s, i * 3, 3);
        }
    }

    public Pose copy() {
        Pose p = new Pose(t.length / 3);
        System.arraycopy(t, 0, p.t, 0, t.length);
        System.arraycopy(r, 0, p.r, 0, r.length);
        System.arraycopy(s, 0, p.s, 0, s.length);
        return p;
    }
}
