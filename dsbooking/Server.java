package dsbooking;

import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets; // <-- 新增：用于精确取 UTF-8 长度
import java.time.Instant;
import java.util.*;

public class Server {
    private final DatagramSocket sock;
    private final byte semantics;
    private final Random rng;
    private final double lossRate;

    private final Map<String, Facility> facilities = new HashMap<>();
    private final List<MonitorClient> monitors = new ArrayList<>();

    // For AMO: history of (client,port,reqId) -> last reply bytes
    private final Map<String, byte[]> history = new HashMap<>();

    public Server(int port, byte semantics, double lossRate, long seed) throws Exception {
        this.sock = new DatagramSocket(port);
        this.semantics = semantics;
        this.lossRate = lossRate;
        this.rng = new Random(seed);

        // preload sample facilities
        facilities.put("RoomA", new Facility("RoomA"));
        facilities.put("RoomB", new Facility("RoomB"));
        facilities.put("LT1", new Facility("LT1"));
        System.out.println("Server listening UDP port " + port + " semantics=" + (semantics==Message.SEM_AMO?"AMO":"ALO") + " lossRate=" + lossRate);
    }

    private String key(InetAddress a, int p, long reqId) {
        return a.getHostAddress()+":"+p+":"+reqId;
    }

    private void send(InetAddress addr, int port, byte[] data) throws Exception {
        if (Util.shouldDrop(rng, lossRate)) {
            System.out.println("[DROP->] simulated drop of reply ("+data.length+" bytes) to "+addr+":"+port);
            return;
        }
        DatagramPacket pkt = new DatagramPacket(data, data.length, addr, port);
        sock.send(pkt);
    }

    // -------- FIXED HERE ----------
    private void broadcastMonitorUpdates(String facilityName) throws Exception {
        Facility f = facilities.get(facilityName);
        if (f==null) return;

        String text = "Weekly availability for "+facilityName+"\n"+f.weeklyBitmap();

        // 计算两段字符串的 UTF-8 字节长度，并为两次 putStr 预留 4+len 空间
        byte[] facBytes = facilityName.getBytes(StandardCharsets.UTF_8);
        byte[] txtBytes = text.getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.allocate((4 + facBytes.length) + (4 + txtBytes.length));
        Marshaller.putStr(bb, facilityName);
        Marshaller.putStr(bb, text);

        byte[] payload = bb.array();

        // Send to all non-expired matching monitors
        Iterator<MonitorClient> it = monitors.iterator();
        while (it.hasNext()) {
            MonitorClient mc = it.next();
            if (mc.expired()) { it.remove(); continue; }
            if (!mc.facility.equals(facilityName)) continue;
            Message m = new Message(semantics, Message.OP_MONITOR_UPDATE, System.nanoTime(), payload);
            byte[] dat = Marshaller.pack(m);
            send(mc.addr, mc.port, dat);
        }
    }
    // -------- END FIX -------------

