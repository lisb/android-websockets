package com.lisb.android.android_websockets;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

import javax.net.ssl.SSLException;

import org.apache.http.Header;
import org.apache.http.HttpException;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpResponseException;
import org.apache.http.message.BasicLineParser;

import android.text.TextUtils;
import android.util.Log;

// This thread is closed When the Socket is closed.
class WebSocketReadThread extends Thread {

	private static final String TAG = "WebSocketReadThread";
	private static final String THREAD_NAME = "websocket-read-thread";

	private final WebSocketClient mClient; 
	private final InputStream mInputStream;
	private final FrameHandler mFrameHandler;

	WebSocketReadThread(
			final WebSocketClient client) throws IOException {
		super(THREAD_NAME);
		this.mClient = client;
		this.mInputStream = client.getSocket().getInputStream();
		this.mFrameHandler = new FrameHandler(client);
	}

	@Override
	public void run() {
		Log.i(TAG, "start WebSocket reading thread.");
		try {
			FrameHandler.HappyDataInputStream stream = new FrameHandler.HappyDataInputStream(
					mInputStream);

			// Read HTTP response status line.
			StatusLine statusLine = parseStatusLine(readLine(stream));
			if (statusLine == null) {
				throw new HttpException("Received no reply from server.");
			} else if (statusLine.getStatusCode() != HttpStatus.SC_SWITCHING_PROTOCOLS) {
				throw new HttpResponseException(statusLine.getStatusCode(),
						statusLine.getReasonPhrase());
			}

			// Read HTTP response headers.
			String line;
			while (!TextUtils.isEmpty(line = readLine(stream))) {
				Header header = parseHeader(line);
				if (header.getName().equals("Sec-WebSocket-Accept")) {
					// FIXME: Verify the response...
				}
			}

			mClient.onOpen();
			mClient.postHeartbeat();

			// Now decode websocket frames.
		    mFrameHandler.start(stream);
		} catch (IOException ex) {
			mClient.onError(ex);
			final String reason = getDisconnectReason(ex);
			Log.e(TAG, "WebSocket closed." + reason, ex);
			mClient.onClose(CloseCodes.CLOSE_ABNORMAL, reason);	
		} catch (HttpException ex) {
			mClient.onError(ex);
			Log.e(TAG, "Received no reply from server.", ex);
			mClient.onClose(CloseCodes.CLOSE_ABNORMAL, "Received no reply from server.");	
		}

		mClient.destroy();
		Log.d(TAG, "finish WebSocket reading thread. ");
	}
	
	public static String getDisconnectReason (final IOException e) {
		if (e instanceof EOFException) {
			return "EOF";
		} else if (e instanceof SSLException) {
			return "SSL error";
		} else {
			return "IO error";
		}
	}
	

	private StatusLine parseStatusLine(String line) {
		if (TextUtils.isEmpty(line)) {
			return null;
		}
		return BasicLineParser.parseStatusLine(line, new BasicLineParser());
	}

	private Header parseHeader(String line) {
		return BasicLineParser.parseHeader(line, new BasicLineParser());
	}

	// Can't use BufferedReader because it buffers past the HTTP data.
	private String readLine(FrameHandler.HappyDataInputStream reader)
			throws IOException {
		int readChar = reader.read();
		if (readChar == -1) {
			return null;
		}
		StringBuilder string = new StringBuilder("");
		while (readChar != '\n') {
			if (readChar != '\r') {
				string.append((char) readChar);
			}

			readChar = reader.read();
			if (readChar == -1) {
				return null;
			}
		}
		return string.toString();
	}

}
