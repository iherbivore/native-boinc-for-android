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

package sk.boinc.nativeboinc;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import sk.boinc.nativeboinc.debug.Logging;
import sk.boinc.nativeboinc.installer.InstallerProgressListener;
import sk.boinc.nativeboinc.installer.InstallerService;
import sk.boinc.nativeboinc.util.ProgressItem;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * @author mat
 *
 */
public class ProgressActivity extends ServiceBoincActivity implements InstallerProgressListener {
	
	private static final String TAG = "ProgressActivity";
	
	private ProgressItem[] mCurrentProgress;
	
	public final static String ARG_GOTO_ACTIVITY = "GoTo";
	
	public final static int NO_GOTO = 0;
	public final static int GOTO_INSTALL_STEP_2 = 1;
	public final static int GOTO_INSTALL_STEP_FINISH = 2;
	
	private int mGoToActivity = NO_GOTO;
	
	private class ProgressCancelOnClickListener implements View.OnClickListener {

		private String mDistribName;
		private String mProjectUrl;
		
		public ProgressCancelOnClickListener(String distribName, String projectUrl) {
			this.mDistribName = distribName;
			this.mProjectUrl = projectUrl;
		}
		
		@Override
		public void onClick(View v) {
			/* */
			if (Logging.DEBUG) Log.d(TAG, "Canceled item:"+mDistribName);
			
			if (mDistribName.equals(InstallerService.BOINC_CLIENT_ITEM_NAME))
				mInstaller.cancelClientInstallation();
			else {
				if (mProjectUrl == null || mProjectUrl.length()==0)
					return;
				
				mInstaller.cancelProjectAppsInstallation(mProjectUrl);
			}
		}
	}
	
	private Map<String, ProgressCancelOnClickListener> mCurrentCancelClickListeners =
			new HashMap<String, ProgressCancelOnClickListener>();
	
	/* we update item outside adapter, because this notifyDataChanged makes some problems
	 * with cancel clicking */
	private void updateCreatedItemView(View view, int position) {
		TextView progressInfo = (TextView)view.findViewById(R.id.progressInfo);
		ProgressBar progress = (ProgressBar)view.findViewById(R.id.progressBar);
		Button cancelButton = (Button)view.findViewById(R.id.progressCancel);
		
		final ProgressItem item = mCurrentProgress[position];
		
		if (Logging.DEBUG) Log.d(TAG, "toAdapterView:"+item.name+""+item.opDesc+":"+
				item.progress+":"+item.state);
		
		switch(item.state) {
		case ProgressItem.STATE_IN_PROGRESS:
			progressInfo.setText(item.name + ": " + item.opDesc);
			break;
		case ProgressItem.STATE_CANCELLED:
			progressInfo.setText(item.name + ": " + getString(R.string.operationCancelled));
			break;
		case ProgressItem.STATE_ERROR_OCCURRED:
			progressInfo.setText(item.name + ": " + item.opDesc);
			break;
		case ProgressItem.STATE_FINISHED:
			progressInfo.setText(item.name + ": " + getString(R.string.operationFinished));
			break;
		}
		
		if (item.state == ProgressItem.STATE_IN_PROGRESS) {
			if (item.progress >= 0) {
				progress.setIndeterminate(false);
				progress.setProgress(item.progress);
			} else
				progress.setIndeterminate(true);
		} else
			progress.setVisibility(View.GONE);
		
		// disable button if end
		if (item.state != ProgressItem.STATE_IN_PROGRESS)
			cancelButton.setEnabled(false);
	}
	
	private class ProgressItemAdapter extends BaseAdapter {
		private Context mContext;
		
		public ProgressItemAdapter(Context context) {
			mContext = context;
		}

		@Override
		public int getCount() {
			if (mCurrentProgress == null)
				return 0;
			return mCurrentProgress.length;
		}

		@Override
		public Object getItem(int position) {
			return mCurrentProgress[position];
		}

		@Override
		public long getItemId(int position) {
			return 0;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			View view = convertView;
			if (convertView == null) {
				LayoutInflater inflater = (LayoutInflater)mContext
						.getSystemService(LAYOUT_INFLATER_SERVICE);
				view = inflater.inflate(R.layout.progress_list_item, null);
			}
			
			final ProgressItem item = mCurrentProgress[position];
			
			Button cancelButton = (Button)view.findViewById(R.id.progressCancel);
			updateCreatedItemView(view, position);
					
			ProgressCancelOnClickListener listener = mCurrentCancelClickListeners.get(item.name);
			// if new view created and listener not null then install listener
			if (convertView == null && listener != null) {
				if (Logging.DEBUG) Log.d(TAG, "Install cancel item listener");
				cancelButton.setOnClickListener(listener);
			}
			
			return view;
		}
	}
	
