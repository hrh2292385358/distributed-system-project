package dsbooking;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Random;

public class Util {
    public static int dayToIdx(String s) {
        s = s.trim().toLowerCase(Locale.ROOT);
        switch (s.substring(0,3)) {
            case "mon": return 0;
            case "tue": return 1;
            case "wed": return 2;
            case "thu": return 3;
            case "fri": return 4;
            case "sat": return 5;
            case "sun": return 6;
            default: throw new IllegalArgumentException("Bad day: " + s);
        }
    }
    public static String idxToDay(int d) {
        String[] dn={"Mon","Tue","Wed","Thu","Fri","Sat","Sun"};
        return dn[d];
    }
    public static int hmToMin(String hm) {
        String[] p = hm.split(":");
        int h = Integer.parseInt(p[0]);
        int m = Integer.parseInt(p[1]);
        if (h<0||h>=24||m<0||m>=60) throw new IllegalArgumentException("Bad HH:MM");
        return h*60+m;
    }
    
    // Convert minutes to HH:MM format
    public static String minToHm(int minutes) {
        if (minutes < 0 || minutes > 1440) {
            throw new IllegalArgumentException("Bad minutes: " + minutes);
        }
        int h = minutes / 60;
        int m = minutes % 60;
        return String.format("%02d:%02d", h, m);
    }

    public static byte[] join(byte[]... parts) {
        int sum=0; for (byte[] p: parts) sum += p.length;
        ByteBuffer bb = ByteBuffer.allocate(sum);
        for (byte[] p: parts) bb.put(p);
        return bb.array();
    }

    public static boolean shouldDrop(Random rng, double rate) {
        if (rate<=0) return false;
        return rng.nextDouble() < rate;
    }
}
