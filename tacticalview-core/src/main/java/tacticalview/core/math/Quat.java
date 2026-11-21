package tacticalview.core.math;

/** 四元数工具，布局 (x, y, z, w)。 */
public final class Quat {
    private Quat() {}

    public static float[] identity() {
        return new float[] {0, 0, 0, 1};
    }

    public static void normalize(float[] q, int o) {
        float l = (float) Math.sqrt(q[o] * q[o] + q[o + 1] * q[o + 1] + q[o + 2] * q[o + 2] + q[o + 3] * q[o + 3]);
        if (l < 1e-12f) {
            q[o] = 0;
            q[o + 1] = 0;
            q[o + 2] = 0;
            q[o + 3] = 1;
            return;
        }
        q[o] /= l;
        q[o + 1] /= l;
        q[o + 2] /= l;
        q[o + 3] /= l;
    }

    public static float[] fromAxisAngle(float ax, float ay, float az, float angle) {
        float len = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        if (len < 1e-12f) {
            return identity();
        }
        float s = (float) Math.sin(angle * 0.5f) / len;
        return new float[] {ax * s, ay * s, az * s, (float) Math.cos(angle * 0.5f)};
    }

    /** Hamilton 乘积 a * b。 */
    public static float[] mul(float[] a, float[] b) {
        return new float[] {
            a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
            a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
            a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
            a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]
        };
    }

    public static float[] conj(float[] a) {
        return new float[] {-a[0], -a[1], -a[2], a[3]};
    }

    /** 球面插值，结果写入 out[oo..oo+3]。 */
    public static void slerp(float[] a, int ao, float[] b, int bo, float t, float[] out, int oo) {
        float ax = a[ao], ay = a[ao + 1], az = a[ao + 2], aw = a[ao + 3];
        float bx = b[bo], by = b[bo + 1], bz = b[bo + 2], bw = b[bo + 3];
        float dot = ax * bx + ay * by + az * bz + aw * bw;
        if (dot < 0) {
            dot = -dot;
            bx = -bx;
            by = -by;
            bz = -bz;
            bw = -bw;
        }
        float k0, k1;
        if (dot > 0.9995f) {
            k0 = 1f - t;
            k1 = t;
        } else {
            float theta = (float) Math.acos(dot);
            float sin = (float) Math.sin(theta);
            k0 = (float) Math.sin((1f - t) * theta) / sin;
            k1 = (float) Math.sin(t * theta) / sin;
        }
        out[oo] = k0 * ax + k1 * bx;
        out[oo + 1] = k0 * ay + k1 * by;
        out[oo + 2] = k0 * az + k1 * bz;
        out[oo + 3] = k0 * aw + k1 * bw;
        normalize(out, oo);
    }
}
