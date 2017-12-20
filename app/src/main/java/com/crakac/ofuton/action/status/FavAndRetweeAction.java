package com.crakac.ofuton.action.status;

import android.content.Context;
import android.os.Handler;

import com.crakac.ofuton.R;
import com.crakac.ofuton.adapter.TweetStatusAdapter;
import com.crakac.ofuton.util.AppUtil;
import com.crakac.ofuton.util.TwitterUtils;

import twitter4j.Status;
import twitter4j.Twitter;
import twitter4j.TwitterException;

/**
 * Created by Kosuke on 2017/12/18.
 */

public class FavAndRetweeAction extends ClickAction {
    private final Status selectedStatus;

    public FavAndRetweeAction(Context context, Status status) {
        super(context, R.string.fav_and_retweet, R.drawable.ic_fav_and_retweet);
        selectedStatus = status;
    }

    @Override
    public void doAction() {
        final Handler handler = new Handler(mContext.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                Twitter twitter = TwitterUtils.getTwitterInstance();
                Status result = null;

                boolean isFavoriteSuccess = false, isRetweetSuccess = false;
                try {
                    result = twitter.createFavorite(selectedStatus.getId());
                    isFavoriteSuccess = true;
                } catch (TwitterException e) {
                    AppUtil.showToast(R.string.something_wrong);
                }

                try {
                    twitter.retweetStatus(selectedStatus.getId());
                    isRetweetSuccess = true;
                } catch (TwitterException e) {
                    AppUtil.showToast(R.string.something_wrong);
                }

                handler.post(new OnActionResult(result, isFavoriteSuccess, isRetweetSuccess));
            }
        }).start();
    }

    class OnActionResult implements Runnable {
        boolean isFavouriteSuccess, isRetweetSuccess;
        Status status;

        OnActionResult(Status result, boolean isFavouriteSuccess, boolean isRetweetSuccess) {
            status = result;
            this.isFavouriteSuccess = isFavouriteSuccess;
            this.isRetweetSuccess = isRetweetSuccess;
        }

        @Override
        public void run() {
            if (!isFavouriteSuccess || !isRetweetSuccess) {
                AppUtil.showToast(R.string.something_wrong);
            }
            if (status == null)
                return;
            for (TweetStatusAdapter adapter : TweetStatusAdapter.getAdapters()) {
                int pos = adapter.getPosition(selectedStatus);
                if (pos < 0)
                    continue;
                adapter.remove(selectedStatus);
                adapter.insert(status, pos);
                adapter.notifyDataSetChanged();
            }
        }
    }
}