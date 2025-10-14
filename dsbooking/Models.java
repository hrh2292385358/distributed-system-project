package dsbooking;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

class BookingTime {
    // day: 0=Mon ... 6=Sun; minute of day: 0..1439
    final int day;
    final int startMin; // inclusive
    final int endMin;   // exclusive

    BookingTime(int day, int startMin, int endMin) {
        this.day = day; this.startMin = startMin; this.endMin = endMin;
        if (day<0||day>6||startMin<0||startMin>=24*60||endMin<=0||endMin>24*60||startMin>=endMin) {
            throw new IllegalArgumentException("Bad time");
        }
    }

    BookingTime shifted(int minutes) {
        int s = startMin + minutes;
        int e = endMin + minutes;
        int d = day;
        while (s < 0) { s += 1440; e += 1440; d = (d+6)%7; }
        while (e > 1440) { s -= 1440; e -= 1440; d = (d+1)%7; }
        if (s < 0 || e > 1440) throw new IllegalArgumentException("Cross-day shift not supported");
        return new BookingTime(d, s, e);
    }
}

class Booking {
    final long id;
    final String facility;
    BookingTime t;
    Booking(long id, String facility, BookingTime t) { this.id=id; this.facility=facility; this.t=t; }
}

class Facility {
    final String name;
    // minute-resolution availability: false=free, true=booked
    final boolean[][] week = new boolean[7][1440];
    final Map<Long, Booking> bookings = new HashMap<>();

    Facility(String name) { this.name = name; }

    boolean isFree(BookingTime bt) {
        boolean[] day = week[bt.day];
        for(int m=bt.startMin; m<bt.endMin; m++) if (day[m]) return false;
        return true;
    }

    void occupy(BookingTime bt) {
        boolean[] day = week[bt.day];
        for(int m=bt.startMin; m<bt.endMin; m++) day[m]=true;
    }

    void free(BookingTime bt) {
        boolean[] day = week[bt.day];
        for(int m=bt.startMin; m<bt.endMin; m++) day[m]=false;
    }

    String weeklyBitmap() {
        StringBuilder sb = new StringBuilder();
        String[] dn = {"Mon","Tue","Wed","Thu","Fri","Sat","Sun"};
        for(int d=0; d<7; d++) {
            int free=0;
            for (boolean b: week[d]) if (!b) free++;
            sb.append(dn[d]).append(": free ").append(free).append("/1440 minutes\n");
        }
        return sb.toString();
    }
    
    // 显示某一天的详细空闲和占用时间段
    String getDetailedAvailability(int dayIdx) {
        StringBuilder sb = new StringBuilder();
        boolean[] day = week[dayIdx];
        String dayName = Util.idxToDay(dayIdx);
        
        // 查找所有空闲和占用的时间段
        List<String> freeSlots = new ArrayList<>();
        List<String> bookedSlots = new ArrayList<>();
        
        int i = 0;
        while (i < 1440) {
            if (day[i]) {
                // 找到占用时间段的起始
                int start = i;
                while (i < 1440 && day[i]) i++;
                bookedSlots.add(Util.minToHm(start) + "-" + Util.minToHm(i));
            } else {
                // 找到空闲时间段的起始
                int start = i;
                while (i < 1440 && !day[i]) i++;
                freeSlots.add(Util.minToHm(start) + "-" + Util.minToHm(i));
            }
        }
        
        sb.append(dayName).append(":\n");
        if (bookedSlots.isEmpty()) {
            sb.append("  全天空闲 (00:00-24:00)\n");
        } else {
            sb.append("  已占用时段: ");
            for (int j = 0; j < bookedSlots.size(); j++) {
                if (j > 0) sb.append(", ");
                sb.append(bookedSlots.get(j));
            }
            sb.append("\n");
            
            if (!freeSlots.isEmpty()) {
                sb.append("  可预订时段: ");
                for (int j = 0; j < freeSlots.size(); j++) {
                    if (j > 0) sb.append(", ");
                    sb.append(freeSlots.get(j));
                }
                sb.append("\n");
            } else {
                sb.append("  可预订时段: 无\n");
            }
        }
        
        return sb.toString();
    }
}

class MonitorClient {
    final InetAddress addr;
    final int port;
    final String facility;
    final Instant expireAt;
    MonitorClient(InetAddress addr,int port,String facility,Instant expireAt) {
        this.addr=addr; this.port=port; this.facility=facility; this.expireAt=expireAt;
    }
    boolean expired() { return Instant.now().isAfter(expireAt); }
}
