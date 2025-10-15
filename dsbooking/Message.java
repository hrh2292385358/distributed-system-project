public class Message {
    public static final byte VERSION = 1;
    public static final byte SEM_ALO = 0;
    public static final byte SEM_AMO = 1;

    public static final byte OP_QUERY = 1;
    public static final byte OP_BOOK = 2;
    public static final byte OP_CHANGE = 3;
    public static final byte OP_MONITOR_REGISTER = 4;
    public static final byte OP_MONITOR_UPDATE = 5; // server -> client
    public static final byte OP_CANCEL = 6;   // idempotent (extra)
    public static final byte OP_EXTEND = 7;   // non-idempotent: extend/shorten booking time
    public static final byte OP_QUERY_BOOKING = 8;  // query booking details by ID

    public static final byte FLAG_NONE = 0;

    public byte version = VERSION;
    public byte semantics = SEM_AMO;
    public byte opcode;
    public byte flags = FLAG_NONE;
    public long reqId;
    public byte[] payload;

    public Message() {}

    public Message(byte semantics, byte opcode, long reqId, byte[] payload) {
        this.semantics = semantics;
        this.opcode = opcode;
        this.reqId = reqId;
        this.payload = payload;
    }
}
