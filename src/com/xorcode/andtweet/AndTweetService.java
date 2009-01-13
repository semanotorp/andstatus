/* 
 * Copyright (C) 2008 Torgny Bjers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.xorcode.andtweet;

import java.text.ChoiceFormat;
import java.text.DateFormat;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.util.Log;

import com.xorcode.andtweet.data.AndTweet;
import com.xorcode.andtweet.data.FriendTimeline;
import com.xorcode.andtweet.data.AndTweet.Tweets;
import com.xorcode.andtweet.net.Connection;
import com.xorcode.andtweet.view.TweetList;

/**
 * This is an application service that serves as a connection between Android
 * and Twitter. Other applications can interact with it via IPC.
 */
public class AndTweetService extends Service {

	private static final String TAG = "AndTweetService";

	/**
	 * This is a list of callbacks that have been registered with the service.
	 */
	final RemoteCallbackList<IAndTweetServiceCallback> mCallbacks = new RemoteCallbackList<IAndTweetServiceCallback>();

	int mTimelineValue = 0;
	int mReportValue = 0;
	long mLastRunTime = 0;

	private static final int MILLISECONDS = 1000;
	private static final int MSG_UPDATE_TIMELINE = 1;
	private static final int MSG_UPDATE_FRIENDS = 2;

	private String mUsername;
	private String mPassword;
	private int mFrequency = 180;

	private NotificationManager mNM;

	@Override
	public void onCreate() {
		// Set up the notification manager.
		mNM = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		// Start the time line updater.
		mHandler.sendEmptyMessage(MSG_UPDATE_TIMELINE);
		mHandler.sendEmptyMessage(MSG_UPDATE_FRIENDS);
		Log.d(TAG, "Service created in context: " + getApplication().getApplicationContext().getPackageName());
	}

	@Override
	public void onDestroy() {
		// Unregister all callbacks.
		mCallbacks.kill();

		Log.d(TAG, "Service destroyed");

		// Remove the next pending message to increment the counter, stopping
		// the increment loop.
		mHandler.removeMessages(MSG_UPDATE_TIMELINE);
	}

	@Override
	public IBinder onBind(Intent intent) {
		// Select the interface to return. If your service only implements
		// a single interface, you can just return it here without checking
		// the Intent.
		if (IAndTweetService.class.getName().equals(intent.getAction())) {
			return mBinder;
		}
		return null;
	}

	/**
	 * The IAndTweetService is defined through IDL
	 */
	private final IAndTweetService.Stub mBinder = new IAndTweetService.Stub() {
		public void registerCallback(IAndTweetServiceCallback cb) {
			if (cb != null)
				mCallbacks.register(cb);
		}

		public void unregisterCallback(IAndTweetServiceCallback cb) {
			if (cb != null)
				mCallbacks.unregister(cb);
		}
	};

	/**
	 * Our Handler used to execute operations on the main thread. This is used
	 * to schedule updates of timeline data from Twitter.
	 */
	private final Handler mHandler = new Handler() {

		@Override
		public void handleMessage(Message msg) {
			final SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
			final int N = mCallbacks.beginBroadcast();
			switch (msg.what) {
			case MSG_UPDATE_FRIENDS:
				if (sp.contains("automatic_updates") && sp.getBoolean("automatic_updates", false)) {
					loadFriends();
					sendMessageDelayed(obtainMessage(MSG_UPDATE_FRIENDS), 1800 * MILLISECONDS);
				} else {
					super.handleMessage(msg);
				}
				break;
			case MSG_UPDATE_TIMELINE:
				if (sp.contains("automatic_updates") && sp.getBoolean("automatic_updates", false)) {
					for (int i = 0; i < N; i++) {
						try {
							mCallbacks.getBroadcastItem(i).dataLoading(1);
						} catch (RemoteException e) {
							Log.e(TAG, e.getMessage());
						}
					}
					mUsername = sp.getString("twitter_username", null);
					mPassword = sp.getString("twitter_password", null);
					final FriendTimeline friendTimeline = new FriendTimeline(getContentResolver(), mUsername, mPassword);
					int aNewTweets = friendTimeline.loadTimeline();
					for (int i = 0; i < N; i++) {
						try {
							mCallbacks.getBroadcastItem(i).dataLoading(0);
						} catch (RemoteException e) {
							Log.e(TAG, e.getMessage());
						}
					}
					if (aNewTweets > 0) {
						notifyNewTweets(aNewTweets);
						Log.d(TAG, aNewTweets + " new tweets");
					}
					mFrequency = Integer.parseInt(sp.getString("fetch_frequency", "180"));
					mLastRunTime = Long.valueOf(System.currentTimeMillis());
					// Broadcast new value to all clients
					for (int i = 0; i < N; i++) {
						try {
							mCallbacks.getBroadcastItem(i).tweetsChanged(aNewTweets);
						} catch (RemoteException e) {
							Log.e(TAG, e.getMessage());
						}
					}
					mCallbacks.finishBroadcast();
					// Repeat mFrequency seconds (defaults to 180, 3 minutes)
					sendMessageDelayed(obtainMessage(MSG_UPDATE_TIMELINE), mFrequency * MILLISECONDS);
				} else {
					super.handleMessage(msg);
				}
				break;

			default:
				super.handleMessage(msg);
			}
		}
	};

