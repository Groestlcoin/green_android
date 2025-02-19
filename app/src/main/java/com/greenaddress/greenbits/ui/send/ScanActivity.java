package com.greenaddress.greenbits.ui.send;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.ColorDrawable;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.afollestad.materialdialogs.MaterialDialog;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.android.material.textfield.TextInputEditText;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.ResultPointCallback;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.greenaddress.greenapi.data.BalanceData;
import com.greenaddress.greenapi.data.SweepData;
import com.greenaddress.greenbits.GaService;
import com.greenaddress.greenbits.GreenAddressApplication;
import com.greenaddress.greenbits.ui.GaActivity;
import com.greenaddress.greenbits.ui.R;
import com.greenaddress.greenbits.ui.UI;
import com.greenaddress.greenbits.ui.assets.AssetsSelectActivity;
import com.greenaddress.greenbits.ui.preferences.PrefKeys;

import org.bitcoinj.core.AddressFormatException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import de.schildbach.wallet.ui.scan.CameraManager;

import static com.greenaddress.gdk.GDKSession.getSession;
import static com.greenaddress.greenbits.ui.TabbedMainActivity.REQUEST_BITCOIN_URL_SEND;

public class ScanActivity extends GaActivity implements TextureView.SurfaceTextureListener, View.OnClickListener,
    TextWatcher {

    public static final String INTENT_STRING_TX = "intent_string_tx";

    private static final long AUTO_FOCUS_INTERVAL_MS = 2500L;

    private final CameraManager cameraManager = new CameraManager();

    private boolean isSweep;
    private View contentView;
    private de.schildbach.wallet.ui.scan.ScannerView scannerView;
    private TextureView previewView;
    private TextInputEditText mAddressEditText;

    private volatile boolean surfaceCreated = false;
    private Animator sceneTransition = null;

    private HandlerThread cameraThread;
    private volatile Handler cameraHandler;

    private static final int DIALOG_CAMERA_PROBLEM = 0;

    private static final Logger log = LoggerFactory.getLogger(ScanActivity.class);

    @Override
    protected void onCreateWithService(final Bundle savedInstanceState) {
        UI.preventScreenshots(this);

        getSupportActionBar().setElevation(0);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setDisplayShowHomeEnabled(true);
        getSupportActionBar().setBackgroundDrawable(getResources().getDrawable(android.R.color.transparent));

        isSweep = getIntent().getBooleanExtra(PrefKeys.SWEEP, false);

        if (isSweep)
            setTitle(R.string.id_sweep);

        // Stick to the orientation the activity was started with. We cannot declare this in the
        // AndroidManifest.xml, because it's not allowed in combination with the windowIsTranslucent=true
        // theme attribute.
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);

        setContentView(R.layout.activity_send_scan);
        contentView = findViewById(android.R.id.content);
        scannerView = findViewById(R.id.scan_activity_mask);
        previewView = findViewById(R.id.scan_activity_preview);
        previewView.setSurfaceTextureListener(this);

        cameraThread = new HandlerThread("cameraThread", Process.THREAD_PRIORITY_BACKGROUND);
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.CAMERA }, 0);

        mAddressEditText = UI.find(this, R.id.addressEdit);
        mAddressEditText.setHint(
            isSweep ? R.string.id_enter_a_private_key_to_sweep : R.string.id_enter_an_address);

        UI.find(this, R.id.nextButton).setEnabled(false);

        UI.attachHideKeyboardListener(this, findViewById(R.id.activity_send_scan));
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
        case android.R.id.home:
            onBackPressed();
            return true;
        default:
            return super.onOptionsItemSelected(item);
        }
    }

    private void maybeTriggerSceneTransition() {
        if (sceneTransition != null) {
            contentView.setAlpha(1);
            sceneTransition.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    getWindow()
                    .setBackgroundDrawable(new ColorDrawable(getResources().getColor(android.R.color.black)));
                }
            });
            sceneTransition.start();
            sceneTransition = null;
        }
    }

    @Override
    public void onResumeWithService() {
        mAddressEditText.addTextChangedListener(this);
        UI.find(this, R.id.nextButton).setOnClickListener(this);
        maybeOpenCamera();
    }

    @Override
    public void onPauseWithService() {
        cameraHandler.post(closeRunnable);
        mAddressEditText.removeTextChangedListener(this);
        UI.find(this, R.id.nextButton).setOnClickListener(null);
    }

    @Override
    protected void onDestroy() {
        // cancel background thread
        if (cameraHandler != null) {
            cameraHandler.removeCallbacksAndMessages(null);
        }

        if (cameraThread != null) {
            cameraThread.quit();
        }

        if (previewView != null) {
            previewView.setSurfaceTextureListener(null);
        }

        // We're removing the requested orientation because if we don't, somehow the requested orientation is
        // bleeding through to the calling activity, forcing it into a locked state until it is restarted.
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode, final String[] permissions,
                                           final int[] grantResults) {
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
            maybeOpenCamera();
        else
            UI.toast(this, R.string.id_please_enable_camera, Toast.LENGTH_LONG);
    }

    private void maybeOpenCamera() {
        if (surfaceCreated && ContextCompat.checkSelfPermission(this,
                                                                Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED)
            cameraHandler.post(openRunnable);
    }


    @Override
    public void onSurfaceTextureAvailable(final SurfaceTexture surface, final int width, final int height) {
        surfaceCreated = true;
        maybeOpenCamera();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(final SurfaceTexture surface) {
        surfaceCreated = false;
        return true;
    }

    @Override
    public void onSurfaceTextureSizeChanged(final SurfaceTexture surface, final int width, final int height) {}

    @Override
    public void onSurfaceTextureUpdated(final SurfaceTexture surface) {}

    @Override
    public void onAttachedToWindow() {}

    @Override
    public void onBackPressed() {
        scannerView.setVisibility(View.GONE);
        setResult(RESULT_CANCELED);
        finish();
    }

    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent event) {
        switch (keyCode) {
        case KeyEvent.KEYCODE_FOCUS:
        case KeyEvent.KEYCODE_CAMERA:
            // don't launch camera app
            return true;
        case KeyEvent.KEYCODE_VOLUME_DOWN:
        case KeyEvent.KEYCODE_VOLUME_UP:
            cameraHandler.post(() -> cameraManager.setTorch(keyCode == KeyEvent.KEYCODE_VOLUME_UP));
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    private final Runnable openRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                final Camera camera = cameraManager.open(previewView, displayRotation());

                final Rect framingRect = cameraManager.getFrame();
                final RectF framingRectInPreview = new RectF(cameraManager.getFramePreview());
                framingRectInPreview.offsetTo(0, 0);
                final boolean cameraFlip = cameraManager.getFacing() == Camera.CameraInfo.CAMERA_FACING_FRONT;
                final int cameraRotation = cameraManager.getOrientation();

                runOnUiThread(() ->
                              scannerView.setFraming(framingRect, framingRectInPreview, displayRotation(),
                                                     cameraRotation,
                                                     cameraFlip));

                final String focusMode = camera.getParameters().getFocusMode();
                final boolean nonContinuousAutoFocus = Camera.Parameters.FOCUS_MODE_AUTO.equals(focusMode)
                                                       || Camera.Parameters.FOCUS_MODE_MACRO.equals(focusMode);

                if (nonContinuousAutoFocus)
                    cameraHandler.post(new AutoFocusRunnable(camera));

                maybeTriggerSceneTransition();
                cameraHandler.post(fetchAndDecodeRunnable);
            } catch (final Exception x) {
                log.info("problem opening camera", x);
                runOnUiThread(() -> showDialog(DIALOG_CAMERA_PROBLEM));
            }
        }

        private int displayRotation() {
            final int rotation = getWindowManager().getDefaultDisplay().getRotation();
            if (rotation == Surface.ROTATION_0)
                return 0;
            else if (rotation == Surface.ROTATION_90)
                return 90;
            else if (rotation == Surface.ROTATION_180)
                return 180;
            else if (rotation == Surface.ROTATION_270)
                return 270;
            else
                throw new IllegalStateException("rotation: " + rotation);
        }
    };

    private final Runnable closeRunnable = new Runnable() {
        @Override
        public void run() {
            cameraHandler.removeCallbacksAndMessages(null);
            cameraManager.close();
        }
    };

    @Override
    public void beforeTextChanged(final CharSequence charSequence, final int i, final int i1, final int i2) {}

    @Override
    public void onTextChanged(final CharSequence charSequence, final int i, final int i1, final int i2) {}

    @Override
    public void afterTextChanged(final Editable editable) {
        UI.enableIf(editable.length() > 0, UI.find(this, R.id.nextButton));
    }

    private final class AutoFocusRunnable implements Runnable {
        private final Camera camera;

        AutoFocusRunnable(final Camera camera) {
            this.camera = camera;
        }

        @Override
        public void run() {
            try {
                camera.autoFocus(autoFocusCallback);
            } catch (final Exception x) {
                log.info("problem with auto-focus, will not schedule again", x);
            }
        }

        private final Camera.AutoFocusCallback autoFocusCallback = new Camera.AutoFocusCallback() {
            @Override
            public void onAutoFocus(final boolean success, final Camera camera) {
                // schedule again
                cameraHandler.postDelayed(AutoFocusRunnable.this, AUTO_FOCUS_INTERVAL_MS);
            }
        };
    }

    public void handleResult(final Result scanResult, final Bitmap thumbnailImage, final float thumbnailScaleFactor)
    {
        // vibrator.vibrate(VIBRATE_DURATION);
        //scannerView.drawResultBitmap(thumbnailImage);

        // superimpose dots to highlight the key features of the qr code
        final ResultPoint[] points = scanResult.getResultPoints();
        if (points != null && points.length > 0)
        {
            final Paint paint = new Paint();
            paint.setColor(getResources().getColor(R.color.scan_result_dots));
            paint.setStrokeWidth(10.0f);

            final Canvas canvas = new Canvas(thumbnailImage);
            canvas.scale(thumbnailScaleFactor, thumbnailScaleFactor);
            for (final ResultPoint point : points)
                canvas.drawPoint(point.getX(), point.getY(), paint);
        }

        scannerView.setIsResult(true);
        onInserted(scanResult.getText());
    }

    public void onInserted(final String scanned) {
        final GaService service = ((GreenAddressApplication) getApplication()).mService;

        if (service == null)
            return;

        final Intent result = new Intent();
        result.putExtra("internal_qr", true);
        final Integer subaccount = service.getModel().getCurrentSubaccount();

        if (isSweep) {
            result.putExtra(PrefKeys.SWEEP, true);
            // See if the address is a private key, and if so, sweep it
            final Long feeRate = service.getFeeEstimates().get(0);
            final String receiveAddress =
                service.getModel().getReceiveAddressObservable(subaccount).getReceiveAddress();
            final BalanceData balanceData = new BalanceData();
            balanceData.setAddress(receiveAddress);
            final List<BalanceData> balanceDataList = new ArrayList<>();
            balanceDataList.add(balanceData);
            SweepData sweepData = new SweepData();
            sweepData.setPrivateKey(scanned);
            sweepData.setFeeRate(feeRate);
            sweepData.setAddressees(balanceDataList);
            sweepData.setSubaccount(subaccount);
            final ObjectNode transactionRaw = getSession().createTransactionRaw(sweepData);
            final String error = transactionRaw.get("error").asText();
            if (error.isEmpty()) {
                result.putExtra(INTENT_STRING_TX, transactionRaw.toString());
            } else {
                UI.toast(this, error, Toast.LENGTH_LONG);
                cameraHandler.post(fetchAndDecodeRunnable);
                return;
            }
        } else {
            final boolean startWithBitcoin = scanned.toLowerCase().startsWith("bitcoin:");
            if (service.isLiquid() && startWithBitcoin) {
                UI.toast(this, R.string.id_invalid_address, Toast.LENGTH_SHORT);
                return;
            }
            String text;

            if (service.isLiquid()) {
                text = scanned;
            } else {
                text = startWithBitcoin ? scanned : String.format("bitcoin:%s", scanned);
                // qrcodes of bech32 addresses that use alphanumeric mode may be read as uppercase
                final int i = text.indexOf("1");
                final String hrp = i == -1 ? "" : text.substring(8, i).toLowerCase();
                if (!service.isLiquid() && text.substring(8).equals(text.substring(8).toUpperCase()) &&
                    (hrp.equals("bc") || hrp.equals("tb"))) {
                    text = text.toLowerCase();
                }
            }
            try {
                final ObjectNode transactionFromUri =
                    getSession().createTransactionFromUri(text, subaccount);
                result.putExtra(INTENT_STRING_TX, transactionFromUri.toString());
            } catch (final AddressFormatException e) {
                UI.toast(this, R.string.id_invalid_address, Toast.LENGTH_SHORT);
                cameraHandler.post(fetchAndDecodeRunnable);
                return;
            } catch (final Exception e) {
                if (e.getMessage() != null)
                    UI.toast(this, e.getMessage(), Toast.LENGTH_SHORT);
                cameraHandler.post(fetchAndDecodeRunnable);
                return;
            }
        }
        if (mService.isLiquid()) {
            result.setClass(this, AssetsSelectActivity.class);
        } else {
            result.setClass(this, SendAmountActivity.class);
        }
        // Open send activity
        startActivityForResult(result, REQUEST_BITCOIN_URL_SEND);
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_BITCOIN_URL_SEND && resultCode == RESULT_OK)
            finish();
    }

    @Override
    public void onClick(final View view) {
        onInserted(mAddressEditText.getText().toString());
    }


    private final Runnable fetchAndDecodeRunnable = new Runnable()
    {
        private final QRCodeReader reader = new QRCodeReader();
        private final Map<DecodeHintType, Object> hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);

        @Override
        public void run()
        {
            cameraManager.requestPreviewFrame((data, camera) -> decode(data));
        }

        private void decode(final byte[] data)
        {
            final PlanarYUVLuminanceSource source = cameraManager.buildLuminanceSource(data);
            final BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

            try {
                hints.put(DecodeHintType.NEED_RESULT_POINT_CALLBACK,
                          (ResultPointCallback) dot -> runOnUiThread(() -> scannerView.addDot(dot)));
                final Result scanResult = reader.decode(bitmap, hints);

                final int thumbnailWidth = source.getThumbnailWidth();
                final int thumbnailHeight = source.getThumbnailHeight();
                final float thumbnailScaleFactor = (float) thumbnailWidth / source.getWidth();

                final Bitmap thumbnailImage = Bitmap.createBitmap(thumbnailWidth, thumbnailHeight,
                                                                  Bitmap.Config.ARGB_8888);
                thumbnailImage.setPixels(
                    source.renderThumbnail(), 0, thumbnailWidth, 0, 0, thumbnailWidth, thumbnailHeight);

                runOnUiThread(() -> handleResult(scanResult, thumbnailImage, thumbnailScaleFactor));
            } catch (final ReaderException x) {
                // retry
                cameraHandler.post(fetchAndDecodeRunnable);
            } finally {
                reader.reset();
            }
        }
    };

    @Override
    protected Dialog onCreateDialog(final int id)
    {
        if (id == DIALOG_CAMERA_PROBLEM)
        {
            MaterialDialog.Builder builder = new MaterialDialog.Builder(this)
                                             .title(getResources().getString(R.string.id_camera_problem))
                                             .content(getResources().getString(R.string.id_the_camera_has_a_problem_you))
                                             .callback(new MaterialDialog.ButtonCallback() {
                @Override
                public void onPositive(MaterialDialog dialog) {
                    super.onPositive(dialog);
                    finish();
                }

                @Override
                public void onNegative(MaterialDialog dialog) {
                    super.onNegative(dialog);
                    finish();
                }

                @Override
                public void onNeutral(MaterialDialog dialog) {
                    super.onNeutral(dialog);
                    finish();
                }
            });
            return builder.build();
        }else {
            throw new IllegalArgumentException();
        }
    }
}
