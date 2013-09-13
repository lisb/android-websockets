package com.codebutler.android_websockets;

import java.util.Arrays;
import java.util.List;

 class Frames {

	static final int BYTE   = 255;
    static final int FIN    = 128;
    static final int MASK   = 128;
    static final int RSV1   =  64;
    static final int RSV2   =  32;
    static final int RSV3   =  16;
    static final int OPCODE =  15;
    static final int LENGTH = 127;

    static final int MODE_TEXT   = 1;
    static final int MODE_BINARY = 2;

    static final int OP_CONTINUATION =  0;
    static final int OP_TEXT         =  1;
    static final int OP_BINARY       =  2;
    static final int OP_CLOSE        =  8;
    static final int OP_PING         =  9;
    static final int OP_PONG         = 10;

    static final List<Integer> OPCODES = Arrays.asList(
        OP_CONTINUATION,
        OP_TEXT,
        OP_BINARY,
        OP_CLOSE,
        OP_PING,
        OP_PONG
    );

    static final List<Integer> FRAGMENTED_OPCODES = Arrays.asList(
        OP_CONTINUATION, OP_TEXT, OP_BINARY
    );
	
     
    static byte[] mask(byte[] payload, byte[] mask, int offset) {
        if (mask.length == 0) return payload;

        for (int i = 0; i < payload.length - offset; i++) {
            payload[offset + i] = (byte) (payload[offset + i] ^ mask[i % 4]);
        }
        return payload;
    }
}
