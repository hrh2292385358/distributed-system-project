import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets; // For UTF-8 byte length
import java.time.Instant;
import java.util.*;

// UDP server for facility booking
public class Server {
    private final DatagramSocket sock;
    private final byte semantics; // AMO or ALO
    private final Random rng;
    private final double lossRate; // Packet loss simulation

    private final Map<String, Facility> facilities = new HashMap<>();
    private final List<MonitorClient> monitors = new ArrayList<>();

    // For AMO: history of (client,port,reqId) -> last reply bytes
    private final Map<String, byte[]> history = new HashMap<>();

    public Server(int port, byte semantics, double lossRate, long seed) throws Exception {
        this.sock = new DatagramSocket(port);
        this.semantics = semantics;
        this.lossRate = lossRate;
        this.rng = new Random(seed);

        // Preload sample facilities
        facilities.put("RoomA", new Facility("RoomA"));
        facilities.put("RoomB", new Facility("RoomB"));
        facilities.put("LT1", new Facility("LT1"));
        System.out.println("Server listening UDP port " + port + " semantics=" + (semantics==Message.SEM_AMO?"AMO":"ALO") + " lossRate=" + lossRate);
    }

    // Generate unique key for request deduplication
    private String key(InetAddress a, int p, long reqId) {
        return a.getHostAddress()+":"+p+":"+reqId;
    }

    // Send reply with packet loss simulation
    private void send(InetAddress addr, int port, byte[] data) throws Exception {
        if (Util.shouldDrop(rng, lossRate)) {
            System.out.println("[DROP->] simulated drop of reply ("+data.length+" bytes) to "+addr+":"+port);
            return;
        }
        DatagramPacket pkt = new DatagramPacket(data, data.length, addr, port);
        sock.send(pkt);
    }

