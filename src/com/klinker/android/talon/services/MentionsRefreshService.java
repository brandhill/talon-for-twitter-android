package com.klinker.android.talon.services;

import android.app.IntentService;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.widget.RemoteViews;

import com.klinker.android.talon.R;
import com.klinker.android.talon.sq_lite.MentionsDataSource;
import com.klinker.android.talon.ui.MainActivity;
import com.klinker.android.talon.ui.MainActivityPopup;
import com.klinker.android.talon.utils.Utils;

import java.util.List;

import twitter4j.Paging;
import twitter4j.Twitter;
import twitter4j.TwitterException;
import twitter4j.User;

public class MentionsRefreshService extends IntentService {

    SharedPreferences sharedPrefs;

    public MentionsRefreshService() {
        super("MentionsRefreshService");
    }

    @Override
    public void onHandleIntent(Intent intent) {
        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        Context context = getApplicationContext();
        boolean update = false;
        int numberNew = 0;

        try {
            Twitter twitter = Utils.getTwitter(context);

            User user = twitter.verifyCredentials();
            long lastId = sharedPrefs.getLong("last_mention_id", 0);
            Paging paging;
            paging = new Paging(1, 50);

            List<twitter4j.Status> statuses = twitter.getMentionsTimeline(paging);

            boolean broken = false;

            // first try to get the top 50 tweets
            for (int i = 0; i < statuses.size(); i++) {
                if (statuses.get(i).getId() == lastId) {
                    statuses = statuses.subList(0, i);
                    broken = true;
                    break;
                }
            }

            // if that doesn't work, then go for the top 150
            if (!broken) {
                Paging paging2 = new Paging(1, 150);
                List<twitter4j.Status> statuses2 = twitter.getHomeTimeline(paging2);

                for (int i = 0; i < statuses2.size(); i++) {
                    if (statuses2.get(i).getId() == lastId) {
                        statuses2 = statuses2.subList(0, i);
                        break;
                    }
                }

                statuses = statuses2;
            }

            if (statuses.size() != 0) {
                sharedPrefs.edit().putLong("last_mention_id", statuses.get(0).getId()).commit();
                update = true;
                numberNew = statuses.size();
            } else {
                update = false;
                numberNew = 0;
            }

            MentionsDataSource dataSource = new MentionsDataSource(context);
            dataSource.open();

            for (twitter4j.Status status : statuses) {
                try {
                    dataSource.createTweet(status);
                } catch (Exception e) {
                    break;
                }
            }

            dataSource.close();

            int mId = 2;

            if (numberNew > 0) {
                RemoteViews remoteView = new RemoteViews("com.klinker.android.talon", R.layout.custom_notification);
                Intent popup = new Intent(context, MainActivityPopup.class);
                popup.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                PendingIntent popupPending =
                        PendingIntent.getActivity(
                                this,
                                0,
                                popup,
                                0
                        );
                remoteView.setOnClickPendingIntent(R.id.popup_button, popupPending);
                remoteView.setTextViewText(R.id.content, numberNew == 1 ? numberNew + " " + getResources().getString(R.string.new_mention) : numberNew + " " + getResources().getString(R.string.new_mentions));

                TypedArray a = context.getTheme().obtainStyledAttributes(new int[]{R.attr.mentionItem});
                int resource = a.getResourceId(0, 0);
                a.recycle();

                remoteView.setImageViewResource(R.id.icon, resource);

                NotificationCompat.Builder mBuilder =
                        new NotificationCompat.Builder(this)
                                .setSmallIcon(R.drawable.ic_action_accept_dark)
                                .setContent(remoteView);
                //.setContentTitle(getResources().getString(R.string.app_name))
                //.setContentText(numberNew + " new tweets");

                Intent resultIntent = new Intent(this, MainActivity.class);
                resultIntent.putExtra("open_to_page", 1);
                resultIntent.putExtra("from_notification", true);

                PendingIntent resultPendingIntent =
                        PendingIntent.getActivity(
                                this,
                                0,
                                resultIntent,
                                0
                        );

                mBuilder.setContentIntent(resultPendingIntent);
                NotificationManager mNotificationManager =
                        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                mNotificationManager.notify(mId, mBuilder.build());
            }

        } catch (TwitterException e) {
            // Error in updating status
            Log.d("Twitter Update Error", e.getMessage());
        }
    }
}