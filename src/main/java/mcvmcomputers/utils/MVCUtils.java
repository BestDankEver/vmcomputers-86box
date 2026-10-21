package mcvmcomputers.utils;

import org.joml.Quaternionf;
import net.minecraft.util.math.Vec3d;

public class MVCUtils {
	public static Quaternionf lookAt(Vec3d src, Vec3d dest) {
		Vec3d forward = new Vec3d(0,0,1);
		Vec3d fwd = src.subtract(dest).normalize();
		double dot = forward.dotProduct(fwd);
		Vec3d up = new Vec3d(0,1,0);
		if(Math.abs(dot - (-1.0f)) < 0.000001f) {
			return new Quaternionf((float)up.x, (float)up.y, (float)up.z, 3.1415926535897932f);
		}
		if(Math.abs(dot - (1.0f)) < 0.000001f) {
			return new Quaternionf();
		}
		
		double rotAngle = Math.acos(dot);
		Vec3d rotAxis = forward.crossProduct(fwd);
		rotAxis = rotAxis.normalize();
		return createFromAxisAngle(rotAxis, rotAngle);
	}
	
	public static Quaternionf createFromAxisAngle(Vec3d axis, double angle) {
		double halfAngle = angle * .5;
	    float s = (float)Math.sin(halfAngle);
	    Quaternionf q = new Quaternionf((float) axis.x * s, (float) axis.y * s, (float) axis.z * s, (float)Math.cos(halfAngle));
	    return q;
	}
	
	/** Rotation from Euler angles in degrees (X, then Y, then Z), like 1.16's Quaternion(x, y, z, true). */
	public static Quaternionf euler(float x, float y, float z) {
		return new Quaternionf().rotationXYZ((float) Math.toRadians(x), (float) Math.toRadians(y), (float) Math.toRadians(z));
	}

	public static float lerp(float a, float b, float t) {
		return a + (b-a) * t;
	}
	
	public static double lerp(double a, double b, double t) {
		return a + (b-a) * t;
	}
	
	public static final char COLOR_CHAR = (char) (0xfeff00a7);
	public static String getColorChar(char color) {
		return COLOR_CHAR + "" + color;
	}
}
