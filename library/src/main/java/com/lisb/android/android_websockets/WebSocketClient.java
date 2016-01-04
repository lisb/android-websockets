package com.lisb.android.android_websockets;

import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import org.apache.http.NameValuePair;
import org.apache.http.message.BasicNameValuePair;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

import static java.lang.System.currentTimeMillis;

/**
 * WARN: connection is not reusable. If a connection is closed once, don't call
 * connection again.
 */
public class WebSocketClient {
    private static final String TAG = "WebSocketClient";
    private static final String THREAD_NAME_WRITE = "websocket-write-thread";

    private final URI                      mURI;
    private final Listener                 mListener;
    private final List<BasicNameValuePair> mExtraHeaders;
    private final FrameFactory             mFrameMarshaller;
    private final HandlerThread            mHandlerThread;
    private final Handler                  mHandler;
    private final Runnable                 mHeartbeat;
    
    /** access on websocket-write-thread */
    private Socket                   mSocket;
    
    private Thread readThread;
    
    private volatile boolean         mHandShaked;    // modify on websocket read thread. read on websocket write thread.
    private volatile boolean         mDisconnectDispatched;  // modify on websocket read thread. read on websocket read thread and websocket write thread.
    private volatile boolean         mCloseReceived; // modify on websocket read thread. read on websocket read thread and websocket write thread.
    private boolean                  mCloseSent;     // modify on websocket write thread. read on websocket write thread;

    /** access from all thread. Must lock mHeartbeat. */
    private long mHeartbeatInterval;
	private Queue<Long> mPingTimestamps;
    private long mLastIO;
	private long mTimeout;

    private static volatile TrustManager[] sTrustManagers;

    public static void setTrustManagers(TrustManager[] tm) {
        sTrustManagers = tm;
    }

    public WebSocketClient(URI uri, Listener listener, List<BasicNameValuePair> extraHeaders) {
        mURI             = uri;
        mListener        = listener;
        mExtraHeaders    = extraHeaders;
        mFrameMarshaller = new FrameFactory();
        mHeartbeat       = new HeartBeat();
        mHandlerThread   = new HandlerThread(THREAD_NAME_WRITE);
        mHandlerThread.start();
        mHandler         = new Handler(mHandlerThread.getLooper());
		setLastIO();
        open();
    }

    public Listener getListener() {
        return mListener;
    }
    
    void onOpen() {
    	mHandShaked = true;
    	if (mListener != null) {
    		mListener.onOpen();
    	}
    }
    
    void onMessage(final String message) {
    	if (mListener != null) {
    		mListener.onMessage(message);
    	}
    }
    
    void onMessage(final byte[] data) {
    	if (mListener != null) {
    		mListener.onMessage(data);
    	}
    }
    
    void onCloseReceiverd() {
    	mCloseReceived = true;
    }
    
    void onClose(final int code, final String reason) {
    	if (mDisconnectDispatched) {
    		return;
    	}

    	mDisconnectDispatched = true;
    	if (mListener != null && mHandShaked) {
    		mListener.onClose(code, reason);
    	}
    }
    
	void onError(final Exception error) {
		if (mDisconnectDispatched) {
			Log.e(TAG, "Error occured after disconnect.", error);
		} else {
			Log.e(TAG, "Error occured.", error);
			if (mListener != null) {
				mListener.onError(error);
			}
		}
	}
    
    public void setHeartbeatInterval(long heartbeatInterval) {
    	synchronized (mHeartbeat) {
			this.mHeartbeatInterval = heartbeatInterval;
			validateTimeoutAndHeartbeatInterval();
			postHeartbeat();
		}
	}

	public void setTimeout(long mTimeout) {
		synchronized (mHeartbeat) {
			this.mTimeout = mTimeout;
			validateTimeoutAndHeartbeatInterval();
			checkTimeout();
		}
	}

	void setLastIO() {
		synchronized (mHeartbeat) {
			mLastIO = currentTimeMillis();
		}
    }

	void onPong(final String message) {
		synchronized (mHeartbeat) {
			if (mPingTimestamps != null) {
				mPingTimestamps.remove();
			}
		}
	}

	public boolean checkTimeout() {
		synchronized (mHeartbeat) {
			final long now = currentTimeMillis();
			if (mHeartbeatInterval > 0 && mPingTimestamps != null && !mPingTimestamps.isEmpty()) {
				final long timestamp = mPingTimestamps.peek();
				if (now >= timestamp + mHeartbeatInterval) {
					Log.e(TAG, "Heartbeat is timeout. now:" + now + ", timestamp:" + timestamp
							+ ", interval:" + mHeartbeatInterval);
					destroy();
					return true;
				}
			}

			if (mTimeout > 0 && now >= mLastIO + mTimeout) {
				Log.e(TAG, "IO is timeout. now:" + now + ", lastIO:" + mLastIO + ", timeout:" + mTimeout);
				destroy();
				return true;
			}
			return false;
		}
	}

	void validateTimeoutAndHeartbeatInterval() {
		if (mHeartbeatInterval > 0 && mTimeout > 0 && mTimeout < mHeartbeatInterval * 2) {
			Log.e(TAG, "timeout should be twice larger than heartbeat interval.");
			throw new IllegalArgumentException("timeout should be twice larger than heartbeat interval.");
		}
	}

    Socket getSocket() {
		return mSocket;
	}
    
	private boolean canSendFrame() {
		return mHandShaked && !mCloseReceived && !mCloseSent;
	}
	
	private boolean canSendClose() {
		return mHandShaked && !mCloseSent;
	}
    
    void postHeartbeat() {
    	synchronized (mHeartbeat) {
    		if (canSendFrame()) {
    			mHandler.removeCallbacks(mHeartbeat);
	    		if (mHeartbeatInterval > 0) {
	    			mHandler.post(mHeartbeat);
	    		}
    		}
		}
    }

    private void open() {
        mHandler.post(new Runnable() {
			@Override
			public void run() {
				try {
		            int port = (mURI.getPort() != -1) ? mURI.getPort() : ((mURI.getScheme().equals("wss") || mURI.getScheme().equals("https")) ? 443 : 80);

		            SocketFactory factory = (mURI.getScheme().equals("wss") || mURI.getScheme().equals("https")) ? getSSLSocketFactory() : SocketFactory.getDefault();
		            mSocket = factory.createSocket(mURI.getHost(), port);
		            String path = TextUtils.isEmpty(mURI.getPath()) ? "/" : mURI.getPath();
 		            if (!TextUtils.isEmpty(mURI.getQuery())) {
 		                path += "?" + mURI.getQuery();
 		            }

 		            String originScheme = mURI.getScheme().equals("wss") ? "https" : "http";
 		            URI origin = new URI(originScheme, "//" + mURI.getHost(), null);
                    PrintWriter out = new PrintWriter(mSocket.getOutputStream());
                    out.print("GET " + path + " HTTP/1.1\r\n");
                    out.print("Upgrade: websocket\r\n");
                    out.print("Connection: Upgrade\r\n");
                    out.print("Host: " + mURI.getHost() + ":" + port + "\r\n");
                    out.print("Origin: " + origin.toString() + "\r\n");
                    out.print("Sec-WebSocket-Key: " + createSecret() + "\r\n");
                    out.print("Sec-WebSocket-Version: 13\r\n");
                    if (mExtraHeaders != null) {
                        for (NameValuePair pair : mExtraHeaders) {
                            out.print(String.format("%s: %s\r\n", pair.getName(), pair.getValue()));
                        }
                    }
                    out.print("\r\n");
                    out.flush();

					setLastIO();

                    readThread = new WebSocketReadThread(WebSocketClient.this);
                    readThread.start();
		        } catch (IOException ex) {
					onError(ex);
					final String reason = WebSocketReadThread
							.getDisconnectReason(ex);
					Log.e(TAG, "WebSocket closed." + reason, ex);
					onClose(CloseCodes.CLOSE_ABNORMAL, reason);
                } catch (KeyManagementException ex) {
                    throw new RuntimeException(ex);
				} catch (NoSuchAlgorithmException ex) {
					throw new RuntimeException(ex);
				} catch (URISyntaxException ex) {
					throw new RuntimeException(ex);
				}
			}
		});
    }

	public void close() {
		sendClose(1000,
				"the purpose for which the connection was established has been fulfilled.");
	}

	void sendClose(final int code, final String reason) {
		if (mHandlerThread.isAlive()) {
			mHandler.post(new Runnable() {
				@Override
				public void run() {
					if (!mCloseSent) {
						final byte[] frame = mFrameMarshaller.createCloseFrame(
								code, reason);
						sendFrameSync(frame, true);
						mCloseSent = true;
						mHandler.postDelayed(new DestroyTask(), 5000);
					}
				}
			});
		}
	}
    
	// 読込用スレッドを閉じた後，書込用スレッドで終了処理を実施する
	void destroy() {
		mHandler.post(new DestroyTask());
	}
	
	private void closeSocket() {
		if (mSocket != null) {
			if (!mSocket.isClosed()) {
				try {
					Log.d(TAG, "Close socket.");
					mSocket.close();
				} catch (IOException ex) {
					Log.e(TAG, "Error while disconnecting",
							ex);
				}
			} else {
				Log.d(TAG, "Socket was closed already.");
			}
		}
	}
	
	private void interruptWriteThread() {
		mHandlerThread.quit();
	}

    public void send(String data) {
        sendFrame(mFrameMarshaller.createFrame(data), false);
    }

    public void send(byte[] data) {
        sendFrame(mFrameMarshaller.createFrame(data), false);
    }
    
    public void sendPong(final byte[] payload) {
    	sendFrame(mFrameMarshaller.createPongFrame(payload), false);
    }
    
    public void sendPing(final String message) {
		synchronized (mHeartbeat) {
			if (checkTimeout()) {
				return;
			}

			if (mPingTimestamps == null) {
				mPingTimestamps = new LinkedList<Long>();
			}
			mPingTimestamps.add(currentTimeMillis());
		}

    	sendFrame(mFrameMarshaller.createPingFrame(message), false);
    }

	// for unit test.
	boolean isSocketDestroyed() {
		return mSocket == null || mSocket.isClosed();
	}
	
	// for unit test.
	boolean isWriteThreadDestroyed() {
		return !mHandlerThread.isAlive();
	}
	
	// for unit test.
	boolean isReadThreadDestroyed() {
		return readThread == null || !readThread.isAlive();
	}

    private String createSecret() {
        byte[] nonce = new byte[16];
        for (int i = 0; i < 16; i++) {
            nonce[i] = (byte) (Math.random() * 256);
        }
        return Base64.encodeToString(nonce, Base64.DEFAULT).trim();
    }

    void sendFrameSync(final byte[] frame, final boolean closeFrame) {
		if (checkTimeout()) {
			return;
		}

    	try {
        	if (mSocket == null) {
        		Log.e(TAG, "Can't send frame because Socket is closed.");
        		return;
        	}
        	
        	if (!closeFrame && !canSendFrame()) {
        		Log.e(TAG, "Can't send normal frame.");
        		return;
        	}
        	
        	if (closeFrame && !canSendClose()) {
        		Log.e(TAG, "Can't send close frame.");
        		return;
        	}
        	
            OutputStream outputStream = mSocket.getOutputStream();
            outputStream.write(frame);
            outputStream.flush();

			setLastIO();
        } catch (IOException e) {
            onError(e);
        }
    }
    
    void sendFrame(final byte[] frame, final boolean closeFrame) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                sendFrameSync(frame, closeFrame);
            }
        });
    }

    private SSLSocketFactory getSSLSocketFactory() throws NoSuchAlgorithmException, KeyManagementException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, sTrustManagers, null);
        return context.getSocketFactory();
    }
    
    private class HeartBeat implements Runnable {
    	
		@Override
		public void run() {
			synchronized (this) {
				if (mHeartbeatInterval <= 0 || !canSendFrame()) {
					return;
				}

				sendPing("heartbeat");
				mHandler.removeCallbacks(this); // 二重で登録してしまわないように削除．
				mHandler.postDelayed(this, mHeartbeatInterval);
			}
		}
    	
    }
    
	private class DestroyTask implements Runnable {
		@Override
		public void run() {
			closeSocket();
			interruptWriteThread();
		}
	}

    public interface Listener {
        public void onOpen();
        public void onMessage(String message);
        public void onMessage(byte[] data);
        public void onClose(int code, String reason);
        public void onError(Exception error);
    }
}