	private final void loadFriends() {
		Log.i(TAG, "Load friends called");
		final ContentResolver contentResolver = getContentResolver();
		final SharedPreferences sp = PreferenceManager
				.getDefaultSharedPreferences(getApplicationContext());
		mUsername = sp.getString("twitter_username", null);
		mPassword = sp.getString("twitter_password", null);
		if (mUsername != null && mUsername.length() > 0) {
			Log.i(TAG, "Loading friends");
			Connection aConn = new Connection(mUsername, mPassword);
			try {
				JSONArray jArr = aConn.getFriends();
				for (int index = 0; index < jArr.length(); index++) {
					JSONObject jo = jArr.getJSONObject(index);
					ContentValues values = new ContentValues();

					// Construct the Uri to existing record
					Long lUserId = Long.parseLong(jo.getString("id"));
					Uri aUserUri = ContentUris.withAppendedId(AndTweet.Users.CONTENT_URI, lUserId);

					values.put(AndTweet.Users._ID, lUserId.toString());
					values.put(AndTweet.Users.AUTHOR_ID, jo.getString("screen_name"));

					if ((contentResolver.update(aUserUri, values, null, null)) == 0) {
						contentResolver.insert(AndTweet.Users.CONTENT_URI, values);
					}
				}
			} catch (JSONException e) {
				Log.e(TAG, e.getMessage());
			}
		}
	}

	/**
	 * Notify the user of new tweets.
	 * 
	 * @param numTweets
	 */
	private void notifyNewTweets(int numTweets) {
		// Set up the notification to display to the user
		Notification notification = new Notification(android.R.drawable.stat_notify_chat,
				(String) getText(R.string.notification_title), System.currentTimeMillis());
		notification.defaults = Notification.DEFAULT_ALL;
		notification.flags = Notification.FLAG_SHOW_LIGHTS | Notification.FLAG_AUTO_CANCEL;
		notification.ledOffMS = 1000;
		notification.ledOnMS = 500;
		notification.ledARGB = Color.GREEN;

		// Set up the pending intent
		PendingIntent contentIntent = PendingIntent.getActivity(this, numTweets, new Intent(this, TweetList.class), 0);

		// Set up the message
		MessageFormat form = new MessageFormat(getText(R.string.notification_new_tweet_format).toString());
		Object[] formArgs = new Object[] {numTweets};
		double[] tweetLimits = {1,2};
		String[] tweetPart = { getText(R.string.notification_tweet_singular).toString(), getText(R.string.notification_tweet_plural).toString() };
		ChoiceFormat tweetForm = new ChoiceFormat(tweetLimits, tweetPart);
		form.setFormatByArgumentIndex(0, tweetForm);
		String aMessage = form.format(formArgs); 

		// Set the latest event information and send the notification
		notification.setLatestEventInfo(this, getText(R.string.notification_title), aMessage, contentIntent);
		mNM.notify(R.string.app_name, notification);
	}
}
