package com.mattprecious.telescope;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.Handler;
import android.os.Vibrator;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;

import static android.animation.ValueAnimator.AnimatorUpdateListener;
import static android.graphics.Paint.Style;

/**
 * A layout used to take a screenshot and initiate a callback when the user long-presses the
 * container.
 */
public class TelescopeLayout extends FrameLayout {

	protected static final String TAG = "Telescope";
	protected static final SimpleDateFormat SCREENSHOT_FILE_FORMAT = new SimpleDateFormat("'screenshot'-yyyy-MM-dd-HHmmss.'png'");
	protected static final SimpleDateFormat LOGS_FILE_FORMAT = new SimpleDateFormat("'logs'-yyyy-MM-dd-HHmmss.'txt'");
	protected static final int PROGRESS_STROKE_DP = 4;
	protected static final long CANCEL_DURATION_MS = 250;
	protected static final long DONE_DURATION_MS = 1000;
	protected static final long TRIGGER_DURATION_MS = 1000;
	protected static final long VIBRATION_DURATION_MS = 50;

	protected static final int DEFAULT_POINTER_COUNT = 2;
	protected static final int DEFAULT_PROGRESS_COLOR = 0xff33b5e5;

	protected final Vibrator vibrator;
	protected final Handler handler = new Handler();
	protected final Runnable trigger = new Runnable() {
		@Override
		public void run() {
			trigger();
		}
	};

	protected final float halfStrokeWidth;
	protected final String filesPath;
	protected final Paint progressPaint;
	protected final ValueAnimator progressAnimator;
	protected final ValueAnimator progressCancelAnimator;
	protected final ValueAnimator doneAnimator;

	protected Lens lens;
	protected View screenshotTarget;
	protected int pointerCount;
	protected boolean screenshot;
	protected boolean screenshotChildrenOnly;
	protected boolean logs = true;
	protected boolean vibrate;

	// State.
	protected float progressFraction;
	protected float doneFraction;
	protected boolean pressing;
	protected boolean capturing;
	protected boolean saving;

	public TelescopeLayout(Context context) {
		this(context, null);
	}

	public TelescopeLayout(Context context, AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public TelescopeLayout(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		setWillNotDraw(false);
		screenshotTarget = this;

		float density = context.getResources().getDisplayMetrics().density;
		halfStrokeWidth = PROGRESS_STROKE_DP * density / 2;

		TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.TelescopeLayout, defStyle, 0);
		pointerCount = a.getInt(R.styleable.TelescopeLayout_pointerCount, DEFAULT_POINTER_COUNT);
		int progressColor =
				a.getColor(R.styleable.TelescopeLayout_progressColor, DEFAULT_PROGRESS_COLOR);
		screenshot = a.getBoolean(R.styleable.TelescopeLayout_screenshot, true);
		screenshotChildrenOnly =
				a.getBoolean(R.styleable.TelescopeLayout_screenshotChildrenOnly, false);
		vibrate = a.getBoolean(R.styleable.TelescopeLayout_vibrate, true);
		a.recycle();

		progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		progressPaint.setColor(progressColor);
		progressPaint.setStrokeWidth(PROGRESS_STROKE_DP * density);
		progressPaint.setStyle(Style.STROKE);

		AnimatorUpdateListener progressUpdateListener = new AnimatorUpdateListener() {
			@Override
			public void onAnimationUpdate(ValueAnimator animation) {
				progressFraction = (float) animation.getAnimatedValue();
				invalidate();
			}
		};

		progressAnimator = new ValueAnimator();
		progressAnimator.setDuration(TRIGGER_DURATION_MS);
		progressAnimator.addUpdateListener(progressUpdateListener);

		progressCancelAnimator = new ValueAnimator();
		progressCancelAnimator.setDuration(CANCEL_DURATION_MS);
		progressCancelAnimator.addUpdateListener(progressUpdateListener);

		doneFraction = 1;
		doneAnimator = ValueAnimator.ofFloat(0, 1);
		doneAnimator.setDuration(DONE_DURATION_MS);
		doneAnimator.addUpdateListener(new AnimatorUpdateListener() {
			@Override
			public void onAnimationUpdate(ValueAnimator animation) {
				doneFraction = (float) animation.getAnimatedValue();
				invalidate();
			}
		});

		if (isInEditMode()) {
			vibrator = null;
			filesPath = null;
			return;
		}

		vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
		filesPath = makeFilePath(context);
	}

	/**
	 * Delete the screenshot folder for this app. Be careful not to call this before any intents have
	 * finished using a screenshot reference.
	 */
	public static void cleanUp(Context context) {
		File path = new File(makeFilePath(context));
		if (!path.exists()) {
			return;
		}

		delete(path);
	}

