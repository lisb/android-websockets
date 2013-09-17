package com.codebutler.android_websockets;

import static java.lang.System.currentTimeMillis;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

import org.apache.http.NameValuePair;
import org.apache.http.message.BasicNameValuePair;

import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

/**
 * WARN: connection is not reusable. If a connection is closed once, don't call
 * connection again.
 */
public class WebSocketClient {
    private static final String TAG = "WebSocketClient";
    private static final String THREAD_NAME_WRITE = "websocket-write-thread";
    private static final long HEARTBEAT_INTERVAL_MARGIN = 10;

    private final URI                      mURI;
    private final Listener                 mListener;
    private final List<BasicNameValuePair> mExtraHeaders;
    private final FrameFactory          mFrameMarshaller;
    
    /** access on websocket-write-thread */
    private Socket                   mSocket;
    /** create on main thread, destroy on websocket-write-thread */
    private HandlerThread            mHandlerThread;
    private Handler                  mHandler;
    
    private Thread readThread;
    
    private volatile boolean         mHandShaked;    // modify on websocket read thread. read on websocket write thread.
    private volatile boolean         mDisconnectDispatched;  // modify on websocket read thread. read on websocket read thread and websocket write thread.
    private volatile boolean         mCloseReceived; // modify on websocket read thread. read on websocket read thread and websocket write thread.
    private boolean                  mCloseSent;     // modify on websocket write thread. read on websocket write thread;

    private final Object             mConnectionLock = new Object();

    private final Runnable           mHeartbeat;
    /** access from all thread */
    private long                     mHeartbeatInterval;
    private volatile long            mTimestamp; 
    
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
    }

    public Listener getListener() {
        return mListener;
    }
    
    void onConnect() {
    	mHandShaked = true;
    	if (mListener != null) {
    		mListener.onConnect();
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
    
    void onDisconnect(final int code, final String reason) {
    	if (mDisconnectDispatched) {
    		return;
    	}

    	mDisconnectDispatched = true;
    	if (mListener != null && mHandShaked) {
    		mListener.onDisconnect(code, reason);
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
    		postHeartbeat();
		}
	}
    
    void setTimestamp() {
    	mTimestamp = currentTimeMillis();
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

    public void connect() {
    	synchronized (mConnectionLock) {
	    	if (mHandlerThread != null && mHandlerThread.isAlive()) {
	        	Log.d(TAG, "WebSocket writing thread is existed.");
	            return;
	        }
	    	
	    	mHandlerThread = new HandlerThread(THREAD_NAME_WRITE);
	        mHandlerThread.start();
	        mHandler = new Handler(mHandlerThread.getLooper());
    	}

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

                    setTimestamp();
                    readThread = new WebSocketReadThread(WebSocketClient.this);
                    readThread.start();
		        } catch (IOException ex) {
		        	onError(ex);
                } catch (KeyManagementException ex) {
                    onError(ex);
				} catch (NoSuchAlgorithmException ex) {
                    onError(ex);
				} catch (URISyntaxException ex) {
                    onError(ex);
				}
			}
		});
    }

	public void disconnect() {
		sendClose(1000,
				"the purpose for which the connection was established has been fulfilled.");
	}

	void sendClose(final int code, final String reason) {
		if (mHandlerThread != null && mHandlerThread.isAlive()) {
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
		synchronized (mConnectionLock) {
			if (mHandlerThread != null) {
				mHandlerThread.quit();
			}
		}
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
    	sendFrame(mFrameMarshaller.createPingFrame(message), false);
    }

	// for unit test.
	boolean isSocketDestroyed() {
		return mSocket == null || mSocket.isClosed();
	}
	
	// for unit test.
	boolean isWriteThreadDestroyed() {
		return mHandlerThread == null || !mHandlerThread.isAlive();
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

            setTimestamp();
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
				
				mHandler.removeCallbacks(this); // 二重で登録してしまわないように削除．
				final long dx = mTimestamp + mHeartbeatInterval - currentTimeMillis();
				if (dx < HEARTBEAT_INTERVAL_MARGIN) {
					sendPing("heartbeat");
					setTimestamp();
					mHandler.postDelayed(this, mHeartbeatInterval);
				} else {
					mHandler.postDelayed(this, dx);
				}
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
        public void onConnect();
        public void onMessage(String message);
        public void onMessage(byte[] data);
        public void onDisconnect(int code, String reason);
        public void onError(Exception error);
    }
}
