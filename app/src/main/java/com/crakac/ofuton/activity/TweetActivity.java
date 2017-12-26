package com.crakac.ofuton.activity;

import android.Manifest;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.support.v4.content.FileProvider;
import android.support.v7.app.ActionBar;
import android.text.Editable;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.crakac.ofuton.C;
import com.crakac.ofuton.R;
import com.crakac.ofuton.service.StatusUpdateService;
import com.crakac.ofuton.util.AppUtil;
import com.crakac.ofuton.util.BitmapUtil;
import com.crakac.ofuton.util.PrefUtil;
import com.crakac.ofuton.util.SimpleTextChangeListener;
import com.crakac.ofuton.util.TwitterUtils;
import com.crakac.ofuton.util.Util;
import com.crakac.ofuton.widget.ColorOverlayOnTouch;
import com.esafirm.imagepicker.features.ImagePicker;
import com.esafirm.imagepicker.model.Image;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import twitter4j.Status;
import twitter4j.User;
import twitter4j.UserMentionEntity;

/**
 * ツイート作成アクティビティ
 *
 * @author Kosuke
 */
public class TweetActivity extends FinishableActionbarActivity implements View.OnClickListener {

    private static final String TAG = TweetActivity.class.getSimpleName();

    private static final int REQUEST_SELECT_PICTURE = 1;
    private static final int REQUEST_CAMERA = 2;

    private static final int EXTERNAL_STORAGE_PERMISSION_REQUEST = 11;
    private static final int CAMERA_PERMISSION_REQUEST = 12;

    private static final int MAX_TWEET_LENGTH = 140;
    private static final String IMAGE_URI = "IMAGE_URI";
    private static final int MAX_APPEND_FILES = 4;
    private static final int THUMBNAIL_SIZE = 120;//(dp)

    private EditText mInputText;
    private View mTweetBtn, mAppendPicBtn, mCameraBtn, mInfoBtn;// つぶやくボタン，画像追加ボタン，リプライ元情報ボタン
    private Uri mCameraUri;// カメラ画像添付用
    private long mReplyId;// reply先ID
    private String mReplyName;// リプライ先スクリーンネーム
    private String mHashTag;// ハッシュタグ
    private User mMentionUser;

    private LinearLayout mAppendedImageRoot;
    private ArrayList<Parcelable> mAppendedImages = new ArrayList<>(MAX_APPEND_FILES);
    private ArrayList<ImageView> mAppendedImageViews = new ArrayList(MAX_APPEND_FILES);
    private ImageView mLastTappedView;

    private String mPhotoPath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // レイアウトを読み込み
        setContentView(R.layout.activity_tweet);

        mInputText = findViewById(R.id.input_text);// 入力欄
        mTweetBtn = findViewById(R.id.action_tweet);// ツイートボタン
        mAppendPicBtn = findViewById(R.id.appendPic);// 画像添付ボダン
        mCameraBtn = findViewById(R.id.picFromCamera);// 撮影して添付するボタン
        mInfoBtn = findViewById(R.id.tweetInfoBtn);// リプライ先表示ボタン
        mAppendedImageRoot = findViewById(R.id.image_attachments_root);

        mInputText.setTextSize(PrefUtil.getFontSize() * 1.2f);

        for (View clickable : new View[]{mTweetBtn, mAppendPicBtn, mCameraBtn, mInfoBtn}) {
            clickable.setOnClickListener(this);
        }

        // 文章に変更があったら残り文字数を変化させる
        mInputText.addTextChangedListener(new SimpleTextChangeListener() {
            @Override
            public void afterTextChanged(Editable s) {
                updateState();
            }
        });

        setReplyData();

