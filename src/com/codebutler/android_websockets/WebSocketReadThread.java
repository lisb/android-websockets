package com.codebutler.android_websockets;

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
public class WebSocketReadThread extends Thread {

	private static final String TAG = "WebSocketReadThread";
	private static final String THREAD_NAME = "websocket-read-thread";

	private final WebSocketClient mClient; 
	private final InputStream mInputStream;
	private final HybiParser mParser;

	public WebSocketReadThread(
			final WebSocketClient client) throws IOException {
		super(THREAD_NAME);
		this.mClient = client;
		this.mInputStream = client.getSocket().getInputStream();
		this.mParser = new HybiParser(client);
	}

	@Override
	public void run() {
		Log.i(TAG, "start WebSocket reading thread.");
		try {
			HybiParser.HappyDataInputStream stream = new HybiParser.HappyDataInputStream(
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

			mClient.setConnected(true);
			mClient.postHeartbeat();
			mClient.getListener().onConnect();

			// Now decode websocket frames.
			mParser.start(stream);
		} catch (IOException ex) {
			if  (mClient.isConnected()) {
				final String reason = getDisconnectReason(ex);
				Log.e(TAG, "WebSocket closed." + reason, ex);
				mClient.getListener().onError(ex);
				mClient.getListener().onDisconnect(0, reason);
			} else {
				Log.e(TAG, "Error occured after disconnected.", ex);
			}
		} catch (HttpException ex) {
			mClient.getListener().onError(ex);
		}

		mClient.setConnected(false);
		mClient.destroy();
		Log.d(TAG, "finish WebSocket reading thread. ");
	}
	
	private String getDisconnectReason (final IOException e) {
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
	private String readLine(HybiParser.HappyDataInputStream reader)
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
