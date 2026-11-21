package tacticalview.core.gltf;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import tacticalview.core.json.Json;
import tacticalview.core.math.Mat4;

/**
 * glTF 2.0 / GLB 解析器。支持：节点层级、网格(三角形图元)、蒙皮、PBR 材质引用、
 * 图片(bufferView / data URI / 外部文件)、动画(LINEAR/STEP/CUBICSPLINE)。
 * 不支持：稀疏访问器、morph target、非三角形图元（会写入 warnings 并跳过）。
 */
public final class GltfLoader {
    private GltfLoader() {}

    public static GltfModel loadGlb(byte[] data) {
        return loadGlb(data, null);
    }

    /** @param resolver 解析外部 uri 的回调（相对路径 -> 字节），可为 null。 */
    public static GltfModel loadGlb(byte[] data, Function<String, byte[]> resolver) {
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (data.length < 12 || bb.getInt(0) != 0x46546C67) {
            throw new IllegalArgumentException("Not a GLB file (bad magic)");
        }
        if (bb.getInt(4) != 2) {
            throw new IllegalArgumentException("Unsupported GLB version " + bb.getInt(4));
        }
        int total = Math.min(bb.getInt(8), data.length);
        int off = 12;
        String json = null;
        byte[] bin = null;
        while (off + 8 <= total) {
            int len = bb.getInt(off);
            int type = bb.getInt(off + 4);
            off += 8;
            if (off + len > data.length) {
                throw new IllegalArgumentException("Truncated GLB chunk");
            }
            if (type == 0x4E4F534A) {
                json = new String(data, off, len, StandardCharsets.UTF_8);
            } else if (type == 0x004E4942 && bin == null) {
                bin = Arrays.copyOfRange(data, off, off + len);
            }
            off += len;
        }
        if (json == null) {
            throw new IllegalArgumentException("GLB has no JSON chunk");
        }
        return parse(json, bin, resolver);
    }

    public static GltfModel loadGltf(String json, Function<String, byte[]> resolver) {
        return parse(json, null, resolver);
    }

    // ------------------------------------------------------------------

    private static final class Acc {
        final double[] v;
        final int count;
        final int comps;
        final boolean normalized;
        final int ctype;

        Acc(double[] v, int count, int comps, boolean normalized, int ctype) {
            this.v = v;
            this.count = count;
            this.comps = comps;
            this.normalized = normalized;
            this.ctype = ctype;
        }

        float[] floats() {
            float[] out = new float[v.length];
            for (int i = 0; i < out.length; i++) {
                double x = v[i];
                if (normalized) {
                    switch (ctype) {
                        case 5120: x = Math.max(x / 127.0, -1.0); break;
                        case 5121: x = x / 255.0; break;
                        case 5122: x = Math.max(x / 32767.0, -1.0); break;
                        case 5123: x = x / 65535.0; break;
                        default: break;
                    }
                }
                out[i] = (float) x;
            }
            return out;
        }

        int[] ints() {
            int[] out = new int[v.length];
            for (int i = 0; i < out.length; i++) {
                out[i] = (int) v[i];
            }
            return out;
        }
    }

    private static final class Ctx {
        List<Object> accessors = new ArrayList<>();
        List<Object> views = new ArrayList<>();
        List<byte[]> buffers = new ArrayList<>();

