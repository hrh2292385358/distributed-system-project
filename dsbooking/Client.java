import java.net.*;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.Scanner;

// UDP client for facility booking system
public class Client {
    // Match server preloaded facilities
    private static final String[] FACILITIES = {"RoomA", "RoomB", "LT1"};

    private final InetAddress serverHost;
    private final int serverPort;
    private final byte semantics; // AMO or ALO
    private final Random rng;
    private final double lossRate; // Packet loss simulation
    private final DatagramSocket sock;

    // Initialize client with server connection and semantics
    public Client(String host, int port, byte semantics, double lossRate, long seed) throws Exception {
        this.serverHost = InetAddress.getByName(host);
        this.serverPort = port;
        this.semantics = semantics;
        this.lossRate = lossRate;
        this.rng = new Random(seed);
        this.sock = new DatagramSocket();
        this.sock.setSoTimeout(1000); // 1 second timeout
    }

    // Send packet with simulated loss
    private void send(byte[] data) throws Exception {
        if (Util.shouldDrop(rng, lossRate)) {
            System.out.println("[DROP->] simulated drop of request (" + data.length + " bytes)");
            return;
        }
        DatagramPacket pkt = new DatagramPacket(data, data.length, serverHost, serverPort);
        sock.send(pkt);
    }

    // Send request and wait for reply with retry
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
                if (rep.reqId != reqId) continue; // Ignore stray packets
                return rep;
            } catch (SocketTimeoutException te) {
                System.out.println("[timeout] retrying... (" + tries + ")");
                if (tries >= 8) throw new RuntimeException("No reply after retries");
            }
        }
    }

    // Build query payload
    private static byte[] payloadQuery(String fac, String daysCsv) {
        byte[] a = Marshaller.str(fac);
        byte[] b = Marshaller.str(daysCsv);
        return Util.join(a, b);
    }

    // Build booking payload
    private static byte[] payloadBook(String fac, int day, int s, int e) {
        ByteBuffer bb = ByteBuffer.allocate(4 + fac.getBytes().length + 4 + 4 + 4);
        Marshaller.putStr(bb, fac);
        Marshaller.putI32(bb, day);
        Marshaller.putI32(bb, s);
        Marshaller.putI32(bb, e);
        return bb.array();
    }

    // Build change booking payload
    private static byte[] payloadChange(long id, int shiftMinutes) {
        ByteBuffer bb = ByteBuffer.allocate(8 + 4);
        Marshaller.putI64(bb, id);
        Marshaller.putI32(bb, shiftMinutes);
        return bb.array();
    }

    // Build extend/shorten payload
    private static byte[] payloadExtend(long id, int startDelta, int endDelta) {
        ByteBuffer bb = ByteBuffer.allocate(8 + 4 + 4);
        Marshaller.putI64(bb, id);
        Marshaller.putI32(bb, startDelta);
        Marshaller.putI32(bb, endDelta);
        return bb.array();
    }

    // Build ID-only payload for cancel and query booking
    private static byte[] payloadId(long id) {
        ByteBuffer bb = ByteBuffer.allocate(8);
        Marshaller.putI64(bb, id);
        return bb.array();
    }

    // Parse booking ID from user input string
    private static long parseId(String s) {
        s = s.trim();
        if (s.startsWith("CONFIRM#"))    s = s.substring(8).trim();
        if (s.startsWith("CHANGED#"))    s = s.substring(8).trim();
        if (s.startsWith("CANCELED#"))   s = s.substring(9).trim();
        if (s.startsWith("EXTENDED#"))   s = s.substring(9).trim();
        if (s.startsWith("DUPLICATED#")) s = s.substring(11).trim();

        s = s.split("\\s+")[0];
        s = s.replaceAll("[^0-9]", "");
        if (s.isEmpty()) {
            throw new IllegalArgumentException("Invalid ID: no digits found");
        }
        return Long.parseLong(s);
    }

    // Print available facilities
    private static void printFacilitiesLine() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < FACILITIES.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(FACILITIES[i]);
        }
        System.out.println("Available facilities (server-preloaded): " + sb);
    }

    // Display server reply message
    private static void showReply(Message rep) {
        String s = Marshaller.getStr(ByteBuffer.wrap(rep.payload));
        if (rep.flags == 1) {
            System.out.println("ERROR: " + s);
            return;
        }
        String normalized = s.trim();
        if (normalized.matches("^(CONFIRM|CHANGED|CANCELED|EXTENDED|DUPLICATED)#\\s*\\d+.*")) {
            int sharp = normalized.indexOf('#');
            String tag = normalized.substring(0, sharp).trim();
            long code = parseId(normalized);
            System.out.println("Result: " + tag);
            System.out.println("Code  : " + code + "  (<- save this code)");
        } else {
            System.out.println(normalized);
        }
    }

    // Main menu loop
    public void menu() throws Exception {
        Scanner sc = new Scanner(System.in);
        System.out.println("Client ready. Semantics=" + (semantics == Message.SEM_AMO ? "AMO" : "ALO") + " lossRate=" + lossRate);
        printFacilitiesLine();
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
                        printFacilitiesLine();
                        System.out.print("Facility: "); String f = sc.nextLine().trim();
                        System.out.print("Days (e.g., Mon,Tue): "); String days = sc.nextLine().trim();
                        // Input: facility name, comma-separated days
                        // Output: detailed availability showing booked and free time slots
                        Message rep = requestReply(Message.OP_QUERY, payloadQuery(f, days));
                        showReply(rep);
                        break;
                    }
                    case "2": { // Book a facility
                        printFacilitiesLine();
                        System.out.print("Facility: "); String f = sc.nextLine().trim();
                        System.out.print("Start (e.g., Mon@09:00): "); String st = sc.nextLine().trim();
                        System.out.print("End   (e.g., Mon@10:30): "); String en = sc.nextLine().trim();
                        int d = Util.dayToIdx(st.split("@")[0]);
                        int s = Util.hmToMin(st.split("@")[1]);
                        int e = Util.hmToMin(en.split("@")[1]);
                        // Input: facility name, day, start time, end time
                        // Output: confirmation with unique booking ID
                        Message rep = requestReply(Message.OP_BOOK, payloadBook(f, d, s, e));
                        showReply(rep);
                        break;
                    }
                    case "3": { // Change a booking
                        System.out.print("Confirmation ID (code or full string): ");
                        long id = parseId(sc.nextLine());
                        System.out.print("Shift time (minutes, +forward / -backward, e.g., +60 or -30): ");
                        int shiftMin = Integer.parseInt(sc.nextLine().trim());
                        // Input: booking ID, shift minutes (positive=forward, negative=backward)
                        // Output: booking time shifted, duration unchanged
                        Message rep = requestReply(Message.OP_CHANGE, payloadChange(id, shiftMin));
                        showReply(rep);
                        break;
                    }
                    case "4": { // Monitor a facility
                        printFacilitiesLine();
                        System.out.print("Facility: "); String f = sc.nextLine().trim();
                        System.out.print("Monitor seconds: "); int sec = Integer.parseInt(sc.nextLine().trim());
                        // Input: facility name, monitoring duration in seconds
                        // Output: registration confirmation, then real-time updates
                        ByteBuffer bb = ByteBuffer.allocate(4 + f.getBytes().length + 4);
                        Marshaller.putStr(bb, f);
                        Marshaller.putI32(bb, sec);
                        Message rep = requestReply(Message.OP_MONITOR_REGISTER, bb.array());
                        showReply(rep);

                        System.out.println("Waiting for updates (Ctrl+C to quit client if needed)...");
                        long end = System.currentTimeMillis() + sec * 1000L + 1000;
                        // Loop to receive updates from the server.
                        while (System.currentTimeMillis() < end) {
                            try {
                                byte[] buf = new byte[2048];
                                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                                sock.receive(pkt);
                                Message upd = Marshaller.unpack(pkt.getData(), pkt.getLength());
                                // Check if it's a monitor update message.
                                if (upd.opcode == Message.OP_MONITOR_UPDATE) {
                                    ByteBuffer pb = ByteBuffer.wrap(upd.payload);
                                    String fac = Marshaller.getStr(pb);
                                    String text = Marshaller.getStr(pb);
                                    System.out.println("\n[UPDATE] " + fac + "\n" + text);
                                }
                            } catch (SocketTimeoutException te) {
                                // Timeouts are expected while waiting for asynchronous updates, so ignore them.
                            }
                        }
                        System.out.println("Monitor interval finished.");
                        break;
                    }
                    case "5": { // Cancel a booking
                        System.out.print("Confirmation ID (code or full string): ");
                        long id = parseId(sc.nextLine());
                        // Input: booking ID
                        // Output: cancellation confirmation (idempotent - same result on repeat)
                        Message rep = requestReply(Message.OP_CANCEL, payloadId(id));
                        showReply(rep);
                        break;
                    }
                    case "6": { // Extend or shorten a booking
                        System.out.print("Confirmation ID (code or full string): ");
                        long id = parseId(sc.nextLine());
                        System.out.println("Adjust start/end time (non-idempotent):");
                        System.out.print("Start delta (min, +later/-earlier, e.g. +30 or -15): ");
                        int startDelta = Integer.parseInt(sc.nextLine().trim());
                        System.out.print("End delta (min, +extend/-shorten, e.g. +60 or -30): ");
                        int endDelta = Integer.parseInt(sc.nextLine().trim());
                        // Input: booking ID, start delta, end delta
                        // Output: extended/shortened booking (non-idempotent - different result on repeat)
                        Message rep = requestReply(Message.OP_EXTEND, payloadExtend(id, startDelta, endDelta));
                        showReply(rep);
                        break;
                    }
                    case "7": { // Query a specific booking by ID
                        System.out.print("Confirmation ID (code or full string): ");
                        long id = parseId(sc.nextLine());
                        // Input: booking ID
                        // Output: booking details (facility, day, time, duration)
                        Message rep = requestReply(Message.OP_QUERY_BOOKING, payloadId(id));
                        showReply(rep);
                        break;
                    }
                    case "0":
                        System.out.println("Bye.");
                        return;
                    default:
                        System.out.println("Invalid choice.");
                }
            } catch (Exception e) {
                System.out.println("ERROR: " + e.getMessage());
            }
        }
    }

    // Main entry point
    public static void main(String[] args) throws Exception {
        // If no command-line arguments are provided, use default values for easy testing.
        if (args.length == 0) {
            args = new String[]{"127.0.0.1", "5000", "AMO", "0.0", "777"};
            System.out.println("[INFO] No args, using default: 127.0.0.1 5000 AMO 0.0 777");
        }
        // Parse command-line arguments.
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        byte sem = "ALO".equalsIgnoreCase(args[2]) ? Message.SEM_ALO : Message.SEM_AMO;
        double loss = (args.length >= 4) ? Double.parseDouble(args[3]) : 0.0;
        long seed = (args.length >= 5) ? Long.parseLong(args[4]) : 0L;
        // Create a new Client instance and start the main menu.
        new Client(host, port, sem, loss, seed).menu();
    }
}