	/** Set the {@link Lens} to be called when the user triggers a capture. */
	public void setLens(Lens lens) {
		this.lens = lens;
	}

	/** Set the number of pointers requires to trigger the capture. Default is 2. */
	public void setPointerCount(int pointerCount) {
		this.pointerCount = pointerCount;
	}

	/** Set the color of the progress bars. */
	public void setProgressColor(int progressColor) {
		progressPaint.setColor(progressColor);
	}

	/**
	 * <p>Set whether a screenshot will be taken when capturing. Default is true.</p>
	 *
	 * <p>
	 * <i>Requires the {@link android.Manifest.permission#WRITE_EXTERNAL_STORAGE} permission.</i>
	 * </p>
	 */
	public void setScreenshot(boolean screenshot) {
		this.screenshot = screenshot;
	}

	/**
	 * Set whether the screenshot will capture the children of this view only, or if it will
	 * capture the whole window this view is in. Default is false.
	 */
	public void setScreenshotChildrenOnly(boolean screenshotChildrenOnly) {
		this.screenshotChildrenOnly = screenshotChildrenOnly;
	}

	/** Set the target view that the screenshot will capture. */
	public void setScreenshotTarget(View screenshotTarget) {
		this.screenshotTarget = screenshotTarget;
	}

	public boolean isLogs() {
		return logs;
	}

	public void setLogs(boolean logs) {
		this.logs = logs;
	}

	/**
	 * <p>Set whether vibration is enabled when a capture is triggered. Default is true.</p>
	 *
	 * <p><i>Requires the {@link android.Manifest.permission#VIBRATE} permission.</i></p>
	 */
	public void setVibrate(boolean vibrate) {
		this.vibrate = vibrate;
	}

	@Override
	public boolean onInterceptTouchEvent(MotionEvent ev) {
		if (!isEnabled()) {
			return false;
		}

		// Capture all clicks while capturing/saving.
		if (capturing || saving) {
			return true;
		}

		if (ev.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN
				&& ev.getPointerCount() == pointerCount) {
			// onTouchEvent isn't called if we steal focus from a child, so call start here.
			start();

			// Steal the events from our children.
			return true;
		}

		return super.onInterceptTouchEvent(ev);
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		if (!isEnabled()) {
			return false;
		}

		// Capture all clicks while capturing/saving.
		if (capturing || saving) {
			return true;
		}

		switch (event.getActionMasked()) {
		case MotionEvent.ACTION_CANCEL:
		case MotionEvent.ACTION_UP:
		case MotionEvent.ACTION_POINTER_UP:
			if (pressing) {
				cancel();
			}

			return false;
		case MotionEvent.ACTION_DOWN:
			if (!pressing && event.getPointerCount() == pointerCount) {
				start();
			}
			return true;
		case MotionEvent.ACTION_POINTER_DOWN:
			if (event.getPointerCount() == pointerCount) {
				// There's a few cases where we'll get called called in both onInterceptTouchEvent and
				// here, so make sure we only start once.
				if (!pressing) {
					start();
				}
				return true;
			} else {
				cancel();
			}
			break;
		case MotionEvent.ACTION_MOVE:
			if (pressing) {
				invalidate();
				return true;
			}
			break;
		}

		return super.onTouchEvent(event);
	}

	@Override
	public void draw(Canvas canvas) {
		super.draw(canvas);

		// Do not draw any bars while we're capturing a screenshot.
		if (capturing) {
			return;
		}

		int width = getMeasuredWidth();
		int height = getMeasuredHeight();

		float fraction = saving ? 1 : progressFraction;
		if (fraction > 0) {
			// Top (left to right).
			canvas.drawLine(0, halfStrokeWidth, width * fraction, halfStrokeWidth, progressPaint);
			// Right (top to bottom).
			canvas.drawLine(width - halfStrokeWidth, 0, width - halfStrokeWidth, height * fraction,
					progressPaint);
			// Bottom (right to left).
			canvas.drawLine(width, height - halfStrokeWidth, width - (width * fraction),
					height - halfStrokeWidth, progressPaint);
			// Left (bottom to top).
			canvas.drawLine(halfStrokeWidth, height, halfStrokeWidth, height - (height * fraction),
					progressPaint);
		}

		if (doneFraction < 1) {
			// Top (left to right).
			canvas.drawLine(width * doneFraction, halfStrokeWidth, width, halfStrokeWidth, progressPaint);
			// Right (top to bottom).
			canvas.drawLine(width - halfStrokeWidth, height * doneFraction, width - halfStrokeWidth,
					height, progressPaint);
			// Bottom (right to left).
			canvas.drawLine(width - (width * doneFraction), height - halfStrokeWidth, 0,
					height - halfStrokeWidth, progressPaint);
			// Left (bottom to top).
			canvas.drawLine(halfStrokeWidth, height - (height * doneFraction), halfStrokeWidth, 0,
					progressPaint);
		}
	}

	public void start() {
		pressing = true;
		progressAnimator.setFloatValues(progressFraction, 1);
		progressAnimator.start();
		handler.postDelayed(trigger, TRIGGER_DURATION_MS);
	}

	protected void stop() {
		pressing = false;
	}

	protected void cancel() {
		stop();
		progressAnimator.cancel();
		progressCancelAnimator.setFloatValues(progressFraction, 0);
		progressCancelAnimator.start();
		handler.removeCallbacks(trigger);
	}

	protected void trigger() {
		stop();

		if (vibrate) {
			vibrator.vibrate(VIBRATION_DURATION_MS);
		}

		progressAnimator.end();
		progressFraction = 0;

		final String logs;
		if (isLogs()) {
			logs = readLogs();
		} else {
			logs = null;
		}

		if (screenshot) {
			capturing = true;
			invalidate();

			// Wait for the next frame to be sure our progress bars are hidden.
			post(new Runnable() {
				@Override
				public void run() {
					View view = getTargetView();
					view.setDrawingCacheEnabled(true);
					Bitmap screenshot = Bitmap.createBitmap(view.getDrawingCache());
					view.setDrawingCacheEnabled(false);

					capturing = false;
					new SaveTask(screenshot, logs).execute();
				}
			});
		} else {
			new SaveTask(null, logs).execute();
		}
	}

	/**
	 * Unless {@code screenshotChildrenOnly} is true, navigate up the layout hierarchy until we find
	 * the root view.
	 */
	protected View getTargetView() {
		View view = screenshotTarget;
		if (!screenshotChildrenOnly) {
			while (view.getRootView() != view) {
				view = view.getRootView();
			}
		}

		return view;
	}

	/** Recursive delete of a file or directory. */
	protected static void delete(File file) {
		if (file.isDirectory()) {
			for (File child : file.listFiles()) {
				delete(child);
			}
		}

		file.delete();
	}

	protected static String makeFilePath(Context context) {
		return Environment.getExternalStorageDirectory().toString()
				+ "/Telescope/"
				+ context.getPackageName();
	}

	protected static String readLogs() {
		try {
			final Process process = Runtime.getRuntime().exec("logcat -d");
			final BufferedReader bufferedReader = new BufferedReader(
					new InputStreamReader(process.getInputStream()));

			final StringBuilder log = new StringBuilder();
			String line;
			while ((line = bufferedReader.readLine()) != null) {
				log.append(line);
			}
			return log.toString();
		} catch (IOException e) {
			return "";
		}
	}

	/**
	 * Save a screenshot to external storage, start the done animation, and call the capture
	 * listener.
	 */
	protected class SaveTask extends AsyncTask<Void, Void, File[]> {

		protected final Bitmap screenshot;
		protected final String logs;

		protected SaveTask(Bitmap screenshot, String logs) {
			this.screenshot = screenshot;
			this.logs = logs;
		}

		@Override
		protected void onPreExecute() {
			saving = true;
			invalidate();
		}

		@Override
		protected File[] doInBackground(Void... params) {
			final File[] attachments = new File[2];
			try {
				final File path = new File(filesPath);
				path.mkdirs();

				if (screenshot != null) {
					final File file = new File(path, SCREENSHOT_FILE_FORMAT.format(new Date()));
					final FileOutputStream out = new FileOutputStream(file);
					screenshot.compress(Bitmap.CompressFormat.PNG, 100, out);
					out.flush();
					out.close();
					attachments[0] = file;
				}

				if (!TextUtils.isEmpty(logs)) {
					final File file = new File(path, LOGS_FILE_FORMAT.format(new Date()));
					final BufferedWriter writer = new BufferedWriter(new FileWriter(file));
					writer.write(logs);
					writer.flush();
					writer.close();
					attachments[1] = file;
				}

				return attachments;
			} catch (IOException e) {
				Log.e(TAG,
						"Failed to save screenshot. Is the WRITE_EXTERNAL_STORAGE permission requested?");
			}

			return null;
		}

		@Override
		protected void onPostExecute(File[] attachments) {
			saving = false;
			doneAnimator.start();

			if (lens != null) {
				lens.onCapture(attachments);
			}
		}
	}
}
