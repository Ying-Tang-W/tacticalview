package tacticalview.core.validate;

/** 不解码整张图，只从文件头读取 PNG/JPEG 的宽高。 */
public final class ImageInfo {
    private ImageInfo() {}

    /** @return {width, height}；无法识别返回 null。 */
    public static int[] dimensions(byte[] d) {
        if (d == null) {
            return null;
        }
        if (d.length >= 24 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return new int[] {be32(d, 16), be32(d, 20)};
        }
        if (d.length >= 4 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) {
            int i = 2;
            while (i + 9 < d.length) {
                if ((d[i] & 0xFF) != 0xFF) {
                    i++;
                    continue;
                }
                int marker = d[i + 1] & 0xFF;
                if (marker == 0xFF) {
                    i++;
                    continue;
                }
                if (marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD7) || marker == 0x01) {
                    i += 2;
                    continue;
                }
                int len = ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
                boolean sof = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
                if (sof) {
                    int h = ((d[i + 5] & 0xFF) << 8) | (d[i + 6] & 0xFF);
                    int w = ((d[i + 7] & 0xFF) << 8) | (d[i + 8] & 0xFF);
                    return new int[] {w, h};
                }
                i += 2 + len;
            }
        }
        return null;
    }

    private static int be32(byte[] d, int o) {
        return ((d[o] & 0xFF) << 24) | ((d[o + 1] & 0xFF) << 16) | ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
    }
}
