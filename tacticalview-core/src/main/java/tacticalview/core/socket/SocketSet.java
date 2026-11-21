package tacticalview.core.socket;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tacticalview.core.gltf.GltfModel;
import tacticalview.core.math.Mat4;

/** 一个模型的全部挂点。先 bind(model) 解析骨骼下标，再每帧用世界矩阵求挂点世界变换。 */
public final class SocketSet {
    private final Map<String, Socket> sockets = new LinkedHashMap<>();

    public void add(Socket s) {
        sockets.put(s.name, s);
    }

    public Socket get(String name) {
        return sockets.get(name);
    }

    public Collection<Socket> all() {
        return sockets.values();
    }

    /** 把每个挂点绑定到模型节点；返回找不到骨骼的挂点名。 */
    public List<String> bind(GltfModel model) {
        List<String> unresolved = new ArrayList<>();
        for (Socket s : sockets.values()) {
            s.nodeIndex = model.findNode(s.bone);
            if (s.nodeIndex < 0) {
                unresolved.add(s.name);
            }
        }
        return unresolved;
    }

    /** 挂点在模型空间的变换：boneWorld * socketLocal。 */
    public Mat4 worldTransform(String name, Mat4[] nodeWorld) {
        Socket s = sockets.get(name);
        if (s == null) {
            throw new IllegalArgumentException("Unknown socket '" + name + "'");
        }
        if (s.nodeIndex < 0) {
            throw new IllegalStateException("Socket '" + name + "' is not bound to a bone");
        }
        Mat4 local = Mat4.fromTRS(s.position, 0, s.rotation, 0, s.scale, 0);
        return nodeWorld[s.nodeIndex].mul(local);
    }
}