    private byte[] handle(Message req, InetAddress from, int port) {
        try {
            ByteBuffer bb = ByteBuffer.wrap(req.payload==null?new byte[0]:req.payload);
            switch (req.opcode) {
                case Message.OP_QUERY: {
                    String facility = Marshaller.getStr(bb);
                    String daysCsv  = Marshaller.getStr(bb);
                    Facility f = facilities.get(facility);
                    if (f==null) return error(req, "No such facility");
                    StringBuilder sb = new StringBuilder();
                    sb.append("=== ").append(facility).append(" 预订情况 ===\n");
                    for (String d: daysCsv.split(",")) {
                        int di = Util.dayToIdx(d.trim());
                        sb.append(f.getDetailedAvailability(di));
                    }
                    return ok(req, sb.toString());
                }
                case Message.OP_BOOK: {
                    String facility = Marshaller.getStr(bb);
                    int day = bb.getInt();
                    int s = bb.getInt();
                    int e = bb.getInt();
                    Facility f = facilities.get(facility);
                    if (f==null) return error(req, "No such facility");
                    BookingTime bt = new BookingTime(day,s,e);
                    if (!f.isFree(bt)) return error(req, "Unavailable in requested period");
                    long id = Math.abs(new Random().nextLong());
                    f.occupy(bt);
                    Booking b = new Booking(id, facility, bt);
                    f.bookings.put(id, b);
                    broadcastMonitorSafe(facility);
                    return ok(req, "CONFIRM# " + id); // 标签 + 空格 + 数字
                }
                case Message.OP_CHANGE: {
                    // 新语义：整体平移时间，保持原时长和星期
                    long id = bb.getLong();
                    int shiftMinutes = bb.getInt(); // 正数向后移，负数向前移

                    Booking b = null; Facility f=null;
                    for (Facility fx: facilities.values()) {
                        if (fx.bookings.containsKey(id)) { b=fx.bookings.get(id); f=fx; break; }
                    }
                    if (b==null || f==null) return error(req, "No such confirmation ID");

                    // 计算新的开始和结束时间
                    int newS = b.t.startMin + shiftMinutes;
                    int newE = b.t.endMin + shiftMinutes;
                    int newDay = b.t.day;
                    
                    // 检查是否跨天
                    while (newS < 0) {
                        newS += 1440;
                        newE += 1440;
                        newDay--;
                    }
                    while (newS >= 1440) {
                        newS -= 1440;
                        newE -= 1440;
                        newDay++;
                    }
                    
                    // 检查星期是否有效
                    if (newDay < 0 || newDay > 6) {
                        return error(req, "Shift would move booking outside week range");
                    }
                    
                    // 检查是否超过当天24:00
                    if (newE > 1440) {
                        return error(req, "Shift would exceed end of day");
                    }

                    BookingTime newBt = new BookingTime(newDay, newS, newE);

                    // 先释放旧占用，检查新时段；若失败再恢复
                    f.free(b.t);
                    if (!f.isFree(newBt)) {
                        f.occupy(b.t);
                        return error(req, "Unavailable for new period");
                    }
                    f.occupy(newBt);
                    b.t = newBt;
                    broadcastMonitorSafe(b.facility);
                    return ok(req, "CHANGED# " + id + " (shifted " + (shiftMinutes>=0?"+":"") + shiftMinutes + " min)");
                }
                case Message.OP_CANCEL: { // idempotent
                    long id = bb.getLong();
                    Booking b = null; Facility f=null;
                    for (Facility fx: facilities.values()) {
                        if (fx.bookings.containsKey(id)) { b=fx.bookings.remove(id); f=fx; break; }
                    }
                    if (b==null || f==null) return ok(req, "ALREADY_CANCELED_OR_NOT_FOUND");
                    f.free(b.t);
                    broadcastMonitorSafe(b.facility);
                    return ok(req, "CANCELED# " + id);
                }
                case Message.OP_EXTEND: { // non-idempotent: extend/shorten booking
                    long id = bb.getLong();
                    int startDelta = bb.getInt(); // 开始时间调整（负数提前，正数延后）
                    int endDelta = bb.getInt();   // 结束时间调整（负数缩短，正数延长）

                    Booking b = null; Facility f=null;
                    for (Facility fx: facilities.values()) {
                        if (fx.bookings.containsKey(id)) { b=fx.bookings.get(id); f=fx; break; }
                    }
                    if (b==null || f==null) return error(req, "No such confirmation ID");

                    // 计算新的开始和结束时间
                    int newS = b.t.startMin + startDelta;
                    int newE = b.t.endMin + endDelta;
                    
                    // 验证新时间的有效性
                    if (newS < 0) {
                        return error(req, "New start time would be before 00:00");
                    }
                    if (newE > 1440) {
                        return error(req, "New end time would exceed 24:00");
                    }
                    if (newS >= newE) {
                        return error(req, "New start time must be before end time");
                    }
                    
                    BookingTime newBt = new BookingTime(b.t.day, newS, newE);

                    // 先释放旧占用
                    f.free(b.t);
                    
                    // 检查新时段是否可用
                    if (!f.isFree(newBt)) {
                        // 恢复旧占用并返回错误
                        f.occupy(b.t);
                        return error(req, "Unavailable for new period");
                    }
                    
                    // 应用新时段
                    f.occupy(newBt);
                    b.t = newBt;
                    broadcastMonitorSafe(b.facility);
                    
                    String msg = "EXTENDED# " + id + " (start " + (startDelta>=0?"+":"") + startDelta + " min, end " + (endDelta>=0?"+":"") + endDelta + " min)";
                    return ok(req, msg);
                }
                case Message.OP_MONITOR_REGISTER: {
                    String facility = Marshaller.getStr(bb);
                    int seconds = bb.getInt();
                    Facility f = facilities.get(facility);
                    if (f==null) return error(req, "No such facility");
                    monitors.add(new MonitorClient(from, port, facility, Instant.now().plusSeconds(seconds)));
                    // Push initial snapshot
                    broadcastMonitorSafe(facility);
                    return ok(req, "MONITORING# "+facility+" for "+seconds+"s");
                }
                default:
                    return error(req, "Unknown op");
            }
        } catch (Exception e) {
            return error(req, "Exception: "+e.getMessage());
        }
    }

    private void broadcastMonitorSafe(String facility) {
        try { broadcastMonitorUpdates(facility); } catch (Exception ex) {
            System.out.println("Monitor broadcast failed: "+ex);
        }
    }

    private byte[] ok(Message req, String text) {
        byte[] payload = Marshaller.str(text);
        Message rep = new Message(req.semantics, req.opcode, req.reqId, payload);
        rep.version = Message.VERSION;
        return Marshaller.pack(rep);
    }

    private byte[] error(Message req, String text) {
        byte[] payload = Marshaller.str("ERROR: "+text);
        Message rep = new Message(req.semantics, req.opcode, req.reqId, payload);
        rep.flags = 1; // error flag
        return Marshaller.pack(rep);
    }

    public void loop() throws Exception {
        byte[] buf = new byte[2048];
        while (true) {
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            sock.receive(pkt);

            InetAddress from = pkt.getAddress();
            int port = pkt.getPort();
            Message req;
            try {
                req = Marshaller.unpack(pkt.getData(), pkt.getLength());
            } catch (Exception e) {
                System.out.println("Bad packet from "+from+":"+port+" -> "+e);
                continue;
            }

            String hkey = key(from, port, req.reqId);
            byte[] reply;
            if (semantics == Message.SEM_AMO) {
                if (history.containsKey(hkey)) {
                    reply = history.get(hkey);
                    System.out.println("[AMO replay] Resent cached reply for reqId="+req.reqId+" to "+from+":"+port);
                } else {
                    reply = handle(req, from, port);
                    history.put(hkey, reply);
                }
            } else {
                reply = handle(req, from, port);
            }

            send(from, port, reply);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            args = new String[]{"5000", "AMO", "0.0", "42"};
            System.out.println("[INFO] No args provided, using default: 5000 AMO 0.0 42");
        }
        int port = Integer.parseInt(args[0]);
        byte sem = "ALO".equalsIgnoreCase(args[1]) ? Message.SEM_ALO : Message.SEM_AMO;
        double loss = (args.length>=3) ? Double.parseDouble(args[2]) : 0.0;
        long seed = (args.length>=4) ? Long.parseLong(args[3]) : 0L;
        new Server(port, sem, loss, seed).loop();
    }
}