    // Broadcast updates to all monitors for this facility
    private void broadcastMonitorUpdates(String facilityName) throws Exception {
        Facility f = facilities.get(facilityName);
        if (f==null) return;

        // Build detailed availability text with specific time slots
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(facilityName).append(" Status ===\n");
        for (int day = 0; day < 7; day++) {
            sb.append(f.getDetailedAvailability(day));
        }
        String text = sb.toString();

        // Calculate UTF-8 byte lengths for two putStr calls
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

    // Handle incoming request and return reply bytes
    private byte[] handle(Message req, InetAddress from, int port) {
        try {
            ByteBuffer bb = ByteBuffer.wrap(req.payload==null?new byte[0]:req.payload);
            switch (req.opcode) {
                case Message.OP_QUERY: {
                    // Input: facility name, comma-separated days
                    // Output: detailed availability showing booked and free time slots
                    String facility = Marshaller.getStr(bb);
                    String daysCsv  = Marshaller.getStr(bb);
                    Facility f = facilities.get(facility);
                    if (f==null) return error(req, "No such facility");
                    StringBuilder sb = new StringBuilder();
                    sb.append("=== ").append(facility).append(" Status ===\n");
                    for (String d: daysCsv.split(",")) {
                        int di = Util.dayToIdx(d.trim());
                        sb.append(f.getDetailedAvailability(di));
                    }
                    return ok(req, sb.toString());
                }
                case Message.OP_BOOK: {
                    // Input: facility name, day, start time, end time
                    // Output: confirmation with unique booking ID
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
                    return ok(req, "CONFIRM# " + id);
                }
                case Message.OP_CHANGE: {
                    // Input: booking ID, shift minutes (positive=forward, negative=backward)
                    // Output: booking time shifted, duration unchanged
                    long id = bb.getLong();
                    int shiftMinutes = bb.getInt();

                    Booking b = null; Facility f=null;
                    for (Facility fx: facilities.values()) {
                        if (fx.bookings.containsKey(id)) { b=fx.bookings.get(id); f=fx; break; }
                    }
                    if (b==null || f==null) return error(req, "No such confirmation ID");

                    int newS = b.t.startMin + shiftMinutes;
                    int newE = b.t.endMin + shiftMinutes;
                    int newDay = b.t.day;

                    // Handle day boundary crossing
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

                    if (newDay < 0 || newDay > 6) {
                        return error(req, "Shift would move booking outside week range");
                    }

                    if (newE > 1440) {
                        return error(req, "Shift would exceed end of day");
                    }

                    BookingTime newBt = new BookingTime(newDay, newS, newE);

                    // Try to move booking
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
                case Message.OP_CANCEL: {
                    // Input: booking ID
                    // Output: cancellation confirmation (idempotent - same result on repeat)
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
                case Message.OP_EXTEND: {
                    // Input: booking ID, start delta, end delta
                    // Output: extended/shortened booking (non-idempotent - different result on repeat)
                    long id = bb.getLong();
                    int startDelta = bb.getInt(); // negative=earlier, positive=later
                    int endDelta = bb.getInt();   // negative=shorten, positive=extend

                    Booking b = null; Facility f=null;
                    for (Facility fx: facilities.values()) {
                        if (fx.bookings.containsKey(id)) { b=fx.bookings.get(id); f=fx; break; }
                    }
                    if (b==null || f==null) return error(req, "No such confirmation ID");

                    int newS = b.t.startMin + startDelta;
                    int newE = b.t.endMin + endDelta;

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

                    f.free(b.t);

                    if (!f.isFree(newBt)) {
                        f.occupy(b.t);
                        return error(req, "Unavailable for new period");
                    }

                    f.occupy(newBt);
                    b.t = newBt;
                    broadcastMonitorSafe(b.facility);

                    String msg = "EXTENDED# " + id + " (start " + (startDelta>=0?"+":"") + startDelta + " min, end " + (endDelta>=0?"+":"") + endDelta + " min)";
                    return ok(req, msg);
                }
                case Message.OP_MONITOR_REGISTER: {
                    // Input: facility name, monitoring duration in seconds
                    // Output: registration confirmation, then real-time updates
                    String facility = Marshaller.getStr(bb);
                    int seconds = bb.getInt();
                    Facility f = facilities.get(facility);
                    if (f==null) return error(req, "No such facility");
                    monitors.add(new MonitorClient(from, port, facility, Instant.now().plusSeconds(seconds)));
                    broadcastMonitorSafe(facility);
                    return ok(req, "MONITORING# "+facility+" for "+seconds+"s");
                }
                case Message.OP_QUERY_BOOKING: {
                    // Input: booking ID
                    // Output: booking details (facility, day, time, duration)
                    long id = bb.getLong();
                    Booking b = null;
                    Facility f = null;
                    for (Facility fx: facilities.values()) {
                        if (fx.bookings.containsKey(id)) {
                            b = fx.bookings.get(id);
                            f = fx;
                            break;
                        }
                    }

                    if (b == null || f == null) {
                        return error(req, "No booking found with ID: " + id);
                    }

                    String dayName = Util.idxToDay(b.t.day);
                    String startTime = Util.minToHm(b.t.startMin);
                    String endTime = Util.minToHm(b.t.endMin);

                    StringBuilder sb = new StringBuilder();
                    sb.append("=== Booking Details ===\n");
                    sb.append("Confirmation ID: ").append(id).append("\n");
                    sb.append("Facility: ").append(b.facility).append("\n");
                    sb.append("Day: ").append(dayName).append("\n");
                    sb.append("Time: ").append(startTime).append(" - ").append(endTime).append("\n");
                    sb.append("Duration: ").append(b.t.endMin - b.t.startMin).append(" minutes");

                    return ok(req, sb.toString());
                }
                default:
                    return error(req, "Unknown op");
            }
        } catch (Exception e) {
            return error(req, "Exception: "+e.getMessage());
        }
    }

    // Safely broadcast monitor updates
    private void broadcastMonitorSafe(String facility) {
        try { broadcastMonitorUpdates(facility); } catch (Exception ex) {
            System.out.println("Monitor broadcast failed: "+ex);
        }
    }

    // Build success reply
    private byte[] ok(Message req, String text) {
        byte[] payload = Marshaller.str(text);
        Message rep = new Message(req.semantics, req.opcode, req.reqId, payload);
        rep.version = Message.VERSION;
        return Marshaller.pack(rep);
    }

    // Build error reply
    private byte[] error(Message req, String text) {
        byte[] payload = Marshaller.str(text);
        Message rep = new Message(req.semantics, req.opcode, req.reqId, payload);
        rep.flags = 1;
        return Marshaller.pack(rep);
    }

    // Main server loop
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

    // Main entry point
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