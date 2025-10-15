import java.net.*;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.Scanner;

// UDP client for facility booking
public class Client {
    // Match server preloaded facilities
    private static final String[] FACILITIES = {"RoomA", "RoomB", "LT1"};

    private final InetAddress serverHost;
    private final int serverPort;
    private final byte semantics; // AMO or ALO
    private final Random rng;
    private final double lossRate; // Packet loss simulation
    private final DatagramSocket sock;

    public Client(String host, int port, byte semantics, double lossRate, long seed) throws Exception {
        this.serverHost = InetAddress.getByName(host);
        this.serverPort = port;
        this.semantics = semantics;
        this.lossRate = lossRate;
        this.rng = new Random(seed);
        this.sock = new DatagramSocket();
        this.sock.setSoTimeout(1000); // 1s timeout
    }

    // Send request with packet loss simulation
    private void send(byte[] data) throws Exception {
        if (Util.shouldDrop(rng, lossRate)) {
            System.out.println("[DROP->] simulated drop of request ("+data.length+" bytes)");
            return;
        }
        DatagramPacket pkt = new DatagramPacket(data, data.length, serverHost, serverPort);
        sock.send(pkt);
    }

    // Send request and wait for reply with retries
    private Message requestReply(byte op, byte[] payload) throws Exception {
        long reqId = System.nanoTime();
        Message m = new Message(semantics, op, reqId, payload);
        byte[] data = Marshaller.pack(m);

        int tries = 0;
        while (true) {
            tries++;
            send(data);
            try {
                byte[] buf = new byte[2048];
                DatagramPacket rp = new DatagramPacket(buf, buf.length);
                sock.receive(rp);
                Message rep = Marshaller.unpack(rp.getData(), rp.getLength());
                if (rep.reqId != reqId) continue; // ignore stray
                return rep;
            } catch (SocketTimeoutException te) {
                System.out.println("[timeout] retrying... ("+tries+")");
                if (tries >= 8) throw new RuntimeException("No reply after retries");
            }
        }
    }

    // Build query payload: facility + days
    private static byte[] payloadQuery(String fac, String daysCsv) {
        byte[] a = Marshaller.str(fac);
        byte[] b = Marshaller.str(daysCsv);
        return Util.join(a,b);
    }

    // Build book payload: facility + day + start + end
    private static byte[] payloadBook(String fac, int day, int s, int e) {
        ByteBuffer bb = ByteBuffer.allocate(4 + fac.getBytes().length + 4 + 4 + 4);
        Marshaller.putStr(bb, fac);
        Marshaller.putI32(bb, day);
        Marshaller.putI32(bb, s);
        Marshaller.putI32(bb, e);
        return bb.array();
    }

    // Change payload: id + shift minutes (+ forward, - backward)
    private static byte[] payloadChange(long id, int shiftMinutes) {
        ByteBuffer bb = ByteBuffer.allocate(8 + 4);
        Marshaller.putI64(bb, id);
        Marshaller.putI32(bb, shiftMinutes);
        return bb.array();
    }

    // Extend/shorten booking: id + startDelta + endDelta
    private static byte[] payloadExtend(long id, int startDelta, int endDelta) {
        ByteBuffer bb = ByteBuffer.allocate(8 + 4 + 4);
        Marshaller.putI64(bb, id);
        Marshaller.putI32(bb, startDelta);
        Marshaller.putI32(bb, endDelta);
        return bb.array();
    }

    // Build cancel payload: booking ID
    private static byte[] payloadId(long id) {
        ByteBuffer bb = ByteBuffer.allocate(8);
        Marshaller.putI64(bb, id);
        return bb.array();
    }

    // Parse booking ID from user input
    private static long parseId(String s) {
        s = s.trim();
        if (s.startsWith("CONFIRM#"))    s = s.substring(8).trim();
        if (s.startsWith("CHANGED#"))    s = s.substring(8).trim();
        if (s.startsWith("CANCELED#"))   s = s.substring(9).trim();
        if (s.startsWith("EXTENDED#"))   s = s.substring(9).trim();
        if (s.startsWith("DUPLICATED#")) s = s.substring(11).trim();

        // Extract first continuous digit sequence only
        s = s.split("\\s+")[0]; // split by space, take first part
        s = s.replaceAll("[^0-9]", "");
        if (s.isEmpty()) {
            throw new IllegalArgumentException("Invalid ID: no digits found");
        }
        return Long.parseLong(s);
    }

