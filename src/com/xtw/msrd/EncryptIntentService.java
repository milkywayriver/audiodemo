package com.xtw.msrd;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import nochump.util.zip.EncryptZipEntry;
import nochump.util.zip.EncryptZipOutput;
import android.app.IntentService;
import android.content.Context;
import android.content.Intent;

/**
 * An {@link IntentService} subclass for handling asynchronous task requests in
 * a service on a separate handler thread.
 * <p>
 * TODO: Customize class - update intent actions, extra parameters and static
 * helper methods.
 */
public class EncryptIntentService extends IntentService {
	// TODO: Rename actions, choose action names that describe tasks that this
	// IntentService can perform, e.g. ACTION_FETCH_NEW_ITEMS
	private static final String ACTION_FOO = "com.xtw.msrd.action.FOO";
	private static final String ACTION_BAZ = "com.xtw.msrd.action.BAZ";

	// TODO: Rename parameters
	private static final String EXTRA_PARAM1 = "com.xtw.msrd.extra.PARAM1";
	private static final String EXTRA_PARAM2 = "com.xtw.msrd.extra.PARAM2";

	/**
	 * Starts this service to perform action Foo with the given parameters. If
	 * the service is already performing a task this action will be queued.
	 * 
	 * @see IntentService
	 */
	// TODO: Customize helper method
	public static void startActionFoo(Context context, String param1, String param2) {
		Intent intent = new Intent(context, EncryptIntentService.class);
		intent.setAction(ACTION_FOO);
		intent.putExtra(EXTRA_PARAM1, param1);
		intent.putExtra(EXTRA_PARAM2, param2);
		context.startService(intent);
	}

	public EncryptIntentService() {
		super("EncryptIntentService");
	}

	@Override
	protected void onHandleIntent(Intent intent) {
		if (intent != null) {
			final String action = intent.getAction();
			if (ACTION_FOO.equals(action)) {
				final String param1 = intent.getStringExtra(EXTRA_PARAM1);
				final String param2 = intent.getStringExtra(EXTRA_PARAM2);
				handleActionFoo(param1, param2);
			}
		}
	}

	/**
	 * Handle action Foo in the provided background thread with the provided
	 * parameters.
	 */
	private void handleActionFoo(String param1, String param2) {
		String path = param1;
		String zipPath = path.replace(".wav", ".zip");
		G.log("startNewEncrypt : " + path);
		FileInputStream fis = null;
		EncryptZipOutput mZipOutput = null;
		try {
			mZipOutput = new EncryptZipOutput(new FileOutputStream(zipPath), "123");
			mZipOutput.putNextEntry(new EncryptZipEntry(new File(path).getName()));

			fis = new FileInputStream(path);
			byte[] buffer = new byte[10 * 1024];
			while (true) {
				int len = fis.read(buffer, 0, buffer.length);
				if (len < 0) {
					break;
				}
				mZipOutput.write(buffer, 0, len);
			}
			mZipOutput.flush();
			mZipOutput.closeEntry();
		} catch (IOException e) {
			if (mZipOutput != null) {
				try {
					mZipOutput.close();
				} catch (IOException e1) {
					e1.printStackTrace();
				}
				mZipOutput = null;
			}
			e.printStackTrace();
		} finally {
			if (fis != null) {
				try {
					fis.close();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			if (mZipOutput != null) {
				try {
					mZipOutput.close();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			if (path.equals(G.sCurrentRecordFilePath)) {
				G.sCurrentRecordFilePath = null;
			}
			new File(path).delete();
			G.log("endEncrypt : " + path);
		}
	}
}