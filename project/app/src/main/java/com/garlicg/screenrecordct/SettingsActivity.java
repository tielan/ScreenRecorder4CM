package com.garlicg.screenrecordct;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.garlicg.cutin.triggerextension.ResultBundleBuilder;
import com.garlicg.cutin.triggerextension.TriggerSetting;
import com.garlicg.cutin.triggerextension.TriggerUtil;
import com.garlicg.screenrecordct.util.ViewFinder;

import java.util.ArrayList;

public class SettingsActivity extends AppCompatActivity
        implements ActivityCompat.OnRequestPermissionsResultCallback {

    private static final int REQUEST_CAPTURE = 1;
    private static final int REQUEST_STICKY_CAPTURE = 2;
    private static final int REQUEST_WRITE_EXTERNAL_STORAGE = 3;

    private AppPrefs mPrefs;

    /**
     * 録画サービスが存在する状態からこのActivityが起動された場合は
     * ・Activity終了時に再度録画サービスが起動される
     * ・起動ボタンが表示されない
     * ただしパーミッションがある場合に限る
     */
    private static final String KEY_STICKY = "STICKY";
    private boolean mSticky = false;


    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(KEY_STICKY , mSticky);
    }


    void restore(Bundle savedInstanceState){
        mSticky = savedInstanceState.getBoolean(KEY_STICKY , false);
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if(savedInstanceState == null){
            mSticky = RecordService.requestQuit(this);
        }else {
            restore(savedInstanceState);
        }


        mPrefs = new AppPrefs(this);

        setContentView(R.layout.activity_settings);

        // Toolbar
        Toolbar toolbar = ViewFinder.byId(this, R.id.toolbar);
        setSupportActionBar(toolbar);

        // launchButton
        View launchButton = ViewFinder.byId(this, R.id.startFloating);
        launchButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tryRecordService();
            }
        });
        launchButton.setVisibility(mSticky && isGrantedPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                ? View.GONE
                : View.VISIBLE);

        createVideoSize(savedInstanceState);
        createFireCutin(savedInstanceState);
        createAutoStop(savedInstanceState);
        createTriggerTitle(savedInstanceState);
        createTriggerMessage(savedInstanceState);
        createInvisibleRecord(savedInstanceState);
        createVideoList(savedInstanceState);
    }


    @Override
    protected void onStart() {
        super.onStart();
        invalidateVideoCount();
    }


    @Override
    public void onBackPressed() {
        if(mSticky && isGrantedPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)){
            requestCapture(REQUEST_STICKY_CAPTURE);
        }
        else{
            super.onBackPressed();
        }
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // from #requestCapture onClick LaunchButton
        if (requestCode == REQUEST_CAPTURE) {
            if (resultCode != RESULT_OK || data == null) return;
            Intent intent = RecordService.newStartIntent(this, data);
            startService(intent);
            finish();
        }
        // from #requestCapture onBackPress
        else if(requestCode == REQUEST_STICKY_CAPTURE){
            if(resultCode == RESULT_OK && data != null){
                Intent intent = RecordService.newStartIntent(this, data);
                startService(intent);
            }
            finish();
        }
    }


    @Override
    public void finish() {
        // カットインマネージャーからの起動のみ想定
        // RecentTask起動とかはたぶんもんだいない
        ResultBundleBuilder builder = new ResultBundleBuilder(this);
        builder.add(new StartRecordTrigger(this));

        Intent intent = new Intent();
        intent.putExtras(builder.build());
        setResult(RESULT_OK, intent);

        super.finish();
    }


    /**
     * Create video size setting view
     */
    private void createVideoSize(Bundle savedInstanceState) {
        int vp = mPrefs.getVideoPercentage();
        final VideoPercentage[] spinnerItems = AppPrefs.videoPercentages();
        int spinnerSelection = AppPrefs.findVideoPercentagePosition(spinnerItems, vp);

        ArrayAdapter<VideoPercentage> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, spinnerItems);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        final Spinner spinner = ViewFinder.byId(this, R.id.videoPercentageSpinner);
        spinner.setAdapter(adapter);

        spinner.setSelection(spinnerSelection);
        spinner.post(new Runnable() {
            @Override
            public void run() {
                // setSelection later
                spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                        if (view == null) return;

                        VideoPercentage item = (VideoPercentage) parent.getItemAtPosition(position);
                        new AppPrefs(view.getContext()).saveVideoPercentage(item);
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                    }
                });
            }
        });
    }


    /**
     * Create auto stop setting view
     */
    private void createFireCutin(Bundle savedInstanceState){

        // init value setup
        final TextView valueView = ViewFinder.byId(this, R.id.fireCutinValue);
        int value = mPrefs.getFireCutinOffsetSec();
        valueView.setText(getString(R.string.x_seconds_later, value));

        // handle value from dialog callback
        final ValidateIntDialogBuilder.Callback callback = new ValidateIntDialogBuilder.Callback() {
            @Override
            public boolean onValidate(int value) {
                return value >= 0 && value <= 999;
            }
            @Override
            public void onOk(int value) {
                valueView.setText(getString(R.string.x_seconds_later, value));
                mPrefs.saveFireCutinOffsetSec(value);
            }
        };

        // show dialog on click
        valueView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int value = mPrefs.getFireCutinOffsetSec();
                AlertDialog ad = ValidateIntDialogBuilder.build(v.getContext(), value, null, callback);
                ad.show();
            }
        });
    }


    /**
     * Create auto stop setting view
     */
    private void createAutoStop(Bundle savedInstanceState){

        // init value setup
        final TextView valueView = ViewFinder.byId(this, R.id.autoStopValue);
        int value = mPrefs.getAutoStopSec();
        valueView.setText(value == 0
                 ? getString(R.string.no_seconds_only_manual_stop)
                 : getString(R.string.plus_x_seconds_later, value)
        );

        // handle value from dialog callback
        final ValidateIntDialogBuilder.Callback callback = new ValidateIntDialogBuilder.Callback() {
            @Override
            public boolean onValidate(int value) {
                return value >= 0 && value <= 999;
            }
            @Override
            public void onOk(int value) {
                valueView.setText(value == 0
                        ? getString(R.string.no_seconds_only_manual_stop)
                        : getString(R.string.plus_x_seconds_later, value)
                );

                mPrefs.saveAutoStopSec(value);
            }
        };

        // show dialog on click
        valueView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int value = mPrefs.getAutoStopSec();
                AlertDialog ad = ValidateIntDialogBuilder.build(v.getContext(), value, null, callback);
                ad.show();
            }
        });
    }


    /**
     * TriggerTitle
     */
    private void createTriggerTitle(Bundle savedInstanceState) {
        final TextView valueView = ViewFinder.byId(this, R.id.triggerTitleValue);
        String value = mPrefs.getTriggerTitle();
        valueView.setText(value);

        // handle value from dialog callback
        final ValidateTextDialogBuilder.Callback callback = new ValidateTextDialogBuilder.Callback() {
            @Override
            public boolean onValidate(CharSequence value) {
                return !TextUtils.isEmpty(value);
            }
            @Override
            public void onOk(CharSequence value) {
                valueView.setText(value);
                mPrefs.saveTriggerTitle(value.toString());
            }
        };

        // show dialog on click
        valueView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String value = mPrefs.getTriggerTitle();
                AlertDialog ad = ValidateTextDialogBuilder.build(v.getContext(), value, null, 50, callback);
                ad.show();
            }
        });
    }


    /**
     * TriggerMessage
     */
    private void createTriggerMessage(Bundle savedInstanceState) {
        final TextView valueView = ViewFinder.byId(this, R.id.triggerMessageValue);
        String value = mPrefs.getTriggerMessage();
        valueView.setText(value);

        // handle value from dialog callback
        final ValidateTextDialogBuilder.Callback callback = new ValidateTextDialogBuilder.Callback() {
            @Override
            public boolean onValidate(CharSequence value) {
                return !TextUtils.isEmpty(value);
            }
            @Override
            public void onOk(CharSequence value) {
                valueView.setText(value);
                mPrefs.saveTriggerMessage(value.toString());
            }
        };

        // show dialog on click
        valueView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String value = mPrefs.getTriggerMessage();
                AlertDialog ad = ValidateTextDialogBuilder.build(v.getContext(), value, null, 100, callback);
                ad.show();
            }
        });
    }


    /**
     * InvisibleRecord
     */
    private void createInvisibleRecord(Bundle savedInstanceState) {
        boolean value = mPrefs.getInvisibleRecord();
        final Switch sw = ViewFinder.byId(this, R.id.invisibleRecord);
        sw.setChecked(value);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                mPrefs.saveInvisibleRecord(isChecked);
            }
        });
    }


    /**
     * VideoList
     */
    private void createVideoList(Bundle savedInstanceState) {
        View touchFrame = ViewFinder.byId(this, R.id.videoList);
        touchFrame.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(SettingsActivity.this, VideoListActivity.class);
                startActivity(intent);
            }
        });

        // init count
        TextView titleView = ViewFinder.byId(this , R.id.videoListTitle);
        titleView.setText(getString(R.string.video_list_x, 0));
    }

    private void invalidateVideoCount(){
        // TODO 動画数を非同期で取得する
        int videoNum = 9;
        TextView titleView = ViewFinder.byId(this , R.id.videoListTitle);
        titleView.setText(getString(R.string.video_list_x, videoNum));
    }


    /**
     * 録画サービス立ち上げるけどパーミッションの壁が立ちはだかる
     * <p/>
     * RuntimePermission (ExternalStorage)
     * -> request intent for MEDIA_PROJECTION_SERVICE
     * -> start RecordService
     */
    private void tryRecordService() {
        final String permission = Manifest.permission.WRITE_EXTERNAL_STORAGE;
        final int requestCode = REQUEST_WRITE_EXTERNAL_STORAGE;

        if (isGrantedPermission(permission)) {
            requestCapture(REQUEST_CAPTURE);
        } else {
            if (!ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                // FIXME Show dialog before show activity_settings Activity.
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                Uri uri = Uri.fromParts("package", getPackageName(), null);
                intent.setData(uri);
                startActivity(intent);
            } else {
                ActivityCompat.requestPermissions(this, new String[]{permission}, requestCode);
            }
        }
    }

    boolean isGrantedPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission)
                == PackageManager.PERMISSION_GRANTED;
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_WRITE_EXTERNAL_STORAGE) {
            if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)
                return;
            requestCapture(REQUEST_CAPTURE);
        }
    }


    void requestCapture(int requestCode) {
        MediaProjectionManager mm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        Intent intent = mm.createScreenCaptureIntent();
        startActivityForResult(intent, requestCode);
    }

}
