package com.xtw.msrd;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import nochump.util.zip.EncryptZipEntry;
import nochump.util.zip.EncryptZipOutput;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder.AudioSource;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Message;
import android.os.Process;
import android.util.Log;
import c7.CRChannel;
import c7.DC7;
import c7.DCAssist;
import c7.Frame;
import c7.LoginInfo;

import com.crearo.mpu.sdk.CameraThread;
import com.crearo.mpu.sdk.GPSHandler;
import com.crearo.mpu.sdk.client.MPUEntity;
import com.crearo.puserver.PUDataChannel;
import com.gjfsoft.andaac.MainActivity;

public class MyMPUEntity extends MPUEntity {

	private static final int FRAME_NUMBER_PER_FILE = 50000;
	protected static int SIZE = 4096;
	protected static final String TAG = "MyMPUEntity";
	private Thread mIAThread;
	private DC7 mIADc;
	private DC7 mIVDc;
	private int mBitRate = 64000;
	private boolean mResetFile = false;

	public void setBitRate(int br) {
		mBitRate = br;
	}

	public void resetFile() {
		mResetFile = true;
	}

	public MyMPUEntity(Context context) {
		super(context);
		File f = new File(G.sRootPath);
		f.mkdirs();
		// 默认录像
		startNewFile(createZipPath());
		checkThread();
	}

	protected List<PUDataChannel> mPDc = new ArrayList<PUDataChannel>();
	protected EncryptZipOutput mZipOutput;
	protected Object mZipOutputLock = new Object();
	private String mCurrentRecordFileName;

	public void addPUDataChannel(PUDataChannel pdc) {
		synchronized (mPDc) {
			this.mPDc.add(pdc);
		}
	}

	public void removePUDataChannel(PUDataChannel pdc) {
		synchronized (mPDc) {
			mPDc.remove(pdc);
		}
	}

	@Override
	public void handleMessage(Message msg) {
		super.handleMessage(msg);
		if (msg.what == 0x3600 || msg.what == 0x3601) {// puserverthread
			int resType = msg.arg1;
			PUDataChannel pdc = (PUDataChannel) msg.obj;

			if (resType == 0) {// iv
				CameraThread cameraThread = mCameraThread;
				if (cameraThread != null)
					if (msg.what == 0x3600) {
						cameraThread.addPUDataChannel(pdc);
					} else {
						cameraThread.removePUDataChannel(pdc);
					}
			} else if (resType == 1) {// ia
				if (msg.what == 0x3600) {
					if (mIAThread == null) {
						startOrRestart();
					}
					addPUDataChannel(pdc);
				} else {
					removePUDataChannel(pdc);
				}
			} else if (resType == 3) {
				GPSHandler gpsHandler = mGpsHandler;
				if (gpsHandler == null) {
					gpsHandler = new GPSHandler(mContext, null);
					mGpsHandler = gpsHandler;
				}
				if (msg.what == 0x3600) {
					gpsHandler.addPUDataChannel(pdc);
				} else {
					gpsHandler.removePUDataChannel(pdc);
				}
			}
		}
	}