        Intent intent = getIntent();
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            handleSendIntent(intent);
        } else if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            handleViewIntent(intent);
        }

        // アクティビティ開始時の残り文字数をセットする．リプライ時やハッシュタグ時のときも140字にならないために．
        updateState();
    }

    private File createImageFile() {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String imageFileName = "IMG_" + timeStamp;
        try {
            File image = File.createTempFile(
                    imageFileName,
                    ".jpg",
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            );
            mPhotoPath = image.getAbsolutePath();
            return image;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private void handleSendIntent(Intent intent) {
        Bundle b = intent.getExtras();
        if (b != null) {
            String text = b.getString(Intent.EXTRA_TEXT);
            if (text != null) {
                mInputText.setText(text);
                mInputText.setSelection(text.length());
            }
            Uri imageUri = (Uri) b.get(Intent.EXTRA_STREAM);
            if (imageUri != null) {
                appendPicture(imageUri);
            }
        }
    }

    private void handleViewIntent(Intent intent) {
        Uri data = intent.getData();
        if (data == null) {
            AppUtil.showToast(R.string.something_wrong);
        } else {
            String sharedText = Util.parseSharedText(data);
            mInputText.setText(sharedText);
            mInputText.setSelection(sharedText.length());
        }
    }

    private void setReplyData() {
        final Intent intent = getIntent();// reply時に必要なデータとかハッシュタグとかが入ってる
        final ActionBar actionbar = getSupportActionBar();

        mReplyId = intent.getLongExtra(C.REPLY_ID, -1);// reply先ID
        mReplyName = intent.getStringExtra(C.SCREEN_NAME);// リプライ先スクリーンネーム
        mMentionUser = (User) intent.getSerializableExtra(C.USER);
        mHashTag = intent.getStringExtra(C.HASH_TAG);// ハッシュタグ

        // リプライ時は@screen_nameを事前に打ち込んでおき，リプライ先表示ボタンを有効にする
        if (mReplyName != null) {
            mInputText.setText("@" + mReplyName + " ");
            // カーソルの位置を@~の後に
            mInputText.setSelection(mInputText.getText().toString().length());
            Status targetStatus = getTargetStatus();
            if (targetStatus != null) {
                User user = targetStatus.getUser();
                actionbar.setSubtitle("Reply to " + user.getName());
                // マルチリプライ
                if (intent.getBooleanExtra(C.REPLY_ALL, false)) {
                    for (UserMentionEntity entity : targetStatus.getUserMentionEntities()) {
                        if (entity.getId() != TwitterUtils.getCurrentAccountId()
                                && !entity.getScreenName().equals(mReplyName)) {
                            mInputText.append("@" + entity.getScreenName() + " ");
                        }
                    }
                }
                // リプライ先のツイートを見れるように
                mInfoBtn.setVisibility(View.VISIBLE);
            }
        }

        // ハッシュタグがある場合は自前に打ち込んでおく
        if (mHashTag != null) {
            mInputText.setText(mInputText.getText().toString() + " " + mHashTag);
        }

    }

    private final static int PREVIEW_APPENDED_IMAGE = 1;
    private final static int REMOVE_APPENDED_IMAGE = 2;

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        if (mAppendedImageViews.contains(v)) {
            menu.setHeaderTitle(R.string.appended_image);
            menu.add(0, PREVIEW_APPENDED_IMAGE, 0, R.string.preview);
            menu.add(0, REMOVE_APPENDED_IMAGE, 0, R.string.delete);
            mLastTappedView = (ImageView) v;
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case PREVIEW_APPENDED_IMAGE:
                previewAppendedImage((ImageView) item.getActionView());
                return true;
            case REMOVE_APPENDED_IMAGE:
                removeAppendedImage(mLastTappedView);
                return true;
            default:
                return false;
        }
    }

    private void previewAppendedImage(ImageView v) {
//        Intent i = new Intent(this, PhotoPreviewActivity.class);
//        i.putExtra(C.FILE, mAppendingFile);
//        startActivity(i);
    }

    private void removeAppendedImage(ImageView v) {
        mAppendedImageRoot.removeView(v);
        mAppendedImages.remove(v.getTag());
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable(IMAGE_URI, mCameraUri);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        mCameraUri = savedInstanceState.getParcelable(IMAGE_URI);
    }

    private void updateState() {
        String text = mInputText.getEditableText().toString();
        int remainLength = MAX_TWEET_LENGTH - Util.getActualTextLength(text);
        if (remainLength < 0 || (text.isEmpty() && mAppendedImages.isEmpty())) {
            enableTweetButton(false);
        } else {
            enableTweetButton(true);
        }
        getSupportActionBar().setSubtitle(String.valueOf(remainLength));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.d(TAG, "onActivityResult");
        if (resultCode != RESULT_OK) {
            return;
        }

        switch (requestCode) {
            case REQUEST_CAMERA:
                Intent i = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                i.setData(mCameraUri);
                this.sendBroadcast(i);
                appendPicture(mCameraUri);
                break;
            case REQUEST_SELECT_PICTURE:
                if (data != null) {
                    setUpThumbnails(ImagePicker.getImages(data));
                }
                break;
        }
    }

    private void enableTweetButton(boolean enabled) {
        mTweetBtn.setEnabled(enabled);
    }

    private void appendPicture(Uri uri) {
        setUpThumbnail(uri);
        mAppendedImages.add(uri);
        updateState();
    }

    private void setUpThumbnails(List<Image> images) {
        for (Image image : images) {
            setUpThumbnail(image);
            mAppendedImages.add(image);
        }
    }

    private ImageView inflateThumbnail() {
        ImageView iv = (ImageView) getLayoutInflater().inflate(R.layout.appended_image_view, null);
        mAppendedImageRoot.addView(iv);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) iv.getLayoutParams();
        lp.width = AppUtil.dpToPx(THUMBNAIL_SIZE);
        lp.height = AppUtil.dpToPx(THUMBNAIL_SIZE);
        iv.setLayoutParams(lp);
        iv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                registerForContextMenu(v);
                openContextMenu(v);
                unregisterForContextMenu(v);
            }
        });
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            iv.setOnTouchListener(new ColorOverlayOnTouch());
        }
        return iv;
    }

    private void setUpThumbnail(Parcelable image) {
        ImageView iv = inflateThumbnail();
        // set thumbnail
        Bitmap thumbnail;
        if (image instanceof Image) {
            File file = new File(((Image) image).getPath());
            thumbnail = BitmapUtil.getResizedBitmap(file, AppUtil.dpToPx(THUMBNAIL_SIZE));
        } else if (image instanceof Uri) {
            thumbnail = BitmapUtil.getResizedBitmap(getContentResolver(), (Uri) image, AppUtil.dpToPx(THUMBNAIL_SIZE));
        } else throw new IllegalArgumentException("Wrong class");
        iv.setImageBitmap(thumbnail);
        iv.setVisibility(View.VISIBLE);
        mAppendedImageViews.add(iv);
    }

    private void updateStatus() {
        Intent i = new Intent(this, StatusUpdateService.class);
        i.putExtra(C.TEXT, mInputText.getText().toString());
        if (mReplyId > 0) {
            i.putExtra(C.IN_REPLY_TO, mReplyId);
        }
        i.putExtra(C.ATTACHMENTS, mAppendedImages);
        startService(i);
    }

    @Override
    public void onClick(View v) {
        Intent intent;
        int clickedId = v.getId();
        switch (clickedId) {
            case R.id.appendPic:
                // 画像添付ボタン押下時の動作
                // 画像を選びに行く．画像を選んだらonActivityResultが呼ばれる
                if (!Util.checkRuntimePermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE, EXTERNAL_STORAGE_PERMISSION_REQUEST))
                    return;
                ImagePicker.create(this)
                        .folderMode(true)
                        .folderTitle("画像を選択")
                        .limit(MAX_APPEND_FILES - mAppendedImages.size())
                        .showCamera(false)
                        .theme(R.style.ImagePicker)
                        .start(REQUEST_SELECT_PICTURE);
                break;
            case R.id.picFromCamera:
                if (!Util.checkRuntimePermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST))
                    return;
                intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                if (intent.resolveActivity(getPackageManager()) == null) {
                    return;
                }
                File photoFile = createImageFile();
                mCameraUri = FileProvider.getUriForFile(this, getString(R.string.file_provider_authority), photoFile);
                intent.putExtra(MediaStore.EXTRA_OUTPUT, mCameraUri);
                startActivityForResult(intent, REQUEST_CAMERA);
                break;

            case R.id.tweetInfoBtn:
                Status targetStatus = getTargetStatus();
                intent = new Intent(this, ConversationActivity.class);
                intent.putExtra(C.STATUS, targetStatus);
                startActivity(intent);
                break;

            case R.id.action_tweet:
                updateStatus();
                finish();
                break;

            case R.id.appendedImage:
                registerForContextMenu(v);
                openContextMenu(v);
                unregisterForContextMenu(v);
                break;
        }
    }

    /**
     * reply先のStatusを取得する
     *
     * @return
     */
    private Status getTargetStatus() {
        return (Status) getIntent().getSerializableExtra(C.STATUS);
    }
}
