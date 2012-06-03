/* 
 * NativeBOINC - Native BOINC Client with Manager
 * Copyright (C) 2011, Mateusz Szpakowski
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 */

package sk.boinc.nativeboinc.nativeclient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import sk.boinc.nativeboinc.BoincManagerActivity;
import sk.boinc.nativeboinc.BoincManagerApplication;
import sk.boinc.nativeboinc.NotificationController;
import sk.boinc.nativeboinc.R;
import sk.boinc.nativeboinc.debug.Logging;
import sk.boinc.nativeboinc.installer.ClientDistrib;
import sk.boinc.nativeboinc.installer.InstallerProgressListener;
import sk.boinc.nativeboinc.installer.InstallerService;
import sk.boinc.nativeboinc.installer.InstallerUpdateListener;
import sk.boinc.nativeboinc.installer.ProjectDistrib;
import sk.boinc.nativeboinc.util.ClientId;
import sk.boinc.nativeboinc.util.HostListDbAdapter;
import sk.boinc.nativeboinc.util.NotificationId;
import sk.boinc.nativeboinc.util.PreferenceName;
import sk.boinc.nativeboinc.util.ProcessUtils;
import sk.boinc.nativeboinc.util.TaskItem;
import sk.boinc.nativeboinc.util.UpdateItem;

import edu.berkeley.boinc.lite.Project;
import edu.berkeley.boinc.nativeboinc.ClientEvent;
import edu.berkeley.boinc.nativeboinc.ExtendedRpcClient;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.preference.PreferenceManager;
import android.util.Log;

/**
 * @author mat
 *
 */