    private static void printFacilitiesLine() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < FACILITIES.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(FACILITIES[i]);
        }
        System.out.println("Available facilities (server-preloaded): " + sb);
    }

    // Parse and display server reply
    private static void showReply(Message rep) {
        String s = Marshaller.getStr(ByteBuffer.wrap(rep.payload));
        if (rep.flags == 1) { // error
            System.out.println("ERROR: " + s);
            return;
        }
        String normalized = s.trim();
        // Only parse confirmation code for confirmation messages
        if (normalized.matches("^(CONFIRM|CHANGED|CANCELED|EXTENDED|DUPLICATED)#\\s*\\d+.*")) {
            int sharp = normalized.indexOf('#');
            String tag = normalized.substring(0, sharp).trim();
            long code = parseId(normalized);
            System.out.println("Result: " + tag);
            System.out.println("Code  : " + code + "  (<- save this code)");
        } else {
            // Other cases (e.g. MONITORING# RoomA for 10s) print as-is
            System.out.println(normalized);
        }
    }

    public void menu() throws Exception {
        Scanner sc = new Scanner(System.in);
        System.out.println("Client ready. Semantics="+(semantics==Message.SEM_AMO?"AMO":"ALO")+" lossRate="+lossRate);
        printFacilitiesLine(); // Show facilities on startup
        while (true) {
            System.out.println("\n--- Menu ---");
            System.out.println("1) Query availability");
            System.out.println("2) Book");
            System.out.println("3) Change booking (shift time, keep duration)");
            System.out.println("4) Monitor (blocking)");
            System.out.println("5) Cancel booking (idempotent)");
            System.out.println("6) Extend/Shorten booking (non-idempotent)");
            System.out.println("7) Query booking");
            System.out.println("0) Exit");
            System.out.print("> ");
            String choice = sc.nextLine().trim();
            try {
                switch (choice) {
                    case "1": {
                        printFacilitiesLine(); // Show facilities before input
                        System.out.print("Facility: "); String f = sc.nextLine().trim();
                        System.out.print("Days (e.g., Mon,Tue): "); String days = sc.nextLine().trim();
                        Message rep = requestReply(Message.OP_QUERY, payloadQuery(f, days));
                        showReply(rep);
                        break;
                    }
                    case "2": {
                        printFacilitiesLine();
                        System.out.print("Facility: "); String f = sc.nextLine().trim();
                        System.out.print("Start (e.g., Mon@09:00): "); String st = sc.nextLine().trim();
                        System.out.print("End   (e.g., Mon@10:30): "); String en = sc.nextLine().trim();
                        int d = Util.dayToIdx(st.split("@")[0]);
                        int s = Util.hmToMin(st.split("@")[1]);
                        int e = Util.hmToMin(en.split("@")[1]);
                        Message rep = requestReply(Message.OP_BOOK, payloadBook(f,d,s,e));
                        showReply(rep);
                        break;
                    }
                    case "3": {
                        System.out.print("Confirmation ID (code or full string): ");
                        long id = parseId(sc.nextLine());
                        // Shift time in minutes
                        System.out.print("Shift time (minutes, +forward / -backward, e.g., +60 or -30): ");
                        int shiftMin = Integer.parseInt(sc.nextLine().trim());
                        Message rep = requestReply(Message.OP_CHANGE, payloadChange(id, shiftMin));
                        showReply(rep);
                        break;
                    }
                    case "4": {
                        printFacilitiesLine();
                        System.out.print("Facility: "); String f = sc.nextLine().trim();
                        System.out.print("Monitor seconds: "); int sec = Integer.parseInt(sc.nextLine().trim());
                        ByteBuffer bb = ByteBuffer.allocate(4 + f.getBytes().length + 4);
                        Marshaller.putStr(bb, f);
                        Marshaller.putI32(bb, sec);
                        Message rep = requestReply(Message.OP_MONITOR_REGISTER, bb.array());
                        // Print monitoring message, no ID parsing
                        showReply(rep);

                        System.out.println("Waiting for updates (Ctrl+C to quit client if needed)...");
                        long end = System.currentTimeMillis() + sec*1000L + 1000;
                        while (System.currentTimeMillis() < end) {
                            try {
                                byte[] buf = new byte[2048];
                                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                                sock.receive(pkt);
                                Message upd = Marshaller.unpack(pkt.getData(), pkt.getLength());
                                if (upd.opcode == Message.OP_MONITOR_UPDATE) {
                                    ByteBuffer pb = ByteBuffer.wrap(upd.payload);
                                    String fac = Marshaller.getStr(pb);
                                    String text = Marshaller.getStr(pb);
                                    System.out.println("\n[UPDATE] "+fac+"\n"+text);
                                }
                            } catch (SocketTimeoutException te) {
                                // ignore timeouts during monitor wait
                            }
                        }
                        System.out.println("Monitor interval finished.");
                        break;
                    }
                    case "5": {
                        System.out.print("Confirmation ID (code or full string): ");
                        long id = parseId(sc.nextLine());
                        Message rep = requestReply(Message.OP_CANCEL, payloadId(id));
                        showReply(rep);
                        break;
                    }
                    case "6": {
                        System.out.print("Confirmation ID (code or full string): ");
                        long id = parseId(sc.nextLine());
                        System.out.println("Adjust start/end time (non-idempotent):");
                        System.out.print("Start delta (min, +later/-earlier, e.g. +30 or -15): ");
                        int startDelta = Integer.parseInt(sc.nextLine().trim());
                        System.out.print("End delta (min, +extend/-shorten, e.g. +60 or -30): ");
                        int endDelta = Integer.parseInt(sc.nextLine().trim());
                        Message rep = requestReply(Message.OP_EXTEND, payloadExtend(id, startDelta, endDelta));
                        showReply(rep);
                        break;
                    }
                    case "7": {
                        System.out.print("Confirmation ID (code or full string): ");
                        long id = parseId(sc.nextLine());
                        Message rep = requestReply(Message.OP_QUERY_BOOKING, payloadId(id));
                        showReply(rep);
                        break;
                    }
                    case "0":
                        System.out.println("Bye."); return;
                    default:
                        System.out.println("Invalid choice.");
                }
            } catch (Exception e) {
                System.out.println("ERROR: "+e.getMessage());
            }
        }
    }

    public static void main(String[] args) throws Exception {
        // Default params for easy run
        if (args.length == 0) {
            args = new String[]{"127.0.0.1", "5000", "AMO", "0.0", "777"};
            System.out.println("[INFO] No args, using default: 127.0.0.1 5000 AMO 0.0 777");
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        byte sem = "ALO".equalsIgnoreCase(args[2]) ? Message.SEM_ALO : Message.SEM_AMO;
        double loss = (args.length>=4) ? Double.parseDouble(args[3]) : 0.0;
        long seed = (args.length>=5) ? Long.parseLong(args[4]) : 0L;
        new Client(host, port, sem, loss, seed).menu();
    }
}