/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.keyguard;

import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.TransitionDrawable;
import com.android.internal.policy.IKeyguardShowCallback;
import com.android.internal.widget.LockPatternUtils;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;

import android.app.ActivityManager;
import android.appwidget.AppWidgetManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Vibrator;
import android.os.UserHandle;
import android.provider.Settings;
import android.renderscript.Allocation;
import android.renderscript.Allocation.MipmapControl;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewManager;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.android.internal.util.liquid.TorchConstants;

/**
 * Manages creating, showing, hiding and resetting the keyguard.  Calls back
 * via {@link KeyguardViewMediator.ViewMediatorCallback} to poke
 * the wake lock and report that the keyguard is done, which is in turn,
 * reported to this class by the current {@link KeyguardViewBase}.
 */
public class KeyguardViewManager {
    private final static boolean DEBUG = KeyguardViewMediator.DEBUG;
    private static String TAG = "KeyguardViewManager";
    public final static String IS_SWITCHING_USER = "is_switching_user";

    // Delay dismissing keyguard to allow animations to complete.
    private static final int HIDE_KEYGUARD_DELAY = 500;

    // Timeout used for keypresses
    static final int DIGIT_PRESS_WAKE_MILLIS = 5000;

    private static final int ROTATION_OFF = 0;
    private static final int ROTATION_ON = 1;
    private static final int ROTATION_PORTRAIT = 2;
    private static final int ROTATION_LANDSCAPE = 3;

    private final Context mContext;
    private final ViewManager mViewManager;
    private final KeyguardViewMediator.ViewMediatorCallback mViewMediatorCallback;

    private WindowManager.LayoutParams mWindowLayoutParams;
    private boolean mNeedsInput = false;

    private ViewManagerHost mKeyguardHost;
    private KeyguardHostView mKeyguardView;

    private boolean mScreenOn = false;
    private LockPatternUtils mLockPatternUtils;

    private Bitmap mCustomImage = null;
    private int mBlurRadius = 12;
    private boolean mSeeThrough = false;
    private boolean mIsCoverflow = true;
    private boolean mLoadWallpaper;
    private String mWallpaperFile;

    private NotificationHostView mNotificationView;
    private NotificationViewManager mNotificationViewManager;
    private boolean mLockscreenNotifications = true;

    private boolean mUnlockKeyDown = false;

    private KeyguardUpdateMonitorCallback mBackgroundChanger = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onSetBackground(Bitmap bmp) {
            mIsCoverflow = true;
            setCustomBackground (bmp);
        }
    };

    public interface ShowListener {
        void onShown(IBinder windowToken);
    };

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_SEE_THROUGH), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_BLUR_RADIUS), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_NOTIFICATIONS), false, this);

            updateSettings();
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
            if (mKeyguardHost == null) {
                maybeCreateKeyguardLocked(shouldEnableScreenRotation(), false, null);
                hide();
            }
            mViewManager.updateViewLayout(mKeyguardHost, mWindowLayoutParams);
        }
    }

    private void updateSettings() {
        mSeeThrough = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.LOCKSCREEN_SEE_THROUGH, 0) == 1;
        mBlurRadius = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.LOCKSCREEN_BLUR_RADIUS, mBlurRadius);
        mLockscreenNotifications = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.LOCKSCREEN_NOTIFICATIONS, mLockscreenNotifications ? 1 : 0) == 1;
        if(!mSeeThrough) mCustomImage = null;
        if (mLockscreenNotifications && mNotificationViewManager == null) {
            mNotificationViewManager = new NotificationViewManager(mContext, this);
        } else if(!mLockscreenNotifications && mNotificationViewManager != null) {
            mNotificationViewManager.unregisterListeners();
            mNotificationViewManager = null;
        }
    }

    /**
     * @param context Used to create views.
     * @param viewManager Keyguard will be attached to this.
     * @param callback Used to notify of changes.
     * @param lockPatternUtils
     */
    public KeyguardViewManager(Context context, ViewManager viewManager,
            KeyguardViewMediator.ViewMediatorCallback callback,
            LockPatternUtils lockPatternUtils) {
        mContext = context;
        mViewManager = viewManager;
        mViewMediatorCallback = callback;
        mLockPatternUtils = lockPatternUtils;

        SettingsObserver observer = new SettingsObserver(new Handler());
        observer.observe();

        updateSettings();

        mWallpaperFile = mContext.getFilesDir() + "/wallpaper.png";
        mLoadWallpaper = Settings.System.getInt(mContext.getContentResolver(), Settings.System.LOCKSCREEN_WALLPAPER, 0) == 1;
    }

    /**
     * Show the keyguard.  Will handle creating and attaching to the view manager
     * lazily.
     */
    public synchronized void show(Bundle options) {
        if (DEBUG) Log.d(TAG, "show(); mKeyguardView==" + mKeyguardView);

        int rotationAngles = shouldEnableScreenRotation();

        maybeCreateKeyguardLocked(rotationAngles, false, options);
        maybeEnableScreenRotation(rotationAngles);

        // Disable common aspects of the system/status/navigation bars that are not appropriate or
        // useful on any keyguard screen but can be re-shown by dialogs or SHOW_WHEN_LOCKED
        // activities. Other disabled bits are handled by the KeyguardViewMediator talking
        // directly to the status bar service.
        int visFlags = View.STATUS_BAR_DISABLE_HOME;
        if (shouldEnableTranslucentDecor()) {
            mWindowLayoutParams.flags |= WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                                       | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION;
        }
        if (DEBUG) Log.v(TAG, "show:setSystemUiVisibility(" + Integer.toHexString(visFlags)+")");
        mKeyguardHost.setSystemUiVisibility(visFlags);

        mViewManager.updateViewLayout(mKeyguardHost, mWindowLayoutParams);
        mKeyguardHost.setVisibility(View.VISIBLE);
        mKeyguardView.show();
        mKeyguardView.requestFocus();
    }

    private int shouldEnableScreenRotation() {
        Resources res = mContext.getResources();
        boolean enableScreenRotation = SystemProperties.getBoolean("lockscreen.rot_override",false)
                || res.getBoolean(R.bool.config_enableLockScreenRotation);
        return Settings.System.getIntForUser(
                mContext.getContentResolver(),
                Settings.System.LOCKSCREEN_ROTATION_ENABLED,
                enableScreenRotation ? ROTATION_ON : ROTATION_OFF,
                UserHandle.USER_CURRENT);
    }

    private boolean shouldEnableTranslucentDecor() {
        Resources res = mContext.getResources();
        return res.getBoolean(R.bool.config_enableLockScreenTranslucentDecor)
            && res.getBoolean(R.bool.config_enableTranslucentDecor);
    }

    private void setCustomBackground(Bitmap bmp) {
        mKeyguardHost.setCustomBackground( new BitmapDrawable(mContext.getResources(),
                    bmp != null ? bmp : mCustomImage) );
        updateShowWallpaper(bmp == null && mCustomImage == null);
    }

    public void setBackgroundBitmap(Bitmap bmp) {
        if (bmp != null && mSeeThrough && mBlurRadius > 0) {
            mCustomImage = blurBitmap(bmp, mBlurRadius);
        } else {
            mCustomImage = bmp;
        }
    }

    public void setWallpaper(Bitmap bmp) {
        Settings.System.putInt(mContext.getContentResolver(), Settings.System.LOCKSCREEN_WALLPAPER, bmp != null ? 1 : 0);
        if (bmp != null) {
            try {
                FileOutputStream fos = new FileOutputStream(mWallpaperFile);
                bmp.compress(CompressFormat.PNG, 100, fos);
            } catch (FileNotFoundException ex) {
                Log.e(TAG, "Could not write file: " + mWallpaperFile + "\nError: " + ex.toString());
            }
        }
        setBackgroundBitmap(bmp);
    }

    private Bitmap blurBitmap(Bitmap bmp, int radius) {
        Bitmap out = Bitmap.createBitmap(bmp);
        RenderScript rs = RenderScript.create(mContext);

        Allocation input = Allocation.createFromBitmap(
                rs, bmp, MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT);
        Allocation output = Allocation.createTyped(rs, input.getType());

        ScriptIntrinsicBlur script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
        script.setInput(input);
        script.setRadius(radius);
        script.forEach(output);

        output.copyTo(out);

        rs.destroy();
        return out;
    }

    class ViewManagerHost extends FrameLayout {
        private static final int BACKGROUND_COLOR = 0x70000000;

        private Drawable mCustomBackground;

        // This is a faster way to draw the background on devices without hardware acceleration
        private final Drawable mBackgroundDrawable = new Drawable() {
            @Override
            public void draw(Canvas canvas) {
                if (mCustomBackground != null) {
                    final Rect bounds = mCustomBackground.getBounds();
                    final int vWidth = getWidth();
                    final int vHeight = getHeight();

                    final int restore = canvas.save();
                    canvas.translate(-(bounds.width() - vWidth) / 2,
                            -(bounds.height() - vHeight) / 2);
                    mCustomBackground.draw(canvas);
                    canvas.restoreToCount(restore);
                } else {
                    canvas.drawColor(BACKGROUND_COLOR, PorterDuff.Mode.SRC);
                }
            }

            @Override
            public void setAlpha(int alpha) {
            }

            @Override
            public void setColorFilter(ColorFilter cf) {
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        };

        private TransitionDrawable mTransitionBackground = null;

        public ViewManagerHost(Context context) {
            super(context);
            setBackground(mBackgroundDrawable);
        }

        public void setCustomBackground(Drawable d) {
            if (!ActivityManager.isHighEndGfx()) {
                mCustomBackground = d;
                if (d != null) {
                    d.setColorFilter(BACKGROUND_COLOR, PorterDuff.Mode.SRC_OVER);
                }
                computeCustomBackgroundBounds(mCustomBackground);
                invalidate();
            } else {
                if (d == null) {
                    mCustomBackground = null;
                    setBackground(mBackgroundDrawable);
                    return;
                }
                Drawable old = mCustomBackground;
                if (old == null) {
                    old = new ColorDrawable(0);
                    computeCustomBackgroundBounds(old);
                }

                d.setColorFilter(BACKGROUND_COLOR, PorterDuff.Mode.SRC_OVER);
                mCustomBackground = d;
                computeCustomBackgroundBounds(d);
                Bitmap b = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(b);
                mBackgroundDrawable.draw(c);

                Drawable dd = new BitmapDrawable(b);

                mTransitionBackground = new TransitionDrawable(new Drawable[]{old, dd});
                mTransitionBackground.setCrossFadeEnabled(true);
                setBackground(mTransitionBackground);

                mTransitionBackground.startTransition(200);

                mCustomBackground = dd;
                invalidate();
            }
        }

        private void computeCustomBackgroundBounds(Drawable background) {
            if (background == null) return; // Nothing to do
            if (!isLaidOut()) return; // We'll do this later

            final int bgWidth = background.getIntrinsicWidth();
            final int bgHeight = background.getIntrinsicHeight();

            final int vWidth = getWidth();
            final int vHeight = getHeight();

            if (!mIsCoverflow) {
                background.setBounds(0, 0, vWidth, vHeight);
                return;
            }

            final float bgAspect = (float) bgWidth / bgHeight;
            final float vAspect = (float) vWidth / vHeight;

            if (bgAspect > vAspect) {
                background.setBounds(0, 0, (int) (vHeight * bgAspect), vHeight);
            } else {
                background.setBounds(0, 0, vWidth, (int) (vWidth / bgAspect));
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            computeCustomBackgroundBounds(mCustomBackground);
        }

        @Override
        protected void onConfigurationChanged(Configuration newConfig) {
            super.onConfigurationChanged(newConfig);
            if (mKeyguardHost.getVisibility() == View.VISIBLE) {
                // only propagate configuration messages if we're currently showing
                maybeCreateKeyguardLocked(shouldEnableScreenRotation(), true, null);
            } else {
                if (DEBUG) Log.v(TAG, "onConfigurationChanged: view not visible");
            }
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (mKeyguardView != null) {
                int keyCode = event.getKeyCode();
                int action = event.getAction();

                if (action == KeyEvent.ACTION_DOWN) {
                    if (handleKeyDown(keyCode, event)) {
                        return true;
                    }
                } else if (action == KeyEvent.ACTION_UP) {
                    if (handleKeyUp(keyCode, event)) {
                        return true;
                    }
                }
                // Always process media keys, regardless of focus
                if (mKeyguardView.dispatchKeyEvent(event)) {
                    return true;
                }
            }
            return super.dispatchKeyEvent(event);
        }
    }

    public boolean handleKeyDown(int keyCode, KeyEvent event) {
        if (event.getRepeatCount() == 0) {
            mUnlockKeyDown = true;
        }
        if (event.isLongPress()) {
            String action = null;
            switch (keyCode) {
                case KeyEvent.KEYCODE_BACK:
                    action = Settings.System.LOCKSCREEN_LONG_BACK_ACTION;
                    break;
                case KeyEvent.KEYCODE_HOME:
                    action = Settings.System.LOCKSCREEN_LONG_HOME_ACTION;
                    break;
                case KeyEvent.KEYCODE_MENU:
                    action = Settings.System.LOCKSCREEN_LONG_MENU_ACTION;
                    break;
            }

            if (action != null) {
                mUnlockKeyDown = false;
                String uri = Settings.System.getString(mContext.getContentResolver(), action);
                if (uri != null && runAction(mContext, uri)) {
                    long[] pattern = getLongPressVibePattern(mContext);
                    if (pattern != null) {
                        Vibrator v = (Vibrator) mContext.getSystemService(mContext.VIBRATOR_SERVICE);
                        if (pattern.length == 1) {
                            v.vibrate(pattern[0]);
                        } else {
                            v.vibrate(pattern, -1);
                        }
                    }
                    return true;
                }
            }
        }
        return false;
    }

    public boolean handleKeyUp(int keyCode, KeyEvent event) {
        if (mUnlockKeyDown) {
            mUnlockKeyDown = false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_BACK:
                    if (mKeyguardView.handleBackKey()) {
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_HOME:
                    if (mKeyguardView.handleHomeKey()) {
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_MENU:
                    if (mKeyguardView.handleMenuKey()) {
                        return true;
                    }
                    break;
            }
        }
        return false;
    }

    private static boolean runAction(Context context, String uri) {
        if ("FLASHLIGHT".equals(uri)) {
            context.sendBroadcast(new Intent(TorchConstants.ACTION_TOGGLE_STATE));
            return true;
        } else if ("NEXT".equals(uri)) {
            sendMediaButtonEvent(context, KeyEvent.KEYCODE_MEDIA_NEXT);
            return true;
        } else if ("PREVIOUS".equals(uri)) {
            sendMediaButtonEvent(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            return true;
        } else if ("PLAYPAUSE".equals(uri)) {
            sendMediaButtonEvent(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            return true;
        } else if ("SOUND".equals(uri)) {
            toggleSilentMode(context);
            return true;
        } else if ("SLEEP".equals(uri)) {
            sendToSleep(context);
            return true;
        }

        return false;
    }

    private static void sendMediaButtonEvent(Context context, int code) {
        long eventtime = SystemClock.uptimeMillis();

        Intent downIntent = new Intent(Intent.ACTION_MEDIA_BUTTON, null);
        KeyEvent downEvent = new KeyEvent(eventtime, eventtime, KeyEvent.ACTION_DOWN, code, 0);
        downIntent.putExtra(Intent.EXTRA_KEY_EVENT, downEvent);
        context.sendOrderedBroadcast(downIntent, null);

        Intent upIntent = new Intent(Intent.ACTION_MEDIA_BUTTON, null);
        KeyEvent upEvent = new KeyEvent(eventtime, eventtime, KeyEvent.ACTION_UP, code, 0);
        upIntent.putExtra(Intent.EXTRA_KEY_EVENT, upEvent);
        context.sendOrderedBroadcast(upIntent, null);
    }

    private static void toggleSilentMode(Context context) {
        final AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        final Vibrator vib = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        final boolean hasVib = vib == null ? false : vib.hasVibrator();
        if (am.getRingerMode() == AudioManager.RINGER_MODE_NORMAL) {
            am.setRingerMode(hasVib
                ? AudioManager.RINGER_MODE_VIBRATE
                : AudioManager.RINGER_MODE_SILENT);
        } else {
            am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        }
    }

    private static long[] getLongPressVibePattern(Context context) {
        if (Settings.System.getInt(context.getContentResolver(),
                Settings.System.HAPTIC_FEEDBACK_ENABLED, 0) == 0) {
            return null;
        }

        int[] defaultPattern = context.getResources().getIntArray(
                com.android.internal.R.array.config_longPressVibePattern);
        if (defaultPattern == null) {
            return null;
        }

        long[] pattern = new long[defaultPattern.length];
        for (int i = 0; i < defaultPattern.length; i++) {
            pattern[i] = defaultPattern[i];
        }

        return pattern;
    }

    private static void sendToSleep(Context context) {
        final PowerManager pm;
        pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        pm.goToSleep(SystemClock.uptimeMillis());
    }

    SparseArray<Parcelable> mStateContainer = new SparseArray<Parcelable>();
    int mLastRotation = 0;
    private void maybeCreateKeyguardLocked(int rotationAngles, boolean force,
            Bundle options) {
        if (mKeyguardHost != null) {
            mKeyguardHost.saveHierarchyState(mStateContainer);
        }

        if (mKeyguardHost == null) {
            if (DEBUG) Log.d(TAG, "keyguard host is null, creating it...");

            mKeyguardHost = new ViewManagerHost(mContext);

            int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                    | WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;

            if (!mNeedsInput) {
                flags |= WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
            }

            final int stretch = ViewGroup.LayoutParams.MATCH_PARENT;
            final int type = WindowManager.LayoutParams.TYPE_KEYGUARD;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    stretch, stretch, type, flags, PixelFormat.TRANSLUCENT);
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
            lp.windowAnimations = R.style.Animation_LockScreen;

            switch (rotationAngles) {
                case ROTATION_OFF:
                    lp.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
                    break;
                case ROTATION_ON:
                    lp.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_USER;
                    break;
                case ROTATION_PORTRAIT:
                    lp.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
                    break;
                case ROTATION_LANDSCAPE:
                    lp.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
                    break;
            }

            if (ActivityManager.isHighEndGfx()) {
                lp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
                lp.privateFlags |=
                        WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_HARDWARE_ACCELERATED;
            }
            lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SET_NEEDS_MENU_KEY;
            lp.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_DISABLE_USER_ACTIVITY;
            lp.setTitle("Keyguard");
            mWindowLayoutParams = lp;
            mViewManager.addView(mKeyguardHost, lp);

            KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mBackgroundChanger);
        }

        if (force || mKeyguardView == null) {
            mKeyguardHost.setCustomBackground(null);
            mKeyguardHost.removeAllViews();
            inflateKeyguardView(options);
            mKeyguardView.requestFocus();
        }

        updateUserActivityTimeoutInWindowLayoutParams();
        mViewManager.updateViewLayout(mKeyguardHost, mWindowLayoutParams);

        mKeyguardHost.restoreHierarchyState(mStateContainer);

        if((mCustomImage != null || (mSeeThrough && mBlurRadius == 0))) {
            if (mCustomImage != null) {
                if (mSeeThrough) {
                    int currentRotation = mKeyguardView.getDisplay().getRotation() * 90;
                    mCustomImage = rotateBmp(mCustomImage, mLastRotation - currentRotation);
                    mLastRotation = currentRotation;
                }
                mIsCoverflow = false;
                setCustomBackground(mCustomImage);
            } else {
                updateShowWallpaper(false);
            }
        } else {
            mIsCoverflow = true;
        }

        if (mLoadWallpaper) {
            setBackgroundBitmap(BitmapFactory.decodeFile(mWallpaperFile));
            mLoadWallpaper = false;
        }
    }

    private Bitmap rotateBmp(Bitmap bmp, int degrees) {
        Matrix m = new Matrix();
        m.postRotate(degrees);
        return Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
    }

    private void inflateKeyguardView(Bundle options) {
        View v = mKeyguardHost.findViewById(R.id.keyguard_host_view);
        if (v != null) {
            mKeyguardHost.removeView(v);
        }

        final LayoutInflater inflater = LayoutInflater.from(mContext);
        View view = inflater.inflate(R.layout.keyguard_host_view, mKeyguardHost, true);
        mKeyguardView = (KeyguardHostView) view.findViewById(R.id.keyguard_host_view);
        mKeyguardView.setLockPatternUtils(mLockPatternUtils);
        mKeyguardView.setViewMediatorCallback(mViewMediatorCallback);
        mKeyguardView.initializeSwitchingUserState(options != null &&
                options.getBoolean(IS_SWITCHING_USER));

        mNotificationView = (NotificationHostView) mKeyguardView.findViewById(R.id.notification_host_view);
        if (mNotificationViewManager != null && mNotificationView != null) {
            mNotificationViewManager.setHostView(mNotificationView);
            mNotificationViewManager.onScreenTurnedOff();
            mNotificationView.addNotifications();
        }

        // HACK
        // The keyguard view will have set up window flags in onFinishInflate before we set
        // the view mediator callback. Make sure it knows the correct IME state.
        if (mViewMediatorCallback != null) {
            if (mLockscreenNotifications) {
                mNotificationView.setViewMediator(mViewMediatorCallback);
            }
            KeyguardPasswordView kpv = (KeyguardPasswordView) mKeyguardView.findViewById(
                    R.id.keyguard_password_view);

            if (kpv != null) {
                mViewMediatorCallback.setNeedsInput(kpv.needsInput());
            }
        }

        if (options != null) {
            int widgetToShow = options.getInt(LockPatternUtils.KEYGUARD_SHOW_APPWIDGET,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
            if (widgetToShow != AppWidgetManager.INVALID_APPWIDGET_ID) {
                mKeyguardView.goToWidget(widgetToShow);
            }
        }
    }

    public void updateUserActivityTimeout() {
        updateUserActivityTimeoutInWindowLayoutParams();
        mViewManager.updateViewLayout(mKeyguardHost, mWindowLayoutParams);
    }

    private void updateUserActivityTimeoutInWindowLayoutParams() {
        // Use the user activity timeout requested by the keyguard view, if any.
        if (mKeyguardView != null) {
            long timeout = mKeyguardView.getUserActivityTimeout();
            if (timeout >= 0) {
                mWindowLayoutParams.userActivityTimeout = timeout;
                return;
            }
        }

        // Otherwise, use the default timeout.
        mWindowLayoutParams.userActivityTimeout = KeyguardViewMediator.AWAKE_INTERVAL_DEFAULT_MS;
    }

    private void maybeEnableScreenRotation(int rotationAngles) {
        // TODO: move this outside
        switch (rotationAngles) {
            case ROTATION_OFF:
                if (DEBUG) Log.d(TAG, "Rotation sensor for lock screen Off!");
                mWindowLayoutParams.screenOrientation
                        = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
                break;
            case ROTATION_ON:
                if (DEBUG) Log.d(TAG, "Rotation sensor for lock screen On!");
                mWindowLayoutParams.screenOrientation
                        = ActivityInfo.SCREEN_ORIENTATION_USER;
                break;
            case ROTATION_PORTRAIT:
                if (DEBUG) Log.d(TAG, "Rotation sensor for lock screen Portrait!");
                mWindowLayoutParams.screenOrientation
                        = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
                break;
            case ROTATION_LANDSCAPE:
                if (DEBUG) Log.d(TAG, "Rotation sensor for lock screen Landscape!");
                mWindowLayoutParams.screenOrientation
                        = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
                break;
        }
        mViewManager.updateViewLayout(mKeyguardHost, mWindowLayoutParams);
    }

    void updateShowWallpaper(boolean show) {
        if (show) {
            mWindowLayoutParams.flags |= WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
        } else {
            mWindowLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
        }
        mWindowLayoutParams.format = show ? PixelFormat.TRANSLUCENT : PixelFormat.OPAQUE;

        mViewManager.updateViewLayout(mKeyguardHost, mWindowLayoutParams);
    }

    public void setNeedsInput(boolean needsInput) {
        mNeedsInput = needsInput;
        if (mWindowLayoutParams != null) {
            if (needsInput) {
                mWindowLayoutParams.flags &=
                    ~WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
            } else {
                mWindowLayoutParams.flags |=
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
            }

            try {
                mViewManager.updateViewLayout(mKeyguardHost, mWindowLayoutParams);
            } catch (java.lang.IllegalArgumentException e) {
                // TODO: Ensure this method isn't called on views that are changing...
                Log.w(TAG,"Can't update input method on " + mKeyguardHost + " window not attached");
            }
        }
    }

    /**
     * Reset the state of the view.
     */
    public synchronized void reset(Bundle options) {
        if (DEBUG) Log.d(TAG, "reset()");
        // User might have switched, check if we need to go back to keyguard
        // TODO: It's preferable to stay and show the correct lockscreen or unlock if none
        maybeCreateKeyguardLocked(shouldEnableScreenRotation(), true, options);
    }

    public synchronized void onScreenTurnedOff() {
        if (DEBUG) Log.d(TAG, "onScreenTurnedOff()");
        mScreenOn = false;
        if (mKeyguardView != null) {
            mKeyguardView.onScreenTurnedOff();
        }
        if (mNotificationViewManager != null) {
            mNotificationViewManager.onScreenTurnedOff();
        }
    }

    public synchronized void onScreenTurnedOn(final IKeyguardShowCallback callback) {
        if (DEBUG) Log.d(TAG, "onScreenTurnedOn()");
        mScreenOn = true;

        final IBinder token;

        // If keyguard is disabled or not showing, we need to inform PhoneWindowManager with a null
        // token so it doesn't wait for us to draw...
        final boolean disabled =
                mLockPatternUtils.isLockScreenDisabled() && !mLockPatternUtils.isSecure();
        if (!isShowing() || disabled) {
            token = null;
        } else {
            token = mKeyguardHost.getWindowToken();
        }

        if (mKeyguardView != null) {
            mKeyguardView.onScreenTurnedOn();

            // Caller should wait for this window to be shown before turning
            // on the screen.
            if (callback != null) {
                if (mKeyguardHost.getVisibility() == View.VISIBLE) {
                    // Keyguard may be in the process of being shown, but not yet
                    // updated with the window manager...  give it a chance to do so.
                    mKeyguardHost.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                callback.onShown(token);
                            } catch (RemoteException e) {
                                Slog.w(TAG, "Exception calling onShown():", e);
                            }
                        }
                    });
                } else {
                    try {
                        callback.onShown(token);
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Exception calling onShown():", e);
                    }
                }
            }
        } else if (callback != null) {
            try {
                callback.onShown(token);
            } catch (RemoteException e) {
                Slog.w(TAG, "Exception calling onShown():", e);
            }
        }

        if (mLockscreenNotifications) {
            if (mNotificationViewManager != null) {
                mNotificationViewManager.onScreenTurnedOn();
            }
        }
    }

    public synchronized void verifyUnlock() {
        if (DEBUG) Log.d(TAG, "verifyUnlock()");
        show(null);
        mKeyguardView.verifyUnlock();
    }

    /**
     * Hides the keyguard view
     */
    public synchronized void hide() {
        if (DEBUG) Log.d(TAG, "hide()");

        if (mLockscreenNotifications) {
            if (mNotificationViewManager != null) {
                mNotificationViewManager.onDismiss();
            }
        }

        if (mKeyguardHost != null) {
            mKeyguardHost.setVisibility(View.GONE);

            // We really only want to preserve keyguard state for configuration changes. Hence
            // we should clear state of widgets (e.g. Music) when we hide keyguard so it can
            // start with a fresh state when we return.
            mStateContainer.clear();

            // Don't do this right away, so we can let the view continue to animate
            // as it goes away.
            if (mKeyguardView != null) {
                final KeyguardViewBase lastView = mKeyguardView;
                mKeyguardView = null;
                mKeyguardHost.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        synchronized (KeyguardViewManager.this) {
                            lastView.cleanUp();
                            // Let go of any large bitmaps.
                            mKeyguardHost.setCustomBackground(null);
                            updateShowWallpaper(true);
                            mKeyguardHost.removeView(lastView);
                            mViewMediatorCallback.keyguardGone();
                        }
                    }
                }, HIDE_KEYGUARD_DELAY);
            }
        }
    }

    /**
     * Dismisses the keyguard by going to the next screen or making it gone.
     */
    public synchronized void dismiss() {
        if (mScreenOn) {
            mKeyguardView.dismiss();
        }
    }

    /**
     * @return Whether the keyguard is showing
     */
    public synchronized boolean isShowing() {
        return (mKeyguardHost != null && mKeyguardHost.getVisibility() == View.VISIBLE);
    }

    public void showAssistant() {
        if (mKeyguardView != null) {
            mKeyguardView.showAssistant();
        }
    }

    public void showCustomIntent(Intent intent) {
        if (mKeyguardView != null) {
            mKeyguardView.showCustomIntent(intent);
        }
    }

    public void dispatch(MotionEvent event) {
        if (mKeyguardView != null) {
            mKeyguardView.dispatch(event);
        }
    }

    public void dispatchButtonClick(int buttonId) {
        mNotificationView.onButtonClick(buttonId);
    }

    public void launchCamera() {
        if (mKeyguardView != null) {
            mKeyguardView.launchCamera();
        }
    }
}