public class NativeBoincService extends Service implements MonitorListener,
		InstallerProgressListener, InstallerUpdateListener {
	private final static String TAG = "NativeBoincService";

	private static final String PARTIAL_WAKELOCK_NAME = "RunnerPartial";
	private static final String SCREEN_WAKELOCK_NAME = "RunnerScreen";
	
	public class LocalBinder extends Binder {
		public NativeBoincService getService() {
			return NativeBoincService.this;
		}
	}
	
	private LocalBinder mBinder = new LocalBinder();
	
	private String mPendingError = null;
	private Object mPendingErrorSync = new Object(); // syncer
	
	private String mPendingServiceError = null;
	private Object mPendingServiceErrorSync = new Object(); // syncer
	
	private boolean mPreviousStateOfIsWorking = false;
	private boolean mIsWorking = false;
	
	public class ListenerHandler extends Handler {

		public void onClientStart() {
			// notify user
			startServiceInForeground();
			mNotificationController.removeClientNotification();
			
			AbstractNativeBoincListener[] listeners = mListeners.toArray(
					new AbstractNativeBoincListener[0]);
			for (AbstractNativeBoincListener listener: listeners)
				if (listener instanceof NativeBoincStateListener)
					((NativeBoincStateListener)listener).onClientStart();
		}
		
		public void onClientStop(int exitCode, boolean stoppedByManager) {
			// notify user
			stopServiceInForeground();
			// save stop reason
			mNotificationController.notifyClientEvent(getString(R.string.nativeClientShutdown),
					ExitCode.getExitCodeMessage(NativeBoincService.this,
							exitCode, stoppedByManager), true);
			
			AbstractNativeBoincListener[] listeners = mListeners.toArray(
					new AbstractNativeBoincListener[0]);
			for (AbstractNativeBoincListener listener: listeners)
				if (listener instanceof NativeBoincStateListener)
					((NativeBoincStateListener)listener).onClientStop(exitCode, stoppedByManager);
		}

		public void onNativeBoincError(String message) {
			boolean called = false;
			
			AbstractNativeBoincListener[] listeners = mListeners.toArray(
					new AbstractNativeBoincListener[0]);
			for (AbstractNativeBoincListener listener: listeners)
				if (listener.onNativeBoincClientError(message))
					called = true;
			
			synchronized(mPendingErrorSync) {
				if (!called) /* set up pending if not already handled */
					mPendingError = message;
				else	// if already handled
					mPendingError = null;
			}
		}
		
		public void nativeBoincServiceError(String message) {
			boolean called = false;
			
			AbstractNativeBoincListener[] listeners = mListeners.toArray(
					new AbstractNativeBoincListener[0]);
			for (AbstractNativeBoincListener listener: listeners)
				if (listener instanceof NativeBoincServiceListener) {
					if (((NativeBoincServiceListener)listener).onNativeBoincServiceError(message))
						called = true;
				}
			
			synchronized(mPendingServiceErrorSync) {
				if (!called) /* set up pending if not already handled */
					mPendingServiceError = message;
				else	// if already handled
					mPendingServiceError = null;
			}
		}

		public void onProgressChange(NativeBoincReplyListener callback, double progress) {
			if (mListeners.contains(callback))
				callback.onProgressChange(progress);
		}
		
		public void getTasks(NativeBoincTasksListener callback, ArrayList<TaskItem> tasks) {
			if (mListeners.contains(callback))
				callback.getTasks(tasks);
		}
		
		public void getProjects(NativeBoincProjectsListener callback, ArrayList<Project> projects) {
			if (mListeners.contains(callback))
				callback.getProjects(projects);
		}
		
		public void updatedProjectApps(String projectUrl) {
			AbstractNativeBoincListener[] listeners = mListeners.toArray(
					new AbstractNativeBoincListener[0]);
			
			for (AbstractNativeBoincListener listener: listeners)
				if (listener instanceof NativeBoincUpdateListener)
					((NativeBoincUpdateListener)listener).updatedProjectApps(projectUrl);
		}
		
		public void notifyChangeIsWorking(boolean isWorking) {
			AbstractNativeBoincListener[] listeners = mListeners.toArray(
					new AbstractNativeBoincListener[0]);
			
			for (AbstractNativeBoincListener listener: listeners)
				listener.onChangeRunnerIsWorking(isWorking);
		}
	}
	
	private ListenerHandler mListenerHandler = null;
	private MonitorThread.ListenerHandler mMonitorListenerHandler;
	
	private NotificationController mNotificationController = null;
	
	private int mBindCounter = 0;
	
	@Override
	public IBinder onBind(Intent intent) {
		if (Logging.DEBUG) Log.d(TAG, "OnBind");
		mBindCounter++;
		if (Logging.DEBUG) Log.d(TAG, "Bind. Bind counter: " + mBindCounter);
		startService(new Intent(this, NativeBoincService.class));
		return mBinder;
	}
	
	private WakeLock mDimWakeLock = null;
	private WakeLock mPartialWakeLock = null;
	
	private BoincManagerApplication mApp = null;
	
	@Override
	public void onCreate() {
		if (Logging.DEBUG) Log.d(TAG, "onCreate");
		PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
		
		mApp = (BoincManagerApplication)getApplication();
		mNotificationController = mApp.getNotificationController();
		
		mDimWakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, SCREEN_WAKELOCK_NAME);
		mPartialWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, PARTIAL_WAKELOCK_NAME);
		mListenerHandler = new ListenerHandler();
		
		mMonitorListenerHandler = MonitorThread.createListenerHandler();
		
		addMonitorListener(this);
	}
	
	private Runnable mRunnerStopper = null;
	
	private static final int DELAYED_DESTROY_INTERVAL = 4000;
	
	@Override
	public void onRebind(Intent intent) {
		mBindCounter++;
		if (Logging.DEBUG) Log.d(TAG, "Rebind. Bind counter: " + mBindCounter);
		if (mListenerHandler != null && mRunnerStopper != null) {
			if (Logging.DEBUG) Log.d(TAG, "Rebind");
			mListenerHandler.removeCallbacks(mRunnerStopper);
			mRunnerStopper = null;
		}
	}
	
	@Override
	public boolean onUnbind(Intent intent) {
		mBindCounter--;
		if (Logging.DEBUG) Log.d(TAG, "Unbind. Bind counter: " + mBindCounter);
		if (mNativeBoincThread == null && mBindCounter == 0) {
			if (Logging.DEBUG) Log.d(TAG, "nativeboincthread is null");
			mRunnerStopper = new Runnable() {
				@Override
				public void run() {
					if (Logging.DEBUG) Log.d(TAG, "Stop NativeBoincService");
					mRunnerStopper = null;
					stopSelf();
				}
			};
			mListenerHandler.postDelayed(mRunnerStopper, DELAYED_DESTROY_INTERVAL);
		}
		return true;
	}
	@Override
	public void onDestroy() {
		if (Logging.DEBUG) Log.d(TAG, "onDestroy");
		if (isRun()) {
			mWorkerThread.shutdownClient();
			
			try { // waits 5 seconds
				Thread.sleep(5000);
			} catch(InterruptedException ex) { }
			
			NativeBoincUtils.killAllNativeBoincs(this);
			NativeBoincUtils.killAllBoincZombies();
		}
		
		if (mWorkerThread != null)
			mWorkerThread.stopThread();
		if (mWakeLockHolder != null)
			mWakeLockHolder.destroy();
		if (mMonitorThread != null)
			mMonitorThread.quitFromThread();
		
		mListeners.clear();
		if (mDimWakeLock.isHeld()) {
			if (Logging.DEBUG) Log.d(TAG, "release screen lock");
			mDimWakeLock.release();
		}
		mDimWakeLock = null;
		if (mPartialWakeLock.isHeld()) {
			if (Logging.DEBUG) Log.d(TAG, "release screen lock");
			mPartialWakeLock.release();
		}
		mPartialWakeLock = null;
		
		mNotificationController = null;
	}
	
	private List<AbstractNativeBoincListener> mListeners = new ArrayList<AbstractNativeBoincListener>();
	
	private NativeBoincWorkerThread mWorkerThread = null;
	private MonitorThread mMonitorThread = null;
	
	private boolean mShutdownCommandWasPerformed = false;
	
	private Notification mServiceNotification = null;
	
	private boolean mDoRestart = false;
	// if restarted (used by BoincManagerActivity to determine whether reconnecting required)
	private boolean mStartAtRestarting = false; //  
	private boolean mIsRestarted = false;
	
	/*
	 * notifications
	 */
	private void startServiceInForeground() {
		String message = getString(R.string.nativeClientWorking);
		
		Intent intent = new Intent(this, BoincManagerActivity.class);
		intent.putExtra(BoincManagerActivity.PARAM_CONNECT_NATIVE_CLIENT, true);
		intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
				PendingIntent.FLAG_UPDATE_CURRENT);
		
		if (mServiceNotification == null) {
			mServiceNotification = new Notification(R.drawable.nativeboinc_alpha, message,
					System.currentTimeMillis());
		} else {
			mServiceNotification.when = System.currentTimeMillis();
		}
		mServiceNotification.setLatestEventInfo(getApplicationContext(),
				message, message, pendingIntent);
		
		startForeground(NotificationId.NATIVE_BOINC_SERVICE, mServiceNotification);
	}
	
	private void stopServiceInForeground() {
		stopForeground(true);
	}
		
	private class NativeBoincThread extends Thread {
		private static final String TAG = "NativeBoincThread";
		
		private boolean mIsRun = false;
		private boolean mSecondStart = false; 
		private int mBoincPid = -1;
		
		public NativeBoincThread(boolean secondStart) {
			this.mSecondStart = secondStart;
		}
		
		@Override
		public void run() {
			SharedPreferences globalPrefs = PreferenceManager.getDefaultSharedPreferences(
					NativeBoincService.this);
			
			boolean usePartial = globalPrefs.getBoolean(PreferenceName.POWER_SAVING, false);
			
			mShutdownCommandWasPerformed = false;
			
			String programName = NativeBoincService.this.getFileStreamPath("boinc_client")
					.getAbsolutePath();
			
			String[] boincArgs = new String[] { "--parent_lifecycle" };
			
			boolean allowRemoteAccess = globalPrefs.getBoolean(PreferenceName.NATIVE_REMOTE_ACCESS, true);
			
			/* choose boinc arguments (depends on AllowRemoteAccess option) */
			if (allowRemoteAccess)
				boincArgs = new String[] { "--parent_lifecycle", "--allow_remote_gui_rpc" };
			
			mBoincPid = ProcessUtils.exec(programName,
					NativeBoincService.this.getFileStreamPath("boinc").getAbsolutePath(),
					boincArgs);
			
			if (mBoincPid == -1) {
				if (Logging.ERROR) Log.e(TAG, "Running boinc_client failed");
				mNativeBoincThread = null;
				notifyClientError(NativeBoincService.this.getString(R.string.runNativeClientError));
				return;
			}
			
			try {
				Thread.sleep(400); // sleep 0.4 seconds
			} catch(InterruptedException ex) { }
			/* open client */
			if (Logging.DEBUG) Log.d(TAG, "Connecting with native client");
			
			ExtendedRpcClient rpcClient = new ExtendedRpcClient();
			boolean isRpcOpened = false;
			for (int i = 0; i < 4; i++) {
				if (rpcClient.open("127.0.0.1", 31416)) {
					isRpcOpened = true;
					break;
				} else {
					try {
						Thread.sleep(300); // sleep 0.3 seconds
					} catch(InterruptedException ex) { }
				}
			}
			
			if (!isRpcOpened) {
				if (Logging.ERROR) Log.e(TAG, "Connecting with native client failed");
				mNativeBoincThread = null;
				notifyClientError(getString(R.string.connectNativeClientError));
				killNativeBoinc();
				NativeBoincUtils.killAllBoincZombies();
				return;
			}
			
			/* authorize access */
			String password = null;
			try {
				password = NativeBoincUtils.getAccessPassword(NativeBoincService.this);
			} catch(IOException ex) {
				if (Logging.ERROR) Log.e(TAG, "Authorizing with native client failed");
				mNativeBoincThread = null;
				notifyClientError(getString(R.string.getAccessPasswordError));
				rpcClient.close();
				killNativeBoinc();
				NativeBoincUtils.killAllBoincZombies();
				return;
			}
			boolean isAuthorized = false;
			for (int i = 0; i < 4; i++) {
				if (Logging.DEBUG) Log.d(TAG, "Try to authorize with password:" + password);
				if (rpcClient.authorize(password)) {
					isAuthorized = true;
					break;
				}
			}
			if (!isAuthorized) {
				if (Logging.ERROR) Log.e(TAG, "Authorizing with native client failed");
				mNativeBoincThread = null;
				notifyClientError(getString(R.string.nativeAuthorizeError));
				killNativeBoinc();
				NativeBoincUtils.killAllBoincZombies();
				return;
			} 
			
			if (Logging.DEBUG) Log.d(TAG, "Acquire wake lock");
			if (!usePartial) {
				if (mDimWakeLock != null)
					mDimWakeLock.acquire();	// screen lock
			} else {
				if (mPartialWakeLock != null)
					mPartialWakeLock.acquire();	// partial lock
			}
			
			String monitorAuthCode = null;
			// authorize monitor
			if (Logging.DEBUG) Log.d(TAG, "Trying to authorize monitor access");
			try {
				Thread.sleep(300);
			} catch(InterruptedException ex) { }
			
			if (rpcClient != null) {
				monitorAuthCode = rpcClient.authorizeMonitor();
				if (monitorAuthCode == null) {
					if (Logging.INFO) Log.i(TAG, "Cant authorize monitor access"); 
				}
			}
			
			// worker thread
			ConditionVariable lock = new ConditionVariable(false);
			mWorkerThread = new NativeBoincWorkerThread(mListenerHandler, NativeBoincService.this, lock, rpcClient);
			mWorkerThread.start();
			
			boolean runningOk = lock.block(200); // Locking until new thread fully runs
			if (!runningOk) {
				if (Logging.ERROR) Log.e(TAG, "NativeBoincWorkerThread did not start in 0.2 second");
			} else 
				if (Logging.DEBUG) Log.d(TAG, "NativeBoincWorkerThread started successfully");
			
			// monitor thread
			if (monitorAuthCode != null) {
				mMonitorThread = new MonitorThread(mMonitorListenerHandler, monitorAuthCode);
				mMonitorThread.start();
			}
			
			// configure client
			if (mSecondStart) {
				if (Logging.DEBUG) Log.d(TAG, "Configure native client");
				if (!rpcClient.setGlobalPrefsOverride(NativeBoincUtils.INITIAL_BOINC_CONFIG))
					notifyClientError(NativeBoincService.this.getString(R.string.nativeClientConfigError));
				else if (!rpcClient.readGlobalPrefsOverride())
					notifyClientError(NativeBoincService.this.getString(R.string.nativeClientConfigError));
			}
			
			mIsRun = true;
			// restart after reinstall/update
			mApp.setRestartedAfterReinstall();
			notifyClientStart();
			
			int exitCode = 0;
			
			try { /* waiting to quit */
				if (Logging.DEBUG) Log.d(TAG, "Waiting for boinc_client");
				exitCode = ProcessUtils.waitForProcess(mBoincPid);
			} catch(InterruptedException ex) {
			}
			
			rpcClient = null;
			
			if (Logging.DEBUG) Log.d(TAG, "boinc_client has been finished");
			
			killNativeBoinc();
			NativeBoincUtils.killAllBoincZombies();
			
			if (Logging.DEBUG) Log.d(TAG, "Release wake lock");
			if (mDimWakeLock != null && mDimWakeLock.isHeld())
				mDimWakeLock.release();	// screen unlock
			if (mPartialWakeLock != null && mPartialWakeLock.isHeld())
				mPartialWakeLock.release();	// screen unlock
			
			mIsRun = false;
			mNativeBoincThread = null;
			notifyClientStop(exitCode, mShutdownCommandWasPerformed);
		}
		
		public void killNativeBoinc() {
			android.os.Process.sendSignal(mBoincPid, 2);
			try {
				Thread.sleep(400);	// 0.4 second
			} catch (InterruptedException e) { }
			/* fallback killing (by using SIGKILL signal) */
			android.os.Process.sendSignal(mBoincPid, 9);
		}
	};
	
	private class NativeKillerThread extends Thread {
		private boolean mDontKill = false;
		
		@Override
		public void run() {
			try {
				Thread.sleep(10000);
			} catch(InterruptedException ex) { }
			
			if (!mDontKill && mNativeBoincThread != null) {
				if (Logging.DEBUG) Log.d(TAG, "Killer:Killing native boinc");
				mNativeBoincThread.interrupt();
			}
			else {
				if (Logging.DEBUG) Log.d(TAG, "Killer:Dont Killing native boinc");
			}
		}
		
		public void disableKiller() {
			mDontKill = true;
			interrupt();
		}
	};
	
		
	private NativeBoincThread mNativeBoincThread = null;
	private NativeKillerThread mNativeKillerThread = null;
	private WakeLockHolder mWakeLockHolder = null;
	
	public synchronized void addNativeBoincListener(AbstractNativeBoincListener listener) {
		mListeners.add(listener);
	}
	
	public synchronized void removeNativeBoincListener(AbstractNativeBoincListener listener) {
		mListeners.remove(listener);
	}
	
	public void addMonitorListener(MonitorListener listener) {
		mMonitorListenerHandler.addMonitorListener(listener);
	}
	
	public void removeMonitorListener(MonitorListener listener) {
		mMonitorListenerHandler.removeMonitorListener(listener);
	}
	
	/**
	 * first starting up client (ruunning in current thread)
	 */
	public static boolean firstStartClient(Context context) {
		if (Logging.DEBUG) Log.d(TAG, "Starting FirstStartThread");
		
		//String[] envArray = getEnvArray();
		
		int boincPid = -1;
		
		String programName = context.getFileStreamPath("boinc_client").getAbsolutePath();
		
		boincPid = ProcessUtils.exec(programName,
				context.getFileStreamPath("boinc").getAbsolutePath(),
				new String[] { "--allow_remote_gui_rpc" });
		
		Log.d(TAG, "First start client, pid:"+boincPid);
		
		if (boincPid == -1) {
			if (Logging.ERROR) Log.d(TAG, "Running boinc_client failed");
			return false;
		}
		
		/* waiting and killing */
		try {
			Thread.sleep(500); // sleep 0.5 seconds
		} catch(InterruptedException ex) { }
		/* killing boinc client */
		
		android.os.Process.sendSignal(boincPid, 2);
		try {
			Thread.sleep(100);	// 0.1 second
		} catch (InterruptedException e) { }
		android.os.Process.sendSignal(boincPid, 2);
		try {
			Thread.sleep(100);	// 0.1 second
		} catch (InterruptedException e) { }
		android.os.Process.sendSignal(boincPid, 2);
		try {
			Thread.sleep(400);	// 0.4 second
		} catch (InterruptedException e) { }
		/* fallback killing (by using SIGKILL signal) */
		android.os.Process.sendSignal(boincPid, 9);
		
		return true;
	}
	
	public boolean handlePendingErrorMessage(AbstractNativeBoincListener listener) {
		synchronized(mPendingErrorSync) {
			if (mPendingError == null)
				return false;
			if (listener.onNativeBoincClientError(mPendingError)) {
				mPendingError = null;
				return true;
			}
			return false;
		}
	}
	
	private void clearPendingErrorMessage() {
		synchronized(mPendingErrorSync) {
			mPendingError = null;
		}
	}
	
	public boolean handlePendingServiceErrorMessage(NativeBoincServiceListener listener) {
		synchronized(mPendingServiceErrorSync) {
			if (mPendingServiceError == null)
				return false;
			if (listener.onNativeBoincServiceError(mPendingServiceError)) {
				mPendingServiceError = null;
				return true;
			}
			return false;
		}
	}
	
	private void clearPendingServiceErrorMessage() {
		synchronized(mPendingServiceErrorSync) {
			mPendingServiceError = null;
		}
	}
	
	/**
	 * starting up client
	 * @param secondStart - if true marks as second start during installation
	 */
	public void startClient(boolean secondStart) {
		if (Logging.DEBUG) Log.d(TAG, "Starting NativeBoincThread");
		if (mNativeBoincThread == null) {
			clearPendingErrorMessage();
			
			String text = getString(R.string.nativeClientStarting);
			mNotificationController.notifyClientEvent(text, text, false);
			
			// inform that service is working
			mIsWorking = true;
			notifyChangeIsWorking();
			
			mApp.bindRunnerService();
			
			/// update native client id (in host db)
			HostListDbAdapter dbAdapter = new HostListDbAdapter(this);
			try {
				dbAdapter.open();
				ClientId clientId = dbAdapter.fetchHost("nativeboinc");
				String password = NativeBoincUtils.getAccessPassword(this);
				clientId.setPassword(password);
				dbAdapter.updateHost(clientId);
			} catch(IOException ex) {
			} finally {
				dbAdapter.close();
			}
			
			mNativeBoincThread = new NativeBoincThread(secondStart);
			mNativeBoincThread.start();
			mWakeLockHolder = new WakeLockHolder(this, mPartialWakeLock, mDimWakeLock);
		}
	}
	
	/**
	 * shutting down native client
	 */
	public void shutdownClient() {
		if (Logging.DEBUG) Log.d(TAG, "Shutting down native client");
		if (mNativeBoincThread != null) {
			clearPendingErrorMessage();
			
			mShutdownCommandWasPerformed = true;
			/* start killer */
			String text = getString(R.string.nativeClientStopping);
			mNotificationController.notifyClientEvent(text, text, false);
			// inform that, service is working
			mIsWorking = true;
			notifyChangeIsWorking();
			
			mStartAtRestarting = false;
			mIsRestarted = false;
			
			mNativeKillerThread = new NativeKillerThread();
			mNativeKillerThread.start();
			mWorkerThread.shutdownClient();
		}
	}
	
	/**
	 * restarts native client
	 * @return
	 */
	public void restartClient() {
		if (Logging.DEBUG) Log.d(TAG, "Restarting client");
		if (mNativeBoincThread == null) {
			// normal start
			startClient(false); 
		} else {
			// restart (shutdown and start)
			mDoRestart = true;
			shutdownClient();
		}
	}
	
	public boolean ifStoppedByManager() {
		return mShutdownCommandWasPerformed;
	}
	
	public boolean serviceIsWorking() {
		return mIsWorking;
	}
	
	public boolean isRun() {
		if (mNativeBoincThread == null)
			return false;
		
		return mNativeBoincThread.mIsRun;
	}
	
	public boolean isRestarted() {
		return mIsRestarted;
	}
	
	/**
	 * returns current client state based on monitor events
	 * @return current client state
	 */
	public int getCurrentClientState() {
		if (mMonitorThread != null)
			return mMonitorThread.getCurrentClientState();
		return MonitorThread.STATE_UNKNOWN;
	}
	
	/**
	 * get Listener Handler
	 * @return listener handler
	 */
	public ListenerHandler getListenerHandler() {
		return mListenerHandler;
	}
	
	/**
	 * getGlobalProgress (concurrent version) 
	 */
	public void getGlobalProgress(NativeBoincReplyListener callback) {
		if (Logging.DEBUG) Log.d(TAG, "Get global progress");
		if (mWorkerThread != null) {
			clearPendingServiceErrorMessage();
			mWorkerThread.getGlobalProgress(callback);
		}
	}
	
	public void getTasks(NativeBoincTasksListener callback) {
		if (Logging.DEBUG) Log.d(TAG, "Get results");
		if (mWorkerThread != null) {
			clearPendingServiceErrorMessage();
			mWorkerThread.getTasks(callback);
		}
	}
	
	public void getProjects(NativeBoincProjectsListener callback) {
		if (Logging.DEBUG) Log.d(TAG, "Get projects");
		if (mWorkerThread != null) {
			clearPendingServiceErrorMessage();
			mWorkerThread.getProjects(callback);
		}
	}
	
	public void updateProjectApps(String projectUrl) {
		if (Logging.DEBUG) Log.d(TAG, "update project binaries");
		if (mWorkerThread != null) {
			clearPendingServiceErrorMessage();
			mWorkerThread.updateProjectApps(projectUrl);
		}
	}
	
	/* notifying methods */
	private synchronized void notifyClientStart() {
		// inform that, service finished work
		mIsWorking = false;
		if (mStartAtRestarting || mApp.restartedAfterReinstall()) {
			mIsRestarted = true;
			mStartAtRestarting = false;
		}
		
		notifyChangeIsWorking();
		
		mListenerHandler.post(new Runnable() {
			@Override
			public void run() {
				mListenerHandler.onClientStart();
			}
		});
	}
	
	private synchronized void notifyClientStop(final int exitCode, final boolean stoppedByManager) {
		mStartAtRestarting = false;
		mIsRestarted = false;
		
		mListenerHandler.post(new Runnable() {
			@Override
			public void run() {
				mNativeBoincThread = null;
				if (mNativeKillerThread != null) {
					mNativeKillerThread.disableKiller();
					mNativeKillerThread = null;
				}
				if (mWakeLockHolder != null) {
					mWakeLockHolder.destroy();
					mWakeLockHolder = null;
				}
				if (mWorkerThread != null) {
					mWorkerThread.stopThread();
					mWorkerThread = null;
				}
				if (mMonitorThread != null) {
					mMonitorThread.quitFromThread();
					mMonitorThread = null;
				}
				// inform that, service finished work
				mIsWorking = false;
				notifyChangeIsWorking();
				
				mListenerHandler.onClientStop(exitCode, stoppedByManager);
				
				if (mDoRestart) {
					if (Logging.DEBUG) Log.d(TAG, "After shutdown, start native client");
					mDoRestart = false;
					mStartAtRestarting = true;
					startClient(false);
				}
			}
		});
	}
	
	private synchronized void notifyClientError(final String message) {
		// inform that, service finished work
		mIsWorking = false;
		mStartAtRestarting = false;
		mIsRestarted = false;
		notifyChangeIsWorking();
		
		mListenerHandler.post(new Runnable() {
			@Override
			public void run() {
				mListenerHandler.onNativeBoincError(message);
			}
		});
	}
	
	private synchronized void notifyChangeIsWorking() {
		final boolean currentIsWorking = mIsWorking;
		if (mPreviousStateOfIsWorking != currentIsWorking) {
			if (Logging.DEBUG) Log.d(TAG, "Change is working:"+currentIsWorking);
			mPreviousStateOfIsWorking = currentIsWorking;
			mListenerHandler.post(new Runnable() {
				@Override
				public void run() {
					mListenerHandler.notifyChangeIsWorking(currentIsWorking);
				}
			});
		}
	}
	
	/**
	 * add project handling, queues and installer service bounding
	 */
	
	/**
	 * contains project urls
	 */
	private ArrayList<String> mPendingProjectAppsToInstall = new ArrayList<String>();
	/**
	 * contains project names (not projectUrls)
	 */
	private ArrayList<String> mInstalledProjectApps = new ArrayList<String>();

	private InstallerService mInstaller = null;
	
	private ServiceConnection mInstallerConn = new ServiceConnection() {
		
		@Override
		public void onServiceDisconnected(ComponentName name) {
			Log.d(TAG, "Installer is unbounded");
			mInstaller.removeInstallerListener(NativeBoincService.this);
			mInstaller = null;
		}
		
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			Log.d(TAG, "Installer is bounded");
			mInstaller = ((InstallerService.LocalBinder)service).getService();
			mInstaller.addInstallerListener(NativeBoincService.this);
			// update installed distrib list before installing applications (IMPORTANT)
			mInstaller.synchronizeInstalledProjects();
			
			mInstallerInBounding = false;
			
			// do install
			synchronized(mPendingProjectAppsToInstall) {
				String[] toInstall = mPendingProjectAppsToInstall.toArray(new String[0]);
				mPendingProjectAppsToInstall.clear();
				for (String projectUrl: toInstall)
					installProjectApplication(projectUrl);
			}
		}
	};
	
	private boolean mInstallerInBounding = false;
	private boolean mIfProjectDistribsListUpdated = false;
	
	/**
	 * real project installtion routine
	 * @param projectUrl
	 */
	private void installProjectApplication(String projectUrl) {
		String projectName = mInstaller.resolveProjectName(projectUrl);
		
		if (projectName == null) {	// do nothing if not found
			if (Logging.DEBUG) Log.d(TAG, "attached not found in distribs!!!");
			if (!mIfProjectDistribsListUpdated) { // if not updated before
				// add to pending tasks
				if (Logging.DEBUG) Log.d(TAG, "try to update project distribs");
				synchronized (mPendingProjectAppsToInstall) {
					mPendingProjectAppsToInstall.add(projectUrl);
				}
				// try to update project list
				mInstaller.updateProjectDistribList();
			} else {
				if (Logging.DEBUG) Log.d(TAG, "again not found. to finish");
				// simply finish task
				finishProjectApplicationInstallation(projectName);
			}
			return;
		}
		
		synchronized (mInstalledProjectApps) {
			mInstalledProjectApps.add(projectName);
		}
		
		mInstaller.installProjectApplicationsAutomatically(projectName, projectUrl);
	}
	
	private void startInstallProjectApplication(String projectUrl) {
		if (mInstaller == null) {
			Log.d(TAG, "Installer service not bound");
			/* if not initialized */
			synchronized(mPendingProjectAppsToInstall) {
				mPendingProjectAppsToInstall.add(projectUrl);
			}
			
			if (!mInstallerInBounding) {
				/* if already bounding */
				mInstallerInBounding = true;
				bindService(new Intent(this, InstallerService.class), mInstallerConn, BIND_AUTO_CREATE);
			}
		} else
			installProjectApplication(projectUrl);
	}

	@Override
	public void onMonitorEvent(ClientEvent event) {
		String title = null;
		switch(event.type) {
		case ClientEvent.EVENT_ATTACHED_PROJECT:
			mNotificationController.notifyClientEvent(getString(R.string.eventAttachedProject), 
					getString(R.string.eventAttachedProjectMessage, event.projectUrl), false);
			
			startInstallProjectApplication(event.projectUrl);
			break;
		case ClientEvent.EVENT_DETACHED_PROJECT:
			mNotificationController.notifyClientEvent(getString(R.string.eventDetachedProject), 
					getString(R.string.eventDetachedProjectMessage, event.projectUrl), false);
			break;
		case ClientEvent.EVENT_RUN_BENCHMARK:
			title = getString(R.string.eventBencharkRun);
			mNotificationController.notifyClientEvent(title, title, true);
			break;
		case ClientEvent.EVENT_FINISH_BENCHMARK:
			title = getString(R.string.eventBencharkFinished);
			mNotificationController.notifyClientEvent(title, title, true);
			break;
		case ClientEvent.EVENT_SUSPEND_ALL_TASKS:
			title = getString(R.string.eventSuspendAll);
			mNotificationController.notifyClientEvent(title, title, false);
			break;
		case ClientEvent.EVENT_RUN_TASKS:
			title = getString(R.string.eventRunTasks);
			mNotificationController.notifyClientEvent(title, title, false);
			break;
		}
	}
	
	private void finishProjectApplicationInstallation(String projectName) {
		boolean ifLast = false;
		synchronized (mInstalledProjectApps) {
			mInstalledProjectApps.remove(projectName);
			ifLast = mInstalledProjectApps.isEmpty();
			if (Logging.DEBUG) Log.d(TAG, "is last:"+ifLast);
		}

		if (ifLast)
			unboundInstallService();
	}
	
	private void unboundInstallService() {
		mIfProjectDistribsListUpdated = false;	// reset this indicator
		/* do unbound installer service */
		Log.d(TAG, "Unbound installer service");
		unbindService(mInstallerConn);
		mInstaller.removeInstallerListener(this);
		mInstaller = null;
	}

	@Override
	public void onOperation(String distribName, String opDescription) {
		// do nothing, ignore
	}

	@Override
	public void onOperationProgress(String distribName, String opDescription,
			int progress) {
		// do nothing
	}

	@Override
	public boolean onOperationError(String distribName, String errorMessage) {
		if (distribName != null) {
			// if error from project distribs update
			if (distribName.equals(InstallerService.BOINC_PROJECTS_DISTRIBS_UPDATE_ITEM_NAME)) {
				// is not distrib (updating project distrib list simply failed)
				if (Logging.DEBUG) Log.d(TAG, "on operation failed");
				synchronized (mPendingProjectAppsToInstall) {
					mPendingProjectAppsToInstall.clear();
				}
				
				boolean ifLast = false; 
				synchronized (mInstalledProjectApps) {
					ifLast = mInstalledProjectApps.isEmpty();
				}
				if (ifLast)
					unboundInstallService();
			} else if (distribName.length() != 0)
				finishProjectApplicationInstallation(distribName);
		}
		// do not consume error data
		return false;
	}

	@Override
	public void onOperationCancel(String distribName) {
		finishProjectApplicationInstallation(distribName);
	}

	@Override
	public void onOperationFinish(String distribName) {
		finishProjectApplicationInstallation(distribName);
	}

	@Override
	public void currentProjectDistribList(ArrayList<ProjectDistrib> projectDistribs) {
		if (Logging.DEBUG) Log.d(TAG, "on currentProjectDistrib"); 
		mIfProjectDistribsListUpdated = true;
		// try again
		synchronized(mPendingProjectAppsToInstall) {
			String[] toInstall = mPendingProjectAppsToInstall.toArray(new String[0]);
			mPendingProjectAppsToInstall.clear();
			for (String projectUrl: toInstall) {
				if (Logging.DEBUG) Log.d(TAG, "after updating:"+projectUrl);
				installProjectApplication(projectUrl);
			}
		}
	}

	@Override
	public void currentClientDistrib(ClientDistrib clientDistrib) {
		// TODO Auto-generated method stub
	}

	@Override
	public void onChangeInstallerIsWorking(boolean isWorking) {
		// TODO Auto-generated method stub
	}

	@Override
	public void binariesToUpdateOrInstall(UpdateItem[] updateItems) {
		// TODO Auto-generated method stub
	}

	@Override
	public void binariesToUpdateFromSDCard(String[] projectNames) {
		// TODO Auto-generated method stub
		
	}
}
