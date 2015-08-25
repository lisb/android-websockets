package com.lisb.android.android_websockets;

import junit.framework.TestCase;
import android.util.Log;

public class WebSocketClientTest extends TestCase {

	private static final String LOG_TAG = WebSocketClientTest.class
			.getSimpleName();
	private WebSocketClient client;
	private String errorReport;
	private Object lock = new Object();

	/**
	 * <ul>
	 * <li>disconnect を実行してもエラーが発生しないこと</li>
	 * <li>socket</li>
	 * </ul>
	 * 
	 * TODO jettyのantプラグインで自動でサーバーを立て，テストをマシン内で完結させる．
	 */
	public void testDisconnect() throws Exception {
		errorReport = "onConnect is not invoked.";
		client = new WebSocketClient(Config.SERVER_URI,
				new WebSocketClient.Listener() {
					@Override
					public void onMessage(byte[] data) {
					}

					@Override
					public void onMessage(String message) {
					}

					@Override
					public void onError(Exception error) {
						synchronized (lock) {
							errorReport = "onError invoked."
									+ error.getMessage();
							error.printStackTrace();
							lock.notifyAll();
						}
					}

					@Override
					public void onClose(int code, String reason) {
						Log.i(LOG_TAG, "onDisconnect. " + code + " " + reason);
					}

					@Override
					public void onOpen() {
						client.close();
						synchronized (lock) {
							errorReport = null;
						}
					}
				}, null);
		synchronized (lock) {
			lock.wait(5000);
			assertNull(errorReport, errorReport);
			assertTrue("socket is alived.", client.isSocketDestroyed());
			assertTrue("read thread is alived.", client.isReadThreadDestroyed());
			assertTrue("write thread is alived.",
					client.isWriteThreadDestroyed());
		}
	}
}