        Acc read(int idx) {
            if (idx < 0 || idx >= accessors.size()) {
                throw new IllegalArgumentException("Bad accessor index " + idx);
            }
            Map<String, Object> a = Json.obj(accessors.get(idx));
            if (a.containsKey("sparse")) {
                throw new UnsupportedOperationException("Sparse accessors are not supported");
            }
            int count = Json.i(a, "count", 0);
            int ct = Json.i(a, "componentType", 5126);
            int comps = compCount(Json.str(a, "type", "SCALAR"));
            boolean norm = Json.bool(a, "normalized", false);
            double[] out = new double[count * comps];
            if (!a.containsKey("bufferView")) {
                return new Acc(out, count, comps, norm, ct);
            }
            Map<String, Object> bv = Json.obj(views.get(Json.i(a, "bufferView", 0)));
            byte[] buf = buffers.get(Json.i(bv, "buffer", 0));
            int base = Json.i(bv, "byteOffset", 0) + Json.i(a, "byteOffset", 0);
            int csize = compSize(ct);
            int stride = Json.i(bv, "byteStride", 0);
            if (stride == 0) {
                stride = comps * csize;
            }
            ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < count; i++) {
                for (int k = 0; k < comps; k++) {
                    out[i * comps + k] = comp(bb, base + i * stride + k * csize, ct);
                }
            }
            return new Acc(out, count, comps, norm, ct);
        }
    }

    private static int compCount(String type) {
        switch (type) {
            case "SCALAR": return 1;
            case "VEC2": return 2;
            case "VEC3": return 3;
            case "VEC4": return 4;
            case "MAT2": return 4;
            case "MAT3": return 9;
            case "MAT4": return 16;
            default: throw new IllegalArgumentException("Unknown accessor type " + type);
        }
    }

    private static int compSize(int ct) {
        switch (ct) {
            case 5120:
            case 5121: return 1;
            case 5122:
            case 5123: return 2;
            case 5125:
            case 5126: return 4;
            default: throw new IllegalArgumentException("Unknown componentType " + ct);
        }
    }

    private static double comp(ByteBuffer bb, int pos, int ct) {
        switch (ct) {
            case 5120: return bb.get(pos);
            case 5121: return bb.get(pos) & 0xFF;
            case 5122: return bb.getShort(pos);
            case 5123: return bb.getShort(pos) & 0xFFFF;
            case 5125: return bb.getInt(pos) & 0xFFFFFFFFL;
            case 5126: return bb.getFloat(pos);
            default: throw new IllegalArgumentException("Unknown componentType " + ct);
        }
    }

    private static byte[] loadUri(String uri, Function<String, byte[]> resolver) {
        if (uri.startsWith("data:")) {
            int comma = uri.indexOf(',');
            return Base64.getDecoder().decode(uri.substring(comma + 1));
        }
        if (resolver == null) {
            throw new IllegalArgumentException("External resource '" + uri + "' needs a resolver");
        }
        byte[] b = resolver.apply(uri);
        if (b == null) {
            throw new IllegalArgumentException("Resolver could not load '" + uri + "'");
        }
        return b;
    }

    private static GltfModel parse(String json, byte[] glbBin, Function<String, byte[]> resolver) {
        Map<String, Object> root = Json.obj(Json.parse(json));
        if (root == null) {
            throw new IllegalArgumentException("glTF root must be an object");
        }
        GltfModel model = new GltfModel();
        Ctx ctx = new Ctx();

        List<Object> bufs = Json.arr(root.get("buffers"));
        if (bufs != null) {
            for (Object o : bufs) {
                String uri = Json.str(Json.obj(o), "uri", null);
                if (uri == null) {
                    if (glbBin == null) {
                        throw new IllegalArgumentException("Buffer without uri but no GLB BIN chunk");
                    }
                    ctx.buffers.add(glbBin);
                } else {
                    ctx.buffers.add(loadUri(uri, resolver));
                }
            }
        }
        List<Object> views = Json.arr(root.get("bufferViews"));
        if (views != null) {
            ctx.views = views;
        }
        List<Object> accs = Json.arr(root.get("accessors"));
        if (accs != null) {
            ctx.accessors = accs;
        }

        parseImages(root, ctx, model, resolver);
        parseMaterials(root, model);
        parseMeshes(root, ctx, model);
        parseNodes(root, model);
        parseSkins(root, ctx, model);
        parseAnimations(root, ctx, model);
        return model;
    }

    private static void parseImages(Map<String, Object> root, Ctx ctx, GltfModel model,
                                    Function<String, byte[]> resolver) {
        List<Object> imgs = Json.arr(root.get("images"));
        if (imgs == null) {
            return;
        }
        for (Object o : imgs) {
            Map<String, Object> im = Json.obj(o);
            GltfModel.Image img = new GltfModel.Image();
            img.name = Json.str(im, "name", "");
            img.mimeType = Json.str(im, "mimeType", null);
            try {
                if (im.containsKey("bufferView")) {
                    Map<String, Object> bv = Json.obj(ctx.views.get(Json.i(im, "bufferView", 0)));
                    byte[] buf = ctx.buffers.get(Json.i(bv, "buffer", 0));
                    int off = Json.i(bv, "byteOffset", 0);
                    img.data = Arrays.copyOfRange(buf, off, off + Json.i(bv, "byteLength", 0));
                } else if (im.containsKey("uri")) {
                    img.data = loadUri(Json.str(im, "uri", ""), resolver);
                }
            } catch (RuntimeException e) {
                model.warnings.add("Image '" + img.name + "' could not be loaded: " + e.getMessage());
            }
            model.images.add(img);
        }
    }

    private static int textureImage(Map<String, Object> texRef, List<Object> textures) {
        int idx = Json.i(texRef, "index", -1);
        if (idx < 0 || textures == null || idx >= textures.size()) {
            return -1;
        }
        return Json.i(Json.obj(textures.get(idx)), "source", -1);
    }

    private static void parseMaterials(Map<String, Object> root, GltfModel model) {
        List<Object> mats = Json.arr(root.get("materials"));
        if (mats == null) {
            return;
        }
        List<Object> textures = Json.arr(root.get("textures"));
        for (Object o : mats) {
            Map<String, Object> m = Json.obj(o);
            GltfModel.Material mat = new GltfModel.Material();
            mat.name = Json.str(m, "name", "");
            Map<String, Object> pbr = Json.obj(m.get("pbrMetallicRoughness"));
            if (pbr != null) {
                mat.baseColor = Json.floats(pbr, "baseColorFactor", mat.baseColor);
                mat.metallic = Json.f(pbr, "metallicFactor", 1f);
                mat.roughness = Json.f(pbr, "roughnessFactor", 1f);
                mat.baseColorImage = textureImage(Json.obj(pbr.get("baseColorTexture")), textures);
                mat.metallicRoughnessImage = textureImage(Json.obj(pbr.get("metallicRoughnessTexture")), textures);
            }
            mat.emissive = Json.floats(m, "emissiveFactor", mat.emissive);
            mat.normalImage = textureImage(Json.obj(m.get("normalTexture")), textures);
            mat.occlusionImage = textureImage(Json.obj(m.get("occlusionTexture")), textures);
            mat.emissiveImage = textureImage(Json.obj(m.get("emissiveTexture")), textures);
            model.materials.add(mat);
        }
    }

    private static void parseMeshes(Map<String, Object> root, Ctx ctx, GltfModel model) {
        List<Object> meshes = Json.arr(root.get("meshes"));
        if (meshes == null) {
            return;
        }
        for (Object mo : meshes) {
            Map<String, Object> mj = Json.obj(mo);
            GltfModel.Mesh mesh = new GltfModel.Mesh();
            mesh.name = Json.str(mj, "name", "");
            List<Object> prims = Json.arr(mj.get("primitives"));
            if (prims != null) {
                for (Object po : prims) {
                    Map<String, Object> pj = Json.obj(po);
                    int mode = Json.i(pj, "mode", 4);
                    if (mode != 4) {
                        model.warnings.add("Mesh '" + mesh.name + "': skipped non-triangle primitive (mode " + mode + ")");
                        continue;
                    }
                    Map<String, Object> attrs = Json.obj(pj.get("attributes"));
                    if (attrs == null || !attrs.containsKey("POSITION")) {
                        model.warnings.add("Mesh '" + mesh.name + "': primitive without POSITION skipped");
                        continue;
                    }
                    GltfModel.Primitive p = new GltfModel.Primitive();
                    p.positions = ctx.read(Json.i(attrs, "POSITION", -1)).floats();
                    if (attrs.containsKey("NORMAL")) {
                        p.normals = ctx.read(Json.i(attrs, "NORMAL", -1)).floats();
                    }
                    if (attrs.containsKey("TEXCOORD_0")) {
                        p.uvs = ctx.read(Json.i(attrs, "TEXCOORD_0", -1)).floats();
                    }
                    if (attrs.containsKey("JOINTS_0")) {
                        p.joints = ctx.read(Json.i(attrs, "JOINTS_0", -1)).ints();
                    }
                    if (attrs.containsKey("WEIGHTS_0")) {
                        p.weights = ctx.read(Json.i(attrs, "WEIGHTS_0", -1)).floats();
                    }
                    if (pj.containsKey("indices")) {
                        p.indices = ctx.read(Json.i(pj, "indices", -1)).ints();
                    } else {
                        p.indices = new int[p.vertexCount()];
                        for (int i = 0; i < p.indices.length; i++) {
                            p.indices[i] = i;
                        }
                    }
                    p.material = Json.i(pj, "material", -1);
                    mesh.primitives.add(p);
                }
            }
            model.meshes.add(mesh);
        }
    }

    private static void parseNodes(Map<String, Object> root, GltfModel model) {
        List<Object> nodes = Json.arr(root.get("nodes"));
        if (nodes == null) {
            return;
        }
        for (Object o : nodes) {
            Map<String, Object> nj = Json.obj(o);
            GltfModel.Node n = new GltfModel.Node();
            n.name = Json.str(nj, "name", "");
            n.mesh = Json.i(nj, "mesh", -1);
            n.skin = Json.i(nj, "skin", -1);
            List<Object> ch = Json.arr(nj.get("children"));
            if (ch != null) {
                n.children = new int[ch.size()];
                for (int i = 0; i < n.children.length; i++) {
                    n.children[i] = ((Number) ch.get(i)).intValue();
                }
            }
            float[] matrix = Json.floats(nj, "matrix", new float[16]);
            if (nj.containsKey("matrix") && matrix.length == 16) {
                new Mat4(matrix).decompose(n.t, n.r, n.s);
            } else {
                n.t = Json.floats(nj, "translation", n.t);
                n.r = Json.floats(nj, "rotation", n.r);
                n.s = Json.floats(nj, "scale", n.s);
            }
            model.nodes.add(n);
        }
        for (int i = 0; i < model.nodes.size(); i++) {
            for (int c : model.nodes.get(i).children) {
                model.nodes.get(c).parent = i;
            }
        }
        List<Object> scenes = Json.arr(root.get("scenes"));
        int sceneIdx = Json.i(root, "scene", 0);
        if (scenes != null && sceneIdx >= 0 && sceneIdx < scenes.size()) {
            List<Object> sn = Json.arr(Json.obj(scenes.get(sceneIdx)).get("nodes"));
            if (sn != null) {
                for (Object x : sn) {
                    model.rootNodes.add(((Number) x).intValue());
                }
            }
        }
        if (model.rootNodes.isEmpty()) {
            for (int i = 0; i < model.nodes.size(); i++) {
                if (model.nodes.get(i).parent < 0) {
                    model.rootNodes.add(i);
                }
            }
        }
    }

    private static void parseSkins(Map<String, Object> root, Ctx ctx, GltfModel model) {
        List<Object> skins = Json.arr(root.get("skins"));
        if (skins == null) {
            return;
        }
        for (Object o : skins) {
            Map<String, Object> sj = Json.obj(o);
            GltfModel.Skin skin = new GltfModel.Skin();
            skin.name = Json.str(sj, "name", "");
            List<Object> js = Json.arr(sj.get("joints"));
            int n = js == null ? 0 : js.size();
            skin.joints = new int[n];
            for (int i = 0; i < n; i++) {
                skin.joints[i] = ((Number) js.get(i)).intValue();
            }
            skin.inverseBind = new float[n][];
            if (sj.containsKey("inverseBindMatrices")) {
                float[] all = ctx.read(Json.i(sj, "inverseBindMatrices", -1)).floats();
                for (int i = 0; i < n; i++) {
                    skin.inverseBind[i] = Arrays.copyOfRange(all, i * 16, i * 16 + 16);
                }
            } else {
                for (int i = 0; i < n; i++) {
                    skin.inverseBind[i] = new Mat4().m.clone();
                }
            }
            model.skins.add(skin);
        }
    }

    private static void parseAnimations(Map<String, Object> root, Ctx ctx, GltfModel model) {
        List<Object> anims = Json.arr(root.get("animations"));
        if (anims == null) {
            return;
        }
        for (Object o : anims) {
            Map<String, Object> aj = Json.obj(o);
            GltfModel.Animation anim = new GltfModel.Animation();
            anim.name = Json.str(aj, "name", "");
            List<Object> samplersJson = Json.arr(aj.get("samplers"));
            GltfModel.Sampler[] samplers = new GltfModel.Sampler[samplersJson == null ? 0 : samplersJson.size()];
            for (int i = 0; i < samplers.length; i++) {
                Map<String, Object> sj = Json.obj(samplersJson.get(i));
                GltfModel.Sampler s = new GltfModel.Sampler();
                s.times = ctx.read(Json.i(sj, "input", -1)).floats();
                s.values = ctx.read(Json.i(sj, "output", -1)).floats();
                s.interpolation = Json.str(sj, "interpolation", "LINEAR");
                samplers[i] = s;
                for (float t : s.times) {
                    anim.duration = Math.max(anim.duration, t);
                }
            }
            List<Object> chs = Json.arr(aj.get("channels"));
            if (chs != null) {
                for (Object co : chs) {
                    Map<String, Object> cj = Json.obj(co);
                    Map<String, Object> target = Json.obj(cj.get("target"));
                    String path = Json.str(target, "path", "");
                    if (!path.equals("translation") && !path.equals("rotation") && !path.equals("scale")) {
                        model.warnings.add("Animation '" + anim.name + "': channel path '" + path + "' ignored");
                        continue;
                    }
                    if (!target.containsKey("node")) {
                        continue;
                    }
                    GltfModel.Channel c = new GltfModel.Channel();
                    c.node = Json.i(target, "node", -1);
                    c.path = path;
                    c.sampler = samplers[Json.i(cj, "sampler", 0)];
                    anim.channels.add(c);
                }
            }
            model.animations.add(anim);
        }
    }
}
