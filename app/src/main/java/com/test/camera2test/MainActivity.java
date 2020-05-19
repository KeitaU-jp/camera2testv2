package com.test.camera2test;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;

public class MainActivity extends AppCompatActivity {
    private static final int STATE_INIT = -1;
    private static final int STATE_WAITING_LOCK = 0;
    private static final int STATE_WAITING_PRE_CAPTURE = 1;
    private static final int STATE_WAITING_NON_PRE_CAPTURE = 2;
    private static final int AF_SAME_STATE_REPEAT_MAX = 20;

    private int mState;
    private int mSameAFStateCount;
    private int mPreAFState;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }

    public void startAutoFocus(PointF[] focusPoints, Context context) {
        int maxRegionsAF = 0;
        Rect activeArraySize = null;
        CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(mOpenCameraId);
            maxRegionsAF = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
            activeArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        if (activeArraySize == null) {
            activeArraySize = new Rect();
        }

        if (maxRegionsAF <= 0) {
            return;
        }
        if (focusPoints == null) {
            return;
        }

        // フォーカス範囲
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int r = (int)(4 * metrics.density);

        MeteringRectangle[] afRegions = new MeteringRectangle[focusPoints.length];
        for (int i = 0; i < focusPoints.length; i++) {
            int x = (int)(activeArraySize.width() * focusPoints[i].x);
            int y = (int)(activeArraySize.height() * focusPoints[i].y);
            Rect p = new Rect(Math.max(activeArraySize.bottom, x - r), Math.max(activeArraySize.top, y - r),
                    Math.min(x + r, activeArraySize.right), Math.min(y + r, activeArraySize.bottom));
            afRegions[i] = new MeteringRectangle(p, MeteringRectangle.METERING_WEIGHT_MAX);
        }

        // 状態初期化
        mState = STATE_WAITING_LOCK;
        mSameAFStateCount = 0;
        mPreAFState = -1;

        try {
            CaptureRequest.Builder captureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            if (null != mPreviewSurface) {
                captureBuilder.addTarget(mPreviewSurface);
            }
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            if (0 < afRegions.length) {
                captureBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, afRegions);
            }
            captureBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
            mCaptureSession.setRepeatingRequest(captureBuilder.build(), mAFListener, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    CameraCaptureSession.CaptureCallback mAFListener = new CameraCaptureSession.CaptureCallback() {

        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);

            if (mState == STATE_WAITING_LOCK) {
                Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                if (afState == null) {
                    Log.w(TAG, "onCaptureCompleted AF STATE is null");
                    mState = STATE_INIT;
                    autoFocusEnd(false);
                    return;
                }

                if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                        afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    Log.i(TAG, "onCaptureCompleted AF STATE = " + afState + ", AE STATE = " + aeState);
                    if (mCancel || (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED)) {
                        mState = STATE_INIT;
                        autoFocusEnd(false);
                        return;
                    }
                }

                if (afState != CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN && afState == mPreAFState) {
                    mSameAFStateCount++;
                    // 同一状態上限
                    if (mSameAFStateCount >= AF_SAME_STATE_REPEAT_MAX) {
                        mState = STATE_INIT;
                        autoFocusEnd(false);
                        return;
                    }
                } else {
                    mSameAFStateCount = 0;
                }
                mPreAFState = afState;
                return;
            }

            if (mState == STATE_WAITING_PRE_CAPTURE) {
                Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                Log.i(TAG, "WAITING_PRE_CAPTURE AE STATE = " + aeState);
                if (aeState == null ||
                        aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                        aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                    mState = STATE_WAITING_NON_PRE_CAPTURE;
                } else if (aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                    mState = STATE_INIT;
                    autoFocusEnd(true);
                }
                return;
            }

            if (mState == STATE_WAITING_NON_PRE_CAPTURE) {
                Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                Log.i(TAG, "WAITING_NON_PRE_CAPTURE AE STATE = " + aeState);
                if (aeState == null ||
                        aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                    mState = STATE_INIT;
                    autoFocusEnd(true);
                }
            }
        }

        private void autoFocusEnd(boolean isSuccess) {
            // フォーカス完了/失敗時の処理
        }
    };


}
