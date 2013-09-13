package com.codebutler.android_websockets;

import java.io.UnsupportedEncodingException;

import android.util.Log;

class FrameFactory {

	private static final String TAG = FrameFactory.class.getSimpleName();
	
	private boolean mMasking = true;
	
    public byte[] createFrame(String data) {
        return marshal(data, Frames.OP_TEXT, -1);
    }

    public byte[] createFrame(byte[] data) {
        return createFrame(data, Frames.OP_BINARY, -1);
    }

    private byte[] createFrame(byte[] data, int opcode, int errorCode)  {
        return createFrame((Object)data, opcode, errorCode);
    }

    private byte[] marshal(String data, int opcode, int errorCode) {
        return createFrame((Object)data, opcode, errorCode);
    }

    private byte[] createFrame(Object data, int opcode, int errorCode) {
        Log.d(TAG, "Creating frame for: " + data + " op: " + opcode + " err: " + errorCode);

        byte[] buffer = (data instanceof String) ? decode((String) data) : (byte[]) data;
        int insert = (errorCode > 0) ? 2 : 0;
        int length = buffer.length + insert;
        int header = (length <= 125) ? 2 : (length <= 65535 ? 4 : 10);
        int offset = header + (mMasking ? 4 : 0);
        int masked = mMasking ? Frames.MASK : 0;
        byte[] frame = new byte[length + offset];

        frame[0] = (byte) ((byte)Frames.FIN | (byte)opcode);

        if (length <= 125) {
            frame[1] = (byte) (masked | length);
        } else if (length <= 65535) {
            frame[1] = (byte) (masked | 126);
            frame[2] = (byte) Math.floor(length / 256);
            frame[3] = (byte) (length & Frames.BYTE);
        } else {
            frame[1] = (byte) (masked | 127);
            frame[2] = (byte) (((int) Math.floor(length / Math.pow(2, 56))) & Frames.BYTE);
            frame[3] = (byte) (((int) Math.floor(length / Math.pow(2, 48))) & Frames.BYTE);
            frame[4] = (byte) (((int) Math.floor(length / Math.pow(2, 40))) & Frames.BYTE);
            frame[5] = (byte) (((int) Math.floor(length / Math.pow(2, 32))) & Frames.BYTE);
            frame[6] = (byte) (((int) Math.floor(length / Math.pow(2, 24))) & Frames.BYTE);
            frame[7] = (byte) (((int) Math.floor(length / Math.pow(2, 16))) & Frames.BYTE);
            frame[8] = (byte) (((int) Math.floor(length / Math.pow(2, 8)))  & Frames.BYTE);
            frame[9] = (byte) (length & Frames.BYTE);
        }

        if (errorCode > 0) {
            frame[offset] = (byte) (((int) Math.floor(errorCode / 256)) & Frames.BYTE);
            frame[offset+1] = (byte) (errorCode & Frames.BYTE);
        }
        System.arraycopy(buffer, 0, frame, offset + insert, buffer.length);

        if (mMasking) {
            byte[] mask = {
                (byte) Math.floor(Math.random() * 256), (byte) Math.floor(Math.random() * 256),
                (byte) Math.floor(Math.random() * 256), (byte) Math.floor(Math.random() * 256)
            };
            System.arraycopy(mask, 0, frame, header, mask.length);
            Frames.mask(frame, mask, offset);
        }

        return frame;
    }

    public byte[] createPingFrame(String message) {
        return marshal(message, Frames.OP_PING, -1);
    }

    public byte[] createCloseFrame(int code, String reason) {
        return marshal(reason, Frames.OP_CLOSE, code);
    }
	
    public byte[] createPongFrame(byte[] payload) {
    	return createFrame(payload, Frames.OP_PONG, -1);
    }
    
    private byte[] decode(String string) {
        try {
            return (string).getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }
}