	private BoincManagerApplication mApp = null;
	
	private ListView mProgressList;
	private ProgressItemAdapter mProgressItemAdapter;
	
	private Button mNextButton = null;
	
	private TextView mProgressText = null;
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
		
		setUpService(false, false, false, false, true, true);
		super.onCreate(savedInstanceState);
		setContentView(R.layout.install_progress);
		
		mApp = (BoincManagerApplication) getApplication();
		
		mProgressItemAdapter = new ProgressItemAdapter(this);
		
		mProgressList = (ListView)findViewById(R.id.installProgressList);
		mProgressList.setAdapter(mProgressItemAdapter);
		
		Button cancelAll = (Button)findViewById(R.id.progressCancelAll);
		Button back = (Button)findViewById(R.id.progressBack);
		mNextButton = (Button)findViewById(R.id.progressNext);
		mProgressText = (TextView)findViewById(R.id.installProgressText);
		
		mGoToActivity = getIntent().getIntExtra(ARG_GOTO_ACTIVITY, NO_GOTO);
		
		if (Logging.DEBUG) Log.d(TAG, "GoToActivty: "+mGoToActivity);
		
		if (mGoToActivity != NO_GOTO) {
			back.setVisibility(View.GONE);
			mNextButton.setVisibility(View.VISIBLE);
			
			mNextButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					mInstaller.clearDistribInstallProgresses();
					finish();
					if (mGoToActivity == GOTO_INSTALL_STEP_2)
						startActivity(new Intent(ProgressActivity.this, InstallStep2Activity.class));
					// if install finish
				}
			});
		}
		
		cancelAll.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				if (mInstaller != null)
					mInstaller.cancelAll();
			}
		});
		
		back.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				mInstaller.clearDistribInstallProgresses();
				finish();
			}
		});
	}
	
	/* update item view outside list adapter */
	private void updateItemView(int position) {
		if (mProgressList == null)
			return;
		int firstVisible = mProgressList.getFirstVisiblePosition();
		int lastVisible = mProgressList.getLastVisiblePosition();
		if (firstVisible > position || lastVisible < position)
			return;
		
		View view = mProgressList.getChildAt(position-firstVisible);
		if (view != null)
			updateCreatedItemView(view, position);
	}

	public boolean areAllTasksNotRan() {
		for (ProgressItem progress: mCurrentProgress)
			if (progress.state == ProgressItem.STATE_IN_PROGRESS)
				return false;
		return true;
	}

	private void ifNothingInBackground() {
		if (areAllTasksNotRan()) {
			setProgressBarIndeterminateVisibility(false);
			if (mGoToActivity != NO_GOTO)
				mNextButton.setEnabled(true);
			
			mProgressText.setText(getString(R.string.noInstallOpsText));
		} else // if nothing working
			mProgressText.setText(getString(R.string.installProgressText));
	}
	
	private void getProgressFromInstaller() {
		if (mInstaller != null) {
			mCurrentProgress = mInstaller.getCurrentlyInstalledBinaries();
			if (mCurrentProgress == null)
				return; // nothing
			Arrays.sort(mCurrentProgress, mProgressCompatator);
			/* we update data with using adapter */
			mCurrentCancelClickListeners.clear();
			
			for (ProgressItem progress: mCurrentProgress)
				mCurrentCancelClickListeners.put(progress.name,
						new ProgressCancelOnClickListener(progress.name, progress.projectUrl));
			
			mProgressItemAdapter.notifyDataSetChanged();
			
			ifNothingInBackground();
		}
	}
	
	@Override
	protected void onStart() {
		super.onStart();
		
		getProgressFromInstaller();
	}
	
	@Override
	protected void onResume() {
		super.onResume();
		
		getProgressFromInstaller();
	}
	
	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_BACK) {
			if (mApp.isInstallerRun())
				mApp.installerIsRun(false);
			finish();
			return true; 
		}
		return super.onKeyDown(keyCode, event);
	}
	
	private Comparator<ProgressItem> mProgressCompatator = new Comparator<ProgressItem>() {
		@Override
		public int compare(ProgressItem p1, ProgressItem p2) {
			/* boinc client should in first position */
			if (p1.name.equals(InstallerService.BOINC_CLIENT_ITEM_NAME))
				return -1;
			if (p2.name.equals(InstallerService.BOINC_CLIENT_ITEM_NAME))
				return 1;
			return p1.name.compareTo(p2.name);
		}
	};
	
	@Override
	public void onInstallerConnected() {
		getProgressFromInstaller();
	}

	private int addNewProgressItem(String distribName) {
		ProgressItem progress = mInstaller.getCurrentStatusForDistribInstall(distribName);
		if (progress == null) // if not found
			return -1;
		
		int putIndex = -Arrays.binarySearch(mCurrentProgress, progress, mProgressCompatator)-1;
		
		if (putIndex<0)	// if duplicate
			return -1;
		// generate new array of progresses
		ProgressItem[] newProgressArray = new ProgressItem[mCurrentProgress.length+1];
		System.arraycopy(mCurrentProgress, 0, newProgressArray, 0, putIndex);
		newProgressArray[putIndex] = progress;
		if (putIndex<mCurrentProgress.length)
			System.arraycopy(mCurrentProgress, putIndex, newProgressArray, putIndex+1,
					mCurrentProgress.length-putIndex);
		
		mCurrentProgress = newProgressArray;
		
		mCurrentCancelClickListeners.put(distribName,
				new ProgressCancelOnClickListener(distribName, progress.projectUrl));
		return putIndex;
	}
	
	private int getProgressItem(String distribName) {
		ProgressItem progress = null;
		int position = 0;
		if (mCurrentProgress == null) {
			mCurrentProgress = new ProgressItem[0];
			position = addNewProgressItem(distribName);
			// add to list by updating
			mProgressItemAdapter.notifyDataSetChanged();
		}
		
		while (position < mCurrentProgress.length) {
			if (mCurrentProgress[position].name.equals(distribName)) {
				progress = mCurrentProgress[position];
				break;
			}
			position++;
		}
		
		if (progress == null) { // if not found
			position = addNewProgressItem(distribName);
			// add to list by updating
			mProgressItemAdapter.notifyDataSetChanged();
		}
		return position;
	}
	
	@Override
	public void onOperation(String distribName, String opDescription) {
		setProgressBarIndeterminateVisibility(true);
		int position = getProgressItem(distribName);
		if (position == -1)
			return;
		
		ProgressItem progress = mCurrentProgress[position];
		
		progress.state = ProgressItem.STATE_IN_PROGRESS;
		progress.opDesc = opDescription;
		progress.progress = -1;
		//mProgressItemAdapter.notifyDataSetChanged();
		updateItemView(position);
	}

	@Override
	public void onOperationProgress(String distribName, String opDescription, int progressValue) {
		setProgressBarIndeterminateVisibility(true);
		int position = getProgressItem(distribName);
		if (position == -1)
			return;
		
		ProgressItem progress = mCurrentProgress[position];
				
		progress.state = ProgressItem.STATE_IN_PROGRESS;
		progress.opDesc = opDescription;
		progress.progress = progressValue;
		//mProgressItemAdapter.notifyDataSetChanged();
		updateItemView(position);
	}

	@Override
	public void onOperationError(String distribName, String errorMessage) {
		int position = getProgressItem(distribName);
		if (position == -1)
			return;
		
		ProgressItem progress = mCurrentProgress[position];
		
		progress.state = ProgressItem.STATE_ERROR_OCCURRED;
		progress.opDesc = errorMessage;
		progress.progress = -1;
		//mProgressItemAdapter.notifyDataSetChanged();
		updateItemView(position);
		
		ifNothingInBackground();
	}
	
	@Override
	public void onOperationCancel(String distribName) {
		int position = getProgressItem(distribName);
		if (position == -1)
			return;
		
		ProgressItem progress = mCurrentProgress[position];
		
		progress.state = ProgressItem.STATE_CANCELLED;
		progress.opDesc = "";
		progress.progress = -1;
		mProgressItemAdapter.notifyDataSetChanged();
		
		updateItemView(position);
		ifNothingInBackground();
	}

	@Override
	public void onOperationFinish(String distribName) {
		int position = getProgressItem(distribName);
		if (position == -1)
			return;
		
		ProgressItem progress = mCurrentProgress[position];
		
		progress.state = ProgressItem.STATE_FINISHED;
		progress.opDesc = "";
		progress.progress = -1;
		mProgressItemAdapter.notifyDataSetChanged();
		
		updateItemView(position);
		ifNothingInBackground();
	}
}