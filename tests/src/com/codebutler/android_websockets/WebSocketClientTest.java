package com.codebutler.android_websockets;

import junit.framework.TestCase;

public class WebSocketClientTest extends TestCase {

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
					public void onDisconnect(int code, String reason) {
						synchronized (lock) {
							errorReport = "onDisconnect invoked. " + code + " "
									+ reason;
							lock.notifyAll();
						}
					}

					@Override
					public void onConnect() {
						client.disconnect();
						synchronized (lock) {
							errorReport = null;
						}
					}
				}, null);
		client.connect();
		synchronized (lock) {
			lock.wait(2000);
			assertNull(errorReport, errorReport);
			assertTrue("socket is alived.", client.isSocketDestroyed());
			assertTrue("write thread is alived.", client.isWriteThreadDestroyed());
			assertTrue("read thread is alived.", client.isReadThreadDestroyed());
		}
	}
}
