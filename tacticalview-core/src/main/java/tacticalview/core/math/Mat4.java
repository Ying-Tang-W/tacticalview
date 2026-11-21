package tacticalview.core.math;

/** 4x4 矩阵，列主序（与 glTF / OpenGL 一致）。元素 (row, col) 位于 m[col * 4 + row]。 */
public final class Mat4 {
    public final float[] m = new float[16];

    public Mat4() {
        m[0] = m[5] = m[10] = m[15] = 1f;
    }

    public Mat4(float[] columnMajor) {
        System.arraycopy(columnMajor, 0, m, 0, 16);
    }

    public static Mat4 translation(float x, float y, float z) {
        Mat4 r = new Mat4();
        r.m[12] = x;
        r.m[13] = y;
        r.m[14] = z;
        return r;
    }

    /** 由 平移/四元数旋转(x,y,z,w)/缩放 构造，数组均可带偏移，方便直接读 Pose 的扁平数组。 */
    public static Mat4 fromTRS(float[] t, int ti, float[] q, int qi, float[] s, int si) {
        float x = q[qi], y = q[qi + 1], z = q[qi + 2], w = q[qi + 3];
        float xx = x * x, yy = y * y, zz = z * z;
        float xy = x * y, xz = x * z, yz = y * z;
        float wx = w * x, wy = w * y, wz = w * z;
        float sx = s[si], sy = s[si + 1], sz = s[si + 2];
        Mat4 r = new Mat4();
        float[] m = r.m;
        m[0] = (1 - 2 * (yy + zz)) * sx;
        m[1] = 2 * (xy + wz) * sx;
        m[2] = 2 * (xz - wy) * sx;
        m[3] = 0;
        m[4] = 2 * (xy - wz) * sy;
        m[5] = (1 - 2 * (xx + zz)) * sy;
        m[6] = 2 * (yz + wx) * sy;
        m[7] = 0;
        m[8] = 2 * (xz + wy) * sz;
        m[9] = 2 * (yz - wx) * sz;
        m[10] = (1 - 2 * (xx + yy)) * sz;
        m[11] = 0;
        m[12] = t[ti];
        m[13] = t[ti + 1];
        m[14] = t[ti + 2];
        m[15] = 1;
        return r;
    }

    /** this * o （先应用 o，再应用 this）。 */
    public Mat4 mul(Mat4 o) {
        Mat4 r = new Mat4();
        float[] a = m, b = o.m, c = r.m;
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0;
                for (int k = 0; k < 4; k++) {
                    sum += a[k * 4 + row] * b[col * 4 + k];
                }
                c[col * 4 + row] = sum;
            }
        }
        return r;
    }

    /** 变换点（w=1），结果写入 out[oi..oi+2]。 */
    public void transformPoint(float x, float y, float z, float[] out, int oi) {
        out[oi] = m[0] * x + m[4] * y + m[8] * z + m[12];
        out[oi + 1] = m[1] * x + m[5] * y + m[9] * z + m[13];
        out[oi + 2] = m[2] * x + m[6] * y + m[10] * z + m[14];
    }

    /** 仿射矩阵求逆（旋转+缩放+平移，不含透视）。 */
    public Mat4 inverseAffine() {
        float a = m[0], b = m[4], c = m[8];
        float d = m[1], e = m[5], f = m[9];
        float g = m[2], h = m[6], i = m[10];
        float det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        if (Math.abs(det) < 1e-12f) {
            throw new ArithmeticException("Matrix is singular");
        }
        float id = 1f / det;
        float r00 = (e * i - f * h) * id, r01 = (c * h - b * i) * id, r02 = (b * f - c * e) * id;
        float r10 = (f * g - d * i) * id, r11 = (a * i - c * g) * id, r12 = (c * d - a * f) * id;
        float r20 = (d * h - e * g) * id, r21 = (b * g - a * h) * id, r22 = (a * e - b * d) * id;
        Mat4 r = new Mat4();
        float[] o = r.m;
        o[0] = r00; o[4] = r01; o[8] = r02;
        o[1] = r10; o[5] = r11; o[9] = r12;
        o[2] = r20; o[6] = r21; o[10] = r22;
        float tx = m[12], ty = m[13], tz = m[14];
        o[12] = -(r00 * tx + r01 * ty + r02 * tz);
        o[13] = -(r10 * tx + r11 * ty + r12 * tz);
        o[14] = -(r20 * tx + r21 * ty + r22 * tz);
        return r;
    }

    /** 分解为 TRS（假设无剪切）。t[3], q[4], s[3]。 */
    public void decompose(float[] t, float[] q, float[] s) {
        t[0] = m[12];
        t[1] = m[13];
        t[2] = m[14];
        float sx = (float) Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]);
        float sy = (float) Math.sqrt(m[4] * m[4] + m[5] * m[5] + m[6] * m[6]);
        float sz = (float) Math.sqrt(m[8] * m[8] + m[9] * m[9] + m[10] * m[10]);
        float det = m[0] * (m[5] * m[10] - m[9] * m[6])
                - m[4] * (m[1] * m[10] - m[9] * m[2])
                + m[8] * (m[1] * m[6] - m[5] * m[2]);
        if (det < 0) {
            sx = -sx;
        }
        s[0] = sx;
        s[1] = sy;
        s[2] = sz;
        float r00 = m[0] / sx, r10 = m[1] / sx, r20 = m[2] / sx;
        float r01 = m[4] / sy, r11 = m[5] / sy, r21 = m[6] / sy;
        float r02 = m[8] / sz, r12 = m[9] / sz, r22 = m[10] / sz;
        float trace = r00 + r11 + r22;
        float x, y, z, w;
        if (trace > 0) {
            float k = (float) Math.sqrt(trace + 1f) * 2f;
            w = 0.25f * k;
            x = (r21 - r12) / k;
            y = (r02 - r20) / k;
            z = (r10 - r01) / k;
        } else if (r00 > r11 && r00 > r22) {
            float k = (float) Math.sqrt(1f + r00 - r11 - r22) * 2f;
            w = (r21 - r12) / k;
            x = 0.25f * k;
            y = (r01 + r10) / k;
            z = (r02 + r20) / k;
        } else if (r11 > r22) {
            float k = (float) Math.sqrt(1f + r11 - r00 - r22) * 2f;
            w = (r02 - r20) / k;
            x = (r01 + r10) / k;
            y = 0.25f * k;
            z = (r12 + r21) / k;
        } else {
            float k = (float) Math.sqrt(1f + r22 - r00 - r11) * 2f;
            w = (r10 - r01) / k;
            x = (r02 + r20) / k;
            y = (r12 + r21) / k;
            z = 0.25f * k;
        }
        q[0] = x;
        q[1] = y;
        q[2] = z;
        q[3] = w;
    }
}
