/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.net.Uri;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.Vibrator;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Events;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.SparseBooleanArray;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto;
import com.android.keyguard.KeyguardStatusView;
import com.android.systemui.FontSizeUtils;
import com.android.systemui.R;
import com.android.systemui.purenexus.headers.StatusBarHeaderMachine;
import com.android.systemui.qs.QSPanel;
import com.android.systemui.qs.QSPanel.Callback;
import com.android.systemui.qs.QuickQSPanel;
import com.android.systemui.qs.TouchAnimator;
import com.android.systemui.qs.TouchAnimator.Builder;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkController.EmergencyListener;
import com.android.systemui.statusbar.policy.NetworkController.IconState;
import com.android.systemui.statusbar.policy.NetworkController.SignalCallback;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.NextAlarmController.NextAlarmChangeCallback;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.statusbar.policy.UserInfoController.OnUserInfoChangedListener;

public class QuickStatusBarHeader extends BaseStatusBarHeader implements
        NextAlarmChangeCallback, OnClickListener, OnLongClickListener, OnUserInfoChangedListener,
        StatusBarHeaderMachine.IStatusBarHeaderMachineObserver, EmergencyListener, SignalCallback {

    private static final String TAG = "QuickStatusBarHeader";

    private static final float EXPAND_INDICATOR_THRESHOLD = .93f;

    private ActivityStarter mActivityStarter;
    private NextAlarmController mNextAlarmController;
    private View mSettingsButton;

    private TextView mAlarmStatus;
    private View mAlarmStatusCollapsed;
    private View mClock;
    private View mDate;

    private QSPanel mQsPanel;

    private boolean mExpanded;
    private boolean mAlarmShowing;

    private ViewGroup mDateTimeGroup;
    private ViewGroup mDateTimeAlarmGroup;
    private TextView mEmergencyOnly;

    protected ExpandableIndicator mExpandIndicator;

    private boolean mListening;
    private AlarmManager.AlarmClockInfo mNextAlarm;

    private QuickQSPanel mHeaderQsPanel;
    private boolean mShowEmergencyCallsOnly;
    protected MultiUserSwitch mMultiUserSwitch;
    private ImageView mMultiUserAvatar;


    private TouchAnimator mAnimator;
    protected TouchAnimator mSettingsAlpha;
    private float mExpansionAmount;
    private QSTileHost mHost;
    private View mEdit;
    private boolean mShowFullAlarm;
    private float mDateTimeTranslation;
    private SparseBooleanArray mRoamingsBySubId = new SparseBooleanArray();
    private boolean mIsRoaming;
    protected Vibrator mVibrator;

    // qs headers
    private ImageView mBackgroundImage;
    private Drawable mCurrentBackground;
    private int mQsPanelOffsetNormal;
    private int mQsPanelOffsetHeader;

    // Task manager
    private boolean mShowTaskManager;
    protected TaskManagerButton mTaskManagerButton;
    public boolean mEnabled;

    public QuickStatusBarHeader(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mEmergencyOnly = (TextView) findViewById(R.id.header_emergency_calls_only);

        mEdit = findViewById(android.R.id.edit);
        findViewById(android.R.id.edit).setOnClickListener(view ->
                mHost.startRunnableDismissingKeyguard(() -> mQsPanel.showEdit(view)));

        mDateTimeAlarmGroup = (ViewGroup) findViewById(R.id.date_time_alarm_group);
        mDateTimeAlarmGroup.findViewById(R.id.empty_time_view).setVisibility(View.GONE);
        mDateTimeGroup = (ViewGroup) findViewById(R.id.date_time_group);
        mDateTimeGroup.setPivotX(0);
        mDateTimeGroup.setPivotY(0);
        mDateTimeTranslation = getResources().getDimension(R.dimen.qs_date_time_translation);
        mClock = findViewById(R.id.clock);
        mClock.setOnClickListener(this);
        mClock.setOnLongClickListener(this);
        mDate = findViewById(R.id.date);
        mDate.setOnClickListener(this);
        mDate.setOnLongClickListener(this);

        mShowFullAlarm = getResources().getBoolean(R.bool.quick_settings_show_full_alarm);

        mExpandIndicator = (ExpandableIndicator) findViewById(R.id.expand_indicator);

        mHeaderQsPanel = (QuickQSPanel) findViewById(R.id.quick_qs_panel);

        mSettingsButton = findViewById(R.id.settings_button);
        mSettingsButton.setOnClickListener(this);
        mSettingsButton.setOnLongClickListener(this);

        mAlarmStatusCollapsed = findViewById(R.id.alarm_status_collapsed);
        mAlarmStatus = (TextView) findViewById(R.id.alarm_status);
        mAlarmStatus.setOnClickListener(this);
        mTaskManagerButton = (TaskManagerButton) findViewById(R.id.task_manager_button);
        mTaskManagerButton.setOnClickListener(this);
        mMultiUserSwitch = (MultiUserSwitch) findViewById(R.id.multi_user_switch);
        mMultiUserSwitch.setOnLongClickListener(this);
        mMultiUserAvatar = (ImageView) mMultiUserSwitch.findViewById(R.id.multi_user_avatar);

        mVibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);

        // RenderThread is doing more harm than good when touching the header (to expand quick
        // settings), so disable it for this view
        ((RippleDrawable) mSettingsButton.getBackground()).setForceSoftware(true);
        ((RippleDrawable) mExpandIndicator.getBackground()).setForceSoftware(true);
        mBackgroundImage = (ImageView) findViewById(R.id.background_image);
        ((RippleDrawable) mTaskManagerButton.getBackground()).setForceSoftware(true);
        updateResources();
    }

    public void vibrateheader(int duration) {
        if (mVibrator != null) {
            if (mVibrator.hasVibrator()) { mVibrator.vibrate(duration); }
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        updateResources();
    }

    private void updateResources() {
        FontSizeUtils.updateFontSize(mAlarmStatus, R.dimen.qs_date_collapsed_size);
        FontSizeUtils.updateFontSize(mEmergencyOnly, R.dimen.qs_emergency_calls_only_text_size);

        Builder builder = new Builder()
                .addFloat(mShowFullAlarm ? mAlarmStatus : findViewById(R.id.date), "alpha", 0, 1)
                .addFloat(mEmergencyOnly, "alpha", 0, 1);
        if (mShowFullAlarm) {
            builder.addFloat(mAlarmStatusCollapsed, "alpha", 1, 0);
        }
        mAnimator = builder.build();

        updateSettingsAnimator();

        mQsPanelOffsetNormal = getResources().getDimensionPixelSize(R.dimen.qs_panel_top_offset_normal);
        mQsPanelOffsetHeader = getResources().getDimensionPixelSize(R.dimen.qs_panel_top_offset_header);

        post(new Runnable() {
            public void run() {
                setHeaderImageHeight();
                // the dimens could have been changed
                setQsPanelOffset();
            }
        });
    }

    protected void updateSettingsAnimator() {
        mSettingsAlpha = new TouchAnimator.Builder()
                .addFloat(mEdit, "alpha", 0, 1)
                .addFloat(mMultiUserSwitch, "alpha", 0, 1)
                .addFloat(mTaskManagerButton, "alpha", 0, 1)
                .build();

        final boolean isRtl = isLayoutRtl();
        if (isRtl && mDateTimeGroup.getWidth() == 0) {
            mDateTimeGroup.addOnLayoutChangeListener(new OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                        int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    mDateTimeGroup.setPivotX(getWidth());
                    mDateTimeGroup.removeOnLayoutChangeListener(this);
                }
            });
        } else {
            mDateTimeGroup.setPivotX(isRtl ? mDateTimeGroup.getWidth() : 0);
        }
    }

    @Override
    public int getCollapsedHeight() {
        return getHeight();
    }

    @Override
    public int getExpandedHeight() {
        return getHeight();
    }

    @Override
    public void setExpanded(boolean expanded) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        mHeaderQsPanel.setExpanded(expanded);
        updateEverything();
    }

    @Override
    public void onNextAlarmChanged(AlarmManager.AlarmClockInfo nextAlarm) {
        mNextAlarm = nextAlarm;
        if (nextAlarm != null) {
            String alarmString = KeyguardStatusView.formatNextAlarm(getContext(), nextAlarm);
            mAlarmStatus.setText(alarmString);
            mAlarmStatus.setContentDescription(mContext.getString(
                    R.string.accessibility_quick_settings_alarm, alarmString));
            mAlarmStatusCollapsed.setContentDescription(mContext.getString(
                    R.string.accessibility_quick_settings_alarm, alarmString));
        }
        if (mAlarmShowing != (nextAlarm != null)) {
            mAlarmShowing = nextAlarm != null;
            updateEverything();
        }
    }

    @Override
    public void setExpansion(float headerExpansionFraction) {
        mExpansionAmount = headerExpansionFraction;
        updateDateTimePosition();
        mAnimator.setPosition(headerExpansionFraction);
        mSettingsAlpha.setPosition(headerExpansionFraction);

        updateAlarmVisibilities();

        mExpandIndicator.setExpanded(headerExpansionFraction > EXPAND_INDICATOR_THRESHOLD);
    }

    @Override
    protected void onDetachedFromWindow() {
        setListening(false);
        mHost.getUserInfoController().remListener(this);
        mHost.getNetworkController().removeEmergencyListener(this);
        super.onDetachedFromWindow();
    }

    private void updateAlarmVisibilities() {
        mAlarmStatus.setVisibility(mAlarmShowing && mShowFullAlarm ? View.VISIBLE : View.INVISIBLE);
        mAlarmStatusCollapsed.setVisibility(mAlarmShowing ? View.VISIBLE : View.INVISIBLE);
    }

    public void setListening(boolean listening) {
        if (listening == mListening) {
            return;
        }
        mHeaderQsPanel.setListening(listening);
        mListening = listening;
        updateListeners();
    }

    @Override
    public void updateEverything() {
        post(() -> {
            updateVisibilities();
            setClickable(false);
        });
    }

    @Override
    public void updateVisibilities() {
        updateAlarmVisibilities();
        updateDateTimePosition();
        mEmergencyOnly.setVisibility(mExpanded && (mShowEmergencyCallsOnly || mIsRoaming)
                ? View.VISIBLE : View.INVISIBLE);
        mSettingsButton.setVisibility(View.VISIBLE);
        mTaskManagerButton.setVisibility(mExpanded && enabletaskmanager() ? View.VISIBLE : View.GONE);
        final boolean isDemo = UserManager.isDeviceInDemoMode(mContext);
        mMultiUserSwitch.setVisibility(mExpanded && mMultiUserSwitch.hasMultipleUsers() && !isDemo
                ? View.VISIBLE : View.INVISIBLE);
        mEdit.setVisibility(isDemo || !mExpanded ? View.INVISIBLE : View.VISIBLE);
    }

   @Override
   public void killvisibilities() {
        if (mMultiUserSwitch != null) {
        mMultiUserSwitch.setVisibility(View.GONE);
        }
        if (mEdit != null) {
        mEdit.setVisibility(View.GONE);
        }
        if (mExpandIndicator != null) {
        mExpandIndicator.setVisibility(View.GONE);
        }
    }

    private void updateDateTimePosition() {
        mDateTimeAlarmGroup.setTranslationY(mShowEmergencyCallsOnly || mIsRoaming
                ? mExpansionAmount * mDateTimeTranslation : 0);
    }

    private void updateListeners() {
        if (mListening) {
            mNextAlarmController.addStateChangedCallback(this);
            if (mHost.getNetworkController().hasVoiceCallingFeature()) {
                mHost.getNetworkController().addEmergencyListener(this);
                mHost.getNetworkController().addSignalCallback(this);
            }
        } else {
            mNextAlarmController.removeStateChangedCallback(this);
            mHost.getNetworkController().removeEmergencyListener(this);
            mHost.getNetworkController().removeSignalCallback(this);
        }
    }

    @Override
    public void setActivityStarter(ActivityStarter activityStarter) {
        mActivityStarter = activityStarter;
    }

    @Override
    public void setQSPanel(final QSPanel qsPanel) {
        mQsPanel = qsPanel;
        setupHost(qsPanel.getHost());
        if (mQsPanel != null) {
            mMultiUserSwitch.setQsPanel(qsPanel);
        }
    }

    public void setupHost(final QSTileHost host) {
        mHost = host;
        host.setHeaderView(mExpandIndicator);
        mHeaderQsPanel.setQSPanelAndHeader(mQsPanel, this);
        mHeaderQsPanel.setHost(host, null /* No customization in header */);
        setUserInfoController(host.getUserInfoController());
        setBatteryController(host.getBatteryController());
        setNextAlarmController(host.getNextAlarmController());

        final boolean isAPhone = mHost.getNetworkController().hasVoiceCallingFeature();
        if (isAPhone) {
            mHost.getNetworkController().addEmergencyListener(this);
        }
    }

    @Override
    public void onClick(View v) {
        if (v == mSettingsButton) {
            MetricsLogger.action(mContext,
                    mExpanded ? MetricsProto.MetricsEvent.ACTION_QS_EXPANDED_SETTINGS_LAUNCH
                            : MetricsProto.MetricsEvent.ACTION_QS_COLLAPSED_SETTINGS_LAUNCH);
            startSettingsActivity();
        } else if (v == mAlarmStatus && mNextAlarm != null) {
            PendingIntent showIntent = mNextAlarm.getShowIntent();
            mActivityStarter.startPendingIntentDismissingKeyguard(showIntent);
        } else if (v == mClock) {
            startClockActivity();
        } else if (v == mDate) {
            startDateActivity();
        } else if (v == mTaskManagerButton) {
           mQsPanel.setenabled();
        }
    }

    @Override
    public boolean onLongClick(View v) {
        if (v == mClock) {
            startClockLongClickActivity();
        } else if (v == mDate) {
            startDateLongClickActivity();
        } else if (v == mMultiUserSwitch) {
            startUserLongClickActivity();
        } else if (v == mSettingsButton) {
            startSettingsLongClickActivity();
        }
        vibrateheader(20);
        return false;
    }

    private void startSettingsActivity() {
        mActivityStarter.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS),
                true /* dismissShade */);
    }

    private void startSettingsLongClickActivity() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
	intent.setClassName("com.android.settings",
            "com.android.settings.Settings$PureNexusSettingsActivity");
        mActivityStarter.startActivity(intent, true /* dismissShade */);
    }

    private void startClockActivity() {
        mActivityStarter.startActivity(new Intent(AlarmClock.ACTION_SHOW_ALARMS),
                true /* dismissShade */);
    }

    private void startClockLongClickActivity() {
        mActivityStarter.startActivity(new Intent(AlarmClock.ACTION_SET_ALARM),
                true /* dismissShade */);
    }

    private void startDateActivity() {
        Uri.Builder builder = CalendarContract.CONTENT_URI.buildUpon();
        builder.appendPath("time");
        ContentUris.appendId(builder, System.currentTimeMillis());
        Intent intent = new Intent(Intent.ACTION_VIEW).setData(builder.build());
        mActivityStarter.startActivity(intent, true /* dismissShade */);
    }

    private void startDateLongClickActivity() {
        Intent intent = new Intent(Intent.ACTION_INSERT);
            intent.setData(Events.CONTENT_URI);
        mActivityStarter.startActivity(intent, true /* dismissShade */);
    }

    private void startUserLongClickActivity() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setClassName("com.android.settings",
            "com.android.settings.Settings$UserSettingsActivity");
        mActivityStarter.startActivity(intent, true /* dismissShade */);
    }

    public void setTaskManagerEnabled(boolean enabled) {
        mEnabled = enabled;
    }

    @Override
    public void starttmactivity() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setClassName("com.android.settings",
            "com.android.settings.Settings$RunningServicesActivity");
    }

    @Override
    public void setNextAlarmController(NextAlarmController nextAlarmController) {
        mNextAlarmController = nextAlarmController;
    }

    @Override
    public void setBatteryController(BatteryController batteryController) {
        // Don't care
    }

    @Override
    public void setUserInfoController(UserInfoController userInfoController) {
        userInfoController.addListener(this);
    }

    @Override
    public void setCallback(Callback qsPanelCallback) {
        mHeaderQsPanel.setCallback(qsPanelCallback);
    }

    @Override
    public void setEmergencyCallsOnly(boolean show) {
        boolean changed = show != mShowEmergencyCallsOnly;
        if (changed) {
            mShowEmergencyCallsOnly = show;
            if (mExpanded) {
                updateEverything();
            }
        }
    }

    @Override
    public void setMobileDataIndicators(IconState statusIcon, IconState qsIcon, int statusType,
            int qsType, boolean activityIn, boolean activityOut, String typeContentDescription,
            String description, boolean isWide, int subId, boolean roaming, boolean isMobileIms) {
        mRoamingsBySubId.put(subId, roaming);
        boolean isRoaming = calculateRoaming();
        if (mIsRoaming != isRoaming) {
            mIsRoaming = isRoaming;
            mEmergencyOnly.setText(mIsRoaming ? R.string.accessibility_data_connection_roaming
                    : com.android.internal.R.string.emergency_calls_only);
            if (mExpanded) {
                updateEverything();
            }
        }
    }

    private boolean calculateRoaming() {
        for (int i = 0; i < mRoamingsBySubId.size(); i++) {
            if (mRoamingsBySubId.valueAt(i)) return true;
        }
        return false;
    }

    @Override
    public void onUserInfoChanged(String name, Drawable picture) {
        mMultiUserAvatar.setImageDrawable(picture);
    }

    @Override
    public void updateSettings() {
        if (mQsPanel != null) {
            mQsPanel.updateSettings();

            // if header is active we want to push the qs panel a little bit further down
            // to have more space for the header image
            post(new Runnable() {
                public void run() {
                    setQsPanelOffset();
                }
            });
        }
        if (mHeaderQsPanel != null) {
            mHeaderQsPanel.updateSettings();
        }
        applyHeaderBackgroundShadow();
    }

    @Override
    public void updateHeader(final Drawable headerImage, final boolean force) {
        post(new Runnable() {
             public void run() {
                doUpdateStatusBarCustomHeader(headerImage, force);
            }
        });
    }

    @Override
    public void disableHeader() {
        post(new Runnable() {
             public void run() {
                mCurrentBackground = null;
                mBackgroundImage.setVisibility(View.GONE);
            }
        });
    }

    private void doUpdateStatusBarCustomHeader(final Drawable next, final boolean force) {
        if (next != null) {
            if (next != mCurrentBackground) {
                mBackgroundImage.setVisibility(View.VISIBLE);
                setNotificationPanelHeaderBackground(next, force);
                mCurrentBackground = next;
            }
        } else {
            mCurrentBackground = null;
            mBackgroundImage.setVisibility(View.GONE);
        }
    }

    private void setNotificationPanelHeaderBackground(final Drawable dw, final boolean force) {
        if (mBackgroundImage.getDrawable() != null && !force) {
            Drawable[] arrayDrawable = new Drawable[2];
            arrayDrawable[0] = mBackgroundImage.getDrawable();
            arrayDrawable[1] = dw;

            TransitionDrawable transitionDrawable = new TransitionDrawable(arrayDrawable);
            transitionDrawable.setCrossFadeEnabled(true);
            mBackgroundImage.setImageDrawable(transitionDrawable);
            transitionDrawable.startTransition(1000);
        } else {
            mBackgroundImage.setImageDrawable(dw);
        }
        applyHeaderBackgroundShadow();
    }

    private void applyHeaderBackgroundShadow() {
        final int headerShadow = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CUSTOM_HEADER_SHADOW, 80,
                UserHandle.USER_CURRENT);

        if (mBackgroundImage != null) {
            if (headerShadow != 0) {
                ColorDrawable shadow = new ColorDrawable(Color.BLACK);
                shadow.setAlpha(headerShadow);
                mBackgroundImage.setForeground(shadow);
            } else {
                mBackgroundImage.setForeground(null);
            }
        }
    }

    private void setQsPanelOffset() {
        final boolean customHeader = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CUSTOM_HEADER, 0,
                UserHandle.USER_CURRENT) != 0;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mQsPanel.getLayoutParams();
        params.setMargins(0, customHeader ? mQsPanelOffsetHeader : mQsPanelOffsetNormal, 0, 0);
        mQsPanel.setLayoutParams(params);
    }

    private void setHeaderImageHeight() {
        if (mBackgroundImage != null) {
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) mBackgroundImage.getLayoutParams();
            p.height = getExpandedHeight();
            mBackgroundImage.setLayoutParams(p);
        }
    }

    public boolean enabletaskmanager() {
       return Settings.System.getInt(mContext.getContentResolver(),
            Settings.System.ENABLE_TASK_MANAGER, 0) == 1;
   }
}
