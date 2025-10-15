import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class Marshaller {
    // Header: ver(1), sem(1), op(1), flags(1), reqId(8 BE), len(4 BE)
    public static byte[] pack(Message m) {
        int len = (m.payload == null) ? 0 : m.payload.length;
        ByteBuffer bb = ByteBuffer.allocate(1 + 1 + 1 + 1 + 8 + 4 + len);
        bb.put(m.version);
        bb.put(m.semantics);
        bb.put(m.opcode);
        bb.put(m.flags);
        bb.putLong(m.reqId);
        bb.putInt(len);
        if (len > 0) bb.put(m.payload);
        return bb.array();
    }

    public static Message unpack(byte[] data, int length) throws IllegalArgumentException {
        ByteBuffer bb = ByteBuffer.wrap(data, 0, length);
        Message m = new Message();
        m.version = bb.get();
        m.semantics = bb.get();
        m.opcode = bb.get();
        m.flags = bb.get();
        m.reqId = bb.getLong();
        int len = bb.getInt();
        if (len < 0 || len > bb.remaining()) throw new IllegalArgumentException("Bad length");
        if (len > 0) {
            m.payload = new byte[len];
            bb.get(m.payload);
        } else {
            m.payload = new byte[0];
        }
        return m;
    }

    // ------- Primitive helpers (big-endian) -------
    public static byte[] str(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.allocate(4 + b.length);
        bb.putInt(b.length);
        bb.put(b);
        return bb.array();
    }

    public static int putStr(ByteBuffer bb, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        bb.putInt(b.length);
        bb.put(b);
        return 4 + b.length;
    }

    public static String getStr(ByteBuffer bb) {
        int n = bb.getInt();
        byte[] b = new byte[n];
        bb.get(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    public static byte[] i32(int v) {
        ByteBuffer bb = ByteBuffer.allocate(4);
        bb.putInt(v);
        return bb.array();
    }

    public static byte[] i64(long v) {
        ByteBuffer bb = ByteBuffer.allocate(8);
        bb.putLong(v);
        return bb.array();
    }

    public static void putI32(ByteBuffer bb, int v) { bb.putInt(v); }
    public static void putI64(ByteBuffer bb, long v) { bb.putLong(v); }
    public static int getI32(ByteBuffer bb) { return bb.getInt(); }
    public static long getI64(ByteBuffer bb) { return bb.getLong(); }
}