	public void startOrRestart() {
		if (isAudioStarted()) {
			stopAudio();
		}
		Thread t = new Thread() {
			int mIdx = 0;

			public void run() {
				Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
				int Fr = 32000;
				final int audioSource = VERSION.SDK_INT >= VERSION_CODES.HONEYCOMB ? AudioSource.VOICE_COMMUNICATION
						: AudioSource.DEFAULT;
				int CC = AudioFormat.CHANNEL_IN_STEREO;
				int BitNum = AudioFormat.ENCODING_PCM_16BIT;
				int audioBuffer = AudioRecord.getMinBufferSize(Fr, CC, BitNum);
				Log.d(TAG, String.valueOf(audioBuffer));
				int size = SIZE;
				if (size < audioBuffer) {
					size = audioBuffer * 2;
				}
				AudioRecord ar = new AudioRecord(audioSource, Fr, CC, BitNum, size);
				size = SIZE;
				ar.startRecording();
				MainActivity mAac = new MainActivity();
				long mEncHandle = mAac.NativeEncodeOpen(2, Fr, 2, mBitRate);
				byte[] readBuf = new byte[size];
				byte[] outBuf = new byte[size];
				int read = 0;
				int loopCount = 0;
				while (mIAThread != null) {
					int cread = ar.read(readBuf, read, readBuf.length - read);
					if (cread < 0)
						break;
					read += cread;
					if (read < readBuf.length) {
						continue;
					}
					read = 0;

					int ret = mAac.NativeEncodeFrame(mEncHandle, readBuf, readBuf.length / 2,
							outBuf, size);
					if (ret <= 0) {
						continue;
					}
					// save begin
					if (isLocalRecord()) {
						if ((mIdx++ == FRAME_NUMBER_PER_FILE || mResetFile)) {
							mIdx = 0;
							mResetFile = false;
							String filePath = createZipPath();

							stopRecord();
							startNewFile(filePath);
						}
						recordFrame(outBuf, ret);
					}
					// save end

					final DC7 dc = mIADc;
					if (dc == null && !isLocalRecord() && mPDc.isEmpty()) {
						break;
					}
					// send
					boolean empty = mPDc.isEmpty();
					if (dc != null || !empty) {
						Frame frame = new Frame();
						frame.timeStamp = System.currentTimeMillis();
						/**
						 * BlockAlign 2 每个算法帧的包含的字节数 804 0x0324 Channels 1
						 * 通道个数,一般是1或2 1 0x01 BitsPerSample 1
						 * PCM格式时的采样精度，一般是8bit或16bit 16 0x10 SamplesPerSec 2
						 * PCM格式时的采样率除以100。例如：8K采样填80 320 0x0140 AlgFrmNum 2
						 * 后面算法帧的个数，这个值应该保持不变 01 0x01 ProducerID 2 厂商ID 01 0x01
						 * PCMLen 2 一个算法帧解码后的PCM数据长度 2048 0x0800 Reserved 4 保留
						 * //AudioData AlgFrmNum* BlockAlign
						 * AlgFrmNum个算法帧，每个算法帧的长度为BlockAlign。客户端解码时，
						 * 直接将这段数据分成BlockAlign的算法帧 ，然后分别送入ProducerID对应的解码库进行解码。
						 * AlgID 1 算法类型的ID 0x0a Rsv 3 保留
						 */
						ByteBuffer buffer = ByteBuffer.allocate(16 + 4 + 4 + ret);
						buffer.order(ByteOrder.LITTLE_ENDIAN);
						buffer.putShort((short) (0x0324));
						buffer.put((byte) 1);
						buffer.put((byte) 0x10);
						buffer.putShort((short) (Fr / 100));
						buffer.putShort((short) 0);
						buffer.putShort((short) 1);
						buffer.putShort((short) size);
						buffer.putInt(0);

						buffer.putInt(ret + 4); // 4 length
						buffer.put((byte) 0x0a);
						buffer.put((byte) 0);
						buffer.putShort((short) 0);
						buffer.put(outBuf, 0, ret);

						frame.data = buffer.array();
						frame.offset = 0;
						frame.length = frame.data.length;
						frame.keyFrmFlg = 1;
						frame.type = Frame.FRAME_TYPE_AUDIO;
						frame.mFrameIdx = loopCount++;

						ByteBuffer bf = null;
						if (dc != null) {
							try {
								bf = DCAssist.pumpFrame2DC(frame, dc, true).buffer;
							} catch (InterruptedException e) {
								e.printStackTrace();
								continue;
							}
						} else {
							bf = DCAssist.buildFrame(frame);
						}
						if (bf != null) {
							int lim = bf.limit();
							int pos = bf.position();
							synchronized (mPDc) {
								Iterator<PUDataChannel> it = mPDc.iterator();
								while (it.hasNext()) {
									PUDataChannel channel = (PUDataChannel) it.next();
									channel.pumpFrame(bf);
									bf.limit(lim);
									bf.position(pos);
								}
							}
						}
					}
				}
				mAac.NativeEncodeClose(mEncHandle);
				ar.release();
			}
		};
		mIAThread = t;
		t.start();
	}

	protected static void save2FileEncrypt(byte[] outBuf, int offset, int length, String filePath,
			boolean append) throws IOException {
		EncryptZipOutput out = new EncryptZipOutput(new FileOutputStream(filePath, append), "123");

		out.putNextEntry(new EncryptZipEntry(new File(filePath).getName()));
		out.write(outBuf, offset, length);
		out.flush();
		out.closeEntry();
		out.close();
	}

	@Override
	protected void handleStartWork(DC7 dc) {
		LoginInfo info = dc.getLoginInfo();
		switch (info.resType) {
		case IV:
			if (G.mPreviewVideo) {
				// VideoRunnable runnable = VideoRunnable.singleton();
				// if (runnable != null) {
				// runnable.setVideoDC(dc);
				// } else {
				// dc.close();
				// return;
				// }
			} else {
				mIVDc = dc;
			}
			break;
		default:
			super.handleStartWork(dc);
		}
	}

