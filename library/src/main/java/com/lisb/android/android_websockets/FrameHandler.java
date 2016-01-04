//
// HybiParser.java: draft-ietf-hybi-thewebsocketprotocol-13 parser
//
// Based on code from the faye project.
// https://github.com/faye/faye-websocket-node
// Copyright (c) 2009-2012 James Coglan
//
// Ported from Javascript to Java by Eric Butler <eric@codebutler.com>
//
// (The MIT License)
//
// Permission is hereby granted, free of charge, to any person obtaining
// a copy of this software and associated documentation files (the
// "Software"), to deal in the Software without restriction, including
// without limitation the rights to use, copy, modify, merge, publish,
// distribute, sublicense, and/or sell copies of the Software, and to
// permit persons to whom the Software is furnished to do so, subject to
// the following conditions:
//
// The above copyright notice and this permission notice shall be
// included in all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
// EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
// MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
// NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
// LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
// OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
// WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

package com.lisb.android.android_websockets;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;

import android.util.Log;

class FrameHandler {
    private static final String TAG = FrameHandler.class.getSimpleName();

    private WebSocketClient mClient;

    private int     mStage;

    private boolean mFinal;
    private boolean mMasked;
    private int     mOpcode;
    private int     mLengthSize;
    private int     mLength;
    private int     mMode;

    private byte[] mMask    = new byte[0];
    private byte[] mPayload = new byte[0];

    private ByteArrayOutputStream mBuffer = new ByteArrayOutputStream();

    

    public FrameHandler(WebSocketClient client) {
        mClient = client;
    }

    public void start(HappyDataInputStream stream) throws IOException {
        while (true) {
            if (stream.available() == -1) break;
            switch (mStage) {
                case 0:
                	mClient.setLastIO();
                    parseOpcode(stream.readByte());
                    break;
                case 1:
                    parseLength(stream.readByte());
                    break;
                case 2:
                    parseExtendedLength(stream.readBytes(mLengthSize));
                    break;
                case 3:
                    mMask = stream.readBytes(4);
                    mStage = 4;
                    break;
                case 4:
                    mPayload = stream.readBytes(mLength);
                    emitFrame();
                    mStage = 0;
                    break;
            }
        }
        
    	mClient.onClose(CloseCodes.CLOSE_ABNORMAL, "EOF");
    }

    private void parseOpcode(byte data) throws ProtocolError {
        boolean rsv1 = (data & Frames.RSV1) == Frames.RSV1;
        boolean rsv2 = (data & Frames.RSV2) == Frames.RSV2;
        boolean rsv3 = (data & Frames.RSV3) == Frames.RSV3;

        if (rsv1 || rsv2 || rsv3) {
            throw new ProtocolError("RSV not zero");
        }

        mFinal   = (data & Frames.FIN) == Frames.FIN;
        mOpcode  = (data & Frames.OPCODE);
        mMask    = new byte[0];
        mPayload = new byte[0];

        if (!Frames.OPCODES.contains(mOpcode)) {
            throw new ProtocolError("Bad opcode");
        }

        if (!Frames.FRAGMENTED_OPCODES.contains(mOpcode) && !mFinal) {
            throw new ProtocolError("Expected non-final packet");
        }

        mStage = 1;
    }

    private void parseLength(byte data) {
        mMasked = (data & Frames.MASK) == Frames.MASK;
        mLength = (data & Frames.LENGTH);

        if (mLength >= 0 && mLength <= 125) {
            mStage = mMasked ? 3 : 4;
        } else {
            mLengthSize = (mLength == 126) ? 2 : 8;
            mStage      = 2;
        }
    }

    private void parseExtendedLength(byte[] buffer) throws ProtocolError {
        mLength = getInteger(buffer);
        mStage  = mMasked ? 3 : 4;
    }

    private void emitFrame() throws IOException {
        byte[] payload = Frames.mask(mPayload, mMask, 0);
        int opcode = mOpcode;

        if (opcode == Frames.OP_CONTINUATION) {
            if (mMode == 0) {
                throw new ProtocolError("Mode was not set.");
            }
            mBuffer.write(payload);
            if (mFinal) {
                byte[] message = mBuffer.toByteArray();
                if (mMode == Frames.MODE_TEXT) {
                    mClient.onMessage(encode(message));
                } else {
                    mClient.onMessage(message);
                }
                reset();
            }

        } else if (opcode == Frames.OP_TEXT) {
            if (mFinal) {
                String messageText = encode(payload);
                mClient.onMessage(messageText);
            } else {
                mMode = Frames.MODE_TEXT;
                mBuffer.write(payload);
            }

        } else if (opcode == Frames.OP_BINARY) {
            if (mFinal) {
                mClient.onMessage(payload);
            } else {
                mMode = Frames.MODE_BINARY;
                mBuffer.write(payload);
            }

        } else if (opcode == Frames.OP_CLOSE) {
            int    code   = (payload.length >= 2) ? 256 * payload[0] + payload[1] : 1005;
            String reason = (payload.length >  2) ? encode(slice(payload, 2))     : "";
            Log.d(TAG, "Got close op! " + code + " " + reason);
        	mClient.onClose(code, reason);
        	mClient.sendClose(code, reason);
        } else if (opcode == Frames.OP_PING) {
            if (payload.length > 125) { throw new ProtocolError("Ping payload too large"); }
            Log.d(TAG, "Sending pong!!");
            mClient.sendPong(payload);

        } else if (opcode == Frames.OP_PONG) {
            String message = encode(payload);
            mClient.onPong(message);
            Log.d(TAG, "Got pong! " + message);
        }
    }

    private void reset() {
        mMode = 0;
        mBuffer.reset();
    }

    private String encode(byte[] buffer) {
        try {
            return new String(buffer, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private int getInteger(byte[] bytes) throws ProtocolError {
        long i = byteArrayToLong(bytes, 0, bytes.length);
        if (i < 0 || i > Integer.MAX_VALUE) {
            throw new ProtocolError("Bad integer: " + i);
        }
        return (int) i;
    }
    
    /**
     * Copied from AOSP Arrays.java.
     */
    /**
     * Copies elements from {@code original} into a new array, from indexes start (inclusive) to
     * end (exclusive). The original order of elements is preserved.
     * If {@code end} is greater than {@code original.length}, the result is padded
     * with the value {@code (byte) 0}.
     *
     * @param original the original array
     * @param start the start index, inclusive
     * @param end the end index, exclusive
     * @return the new array
     * @throws ArrayIndexOutOfBoundsException if {@code start < 0 || start > original.length}
     * @throws IllegalArgumentException if {@code start > end}
     * @throws NullPointerException if {@code original == null}
     * @since 1.6
     */
    private static byte[] copyOfRange(byte[] original, int start, int end) {
        if (start > end) {
            throw new IllegalArgumentException();
        }
        int originalLength = original.length;
        if (start < 0 || start > originalLength) {
            throw new ArrayIndexOutOfBoundsException();
        }
        int resultLength = end - start;
        int copyLength = Math.min(resultLength, originalLength - start);
        byte[] result = new byte[resultLength];
        System.arraycopy(original, start, result, 0, copyLength);
        return result;
    }

    private byte[] slice(byte[] array, int start) {
        return copyOfRange(array, start, array.length);
    }

    public static class ProtocolError extends IOException {
		private static final long serialVersionUID = 3235810088403426201L;

		public ProtocolError(String detailMessage) {
            super(detailMessage);
        }
    }

    private static long byteArrayToLong(byte[] b, int offset, int length) {
        if (b.length < length)
            throw new IllegalArgumentException("length must be less than or equal to b.length");

        long value = 0;
        for (int i = 0; i < length; i++) {
            int shift = (length - 1 - i) * 8;
            value += (b[i + offset] & 0x000000FF) << shift;
        }
        return value;
    }

    public static class HappyDataInputStream extends DataInputStream {
        public HappyDataInputStream(InputStream in) {
            super(in);
        }

        public byte[] readBytes(int length) throws IOException {
            byte[] buffer = new byte[length];

            int total = 0;

            while (total < length) {
                int count = read(buffer, total, length - total);
                if (count == -1) {
                    break;
                }
                total += count;
            }

            if (total != length) {
                throw new IOException(String.format("Read wrong number of bytes. Got: %s, Expected: %s.", total, length));
            }

            return buffer;
        }
    }
}