	@Override
	public void handleChannelError(CRChannel channel, int errorCode) {
		if (channel == sNc) {
			super.handleChannelError(channel, errorCode);
			return;
		}
		LoginInfo info = channel.getLoginInfo();
		info.resType.mIsAlive = false;
		switch (info.resType) {
		case IA: {
			// closeIAThread();
			final DC7 dc = mIADc;
			if (dc != null) {
				dc.close();
				mIADc = null;
				checkThread();
			}
			byte status = RendCallback.STT_REND_END;
			if (mRendCallback != null) {
				mRendCallback.onRendStatusFetched(info.resType, status);
			}
		}
		default:
			super.handleChannelError(channel, errorCode);
		}

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.crearo.mpu.sdk.client.MPUEntity#logout()
	 */
	@Override
	public void logout() {
		super.logout();
		// closeIAThread();
		DC7 dc = mIVDc;
		mIVDc = null;
		if (dc != null) {
			dc.close();
		}
		dc = mIADc;
		mIADc = null;
		if (dc != null) {
			dc.close();
		}
	}

	public void stopAudio() {

		Thread t = mIAThread;
		if (t != null) {
			mIAThread = null;
			try {
				t.join();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		stopRecord();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.crearo.mpu.sdk.MPUHandler#startIAWithDC(c7.DC7)
	 */
	@Override
	public void startIAWithDC(final DC7 dc) {
		// if (mIAThread != null) {
		// dc.close();
		// closeIAThread();
		// return;
		// }
		boolean needCheck = mIADc == null;
		mIADc = dc;
		if (needCheck) {
			checkThread();
		}
	}

	/**
	 * 
	 * @param data
	 * @param offset
	 * @param length
	 * @param topValue
	 *            设置的最高值，如果数组里有大于或者等于该值的，直接返回该值
	 * @param bigEndian
	 * @return
	 */
	public static short getMax(byte[] data, int offset, int length, short topValue,
			boolean bigEndian) {
		short max = 0;
		// ByteBuffer bf = ByteBuffer.wrap(data, offset, length);
		// ShortBuffer sf = bf.asShortBuffer();
		// for (int i = 0; i < sf.limit(); i++) {
		// short value = sf.get(i);
		// if (value > max) {
		// max = value;
		// }
		// }
		for (int i = offset; i < length;) {
			short h = data[i + 1];
			short l = data[i];
			short value = (short) (h * 256 + l);
			if (value >= topValue) {
				return topValue;
			}
			if (value > max) {
				max = value;
			}
			i += 2;
		}
		return max;
	}

	public boolean isLocalRecord() {
		return (mZipOutput != null);
	}

	public void setLocalRecord(boolean record) {
		boolean needCheck = (isLocalRecord() != record);

		if (record) {
			startNewFile(createZipPath());
		} else {
			stopRecord();
		}
		if (needCheck) {
			checkThread();
		}
	}

	private void stopRecord() {
		synchronized (mZipOutputLock) {
			mCurrentRecordFileName = null;
			EncryptZipOutput output = mZipOutput;
			if (output != null) {
				mZipOutput = null;
				try {
					output.flush();
					output.closeEntry();
					output.close();
				} catch (IOException e) {
					e.printStackTrace();
				}

			}
		}
	}

	public void startNewFile(String filePath) {
		synchronized (mZipOutputLock) {
			try {
				mZipOutput = new EncryptZipOutput(new FileOutputStream(filePath), "123");
				mCurrentRecordFileName = new File(filePath).getName();
				filePath = filePath.replace(".zip", ".aac");
				mZipOutput.putNextEntry(new EncryptZipEntry(new File(filePath).getName()));
			} catch (FileNotFoundException e) {
				e.printStackTrace();
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
			}
		}
	};

	private synchronized void checkThread() {
		if (mZipOutput == null && mIADc == null) {
			stopAudio();
		} else {
			startOrRestart();
		}
	}

	private void recordFrame(byte[] outBuf, int ret) {
		synchronized (mZipOutputLock) {
			EncryptZipOutput output = mZipOutput;
			if (output != null) {

				try {
					output.write(outBuf, 0, ret);
					output.flush();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}

				// CommonMethod.save2fileNoLength(outBuf, 0, ret,
				// filePath, true);
			}
		}
	}

	public String getRecordingFileName() {
		return mCurrentRecordFileName;
	}

	private String createZipPath() {
		SimpleDateFormat sdf = new SimpleDateFormat("yy-MM-dd", Locale.CHINA);
		Date date = new Date();
		String dirPath = sdf.format(date);
		File dirFile = new File(G.sRootPath, dirPath);
		dirFile.mkdirs();
		sdf = new SimpleDateFormat("HH.mm.ss", Locale.CHINA);

		String filePath = String.format("%s/%s.zip", dirFile.getPath(), sdf.format(date));
		return filePath;
	}

	public boolean isAudioStarted() {
		Thread mIAThread2 = mIAThread;
		return mIAThread2 != null && mIAThread2.isAlive();
	}
}