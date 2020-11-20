package com.example.tof;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.util.Log;

import java.nio.ShortBuffer;

public class DepthFrameAvailableListener implements ImageReader.OnImageAvailableListener {
    private static final String TAG = DepthFrameAvailableListener.class.getSimpleName();

    public static int WIDTH = 240;
    public static int HEIGHT = 180;

    private static float RANGE_MIN = 100.0f;
    private static float RANGE_MAX = 200.0f;
    private static float CONFIDENCE_FILTER = 0.99f;

    private DepthFrameVisualizer depthFrameVisualizer;
    private int[] rawMask;
    private int[] noiseReduceMask;
    private int[] averagedMask;
    private int[] blurredAverage;
    private int averageDistance;

    public DepthFrameAvailableListener(DepthFrameVisualizer depthFrameVisualizer) {
        this.depthFrameVisualizer = depthFrameVisualizer;

        int size = WIDTH * HEIGHT;
        rawMask = new int[size];
        noiseReduceMask = new int[size];
        averagedMask = new int[size];
        blurredAverage = new int[size];
    }

    @Override
    public void onImageAvailable(ImageReader reader) {
        try {
            Image image = reader.acquireNextImage();
            if (image != null && image.getFormat() == ImageFormat.DEPTH16) {
                processImage(image);
                publishRawData();
//                publishNoiseReduction();
//                publishMovingAverage();
//                publishBlurredMovingAverage();
            }
            image.close();
        }
        catch (Exception e) {
            Log.e(TAG, "Failed to acquireNextImage: " + e.getMessage());
        }
    }

    private void publishRawData() {
        if (depthFrameVisualizer != null) {
            Bitmap bitmap = convertToRGBBitmap(rawMask);
            Log.e(TAG, "Distance: " + averageDistance);
            depthFrameVisualizer.onRawDataAvailable(bitmap, averageDistance);
            bitmap.recycle();
        }
    }

    private void publishNoiseReduction() {
        if (depthFrameVisualizer != null) {
            Bitmap bitmap = convertToRGBBitmap(noiseReduceMask);
            depthFrameVisualizer.onNoiseReductionAvailable(bitmap);
            bitmap.recycle();
        }
    }

    private void publishMovingAverage() {
        if (depthFrameVisualizer != null) {
            Bitmap bitmap = convertToRGBBitmap(averagedMask);
            depthFrameVisualizer.onMovingAverageAvailable(bitmap);
            bitmap.recycle();
        }
    }

    private void publishBlurredMovingAverage() {
        if (depthFrameVisualizer != null) {
            Bitmap bitmap = convertToRGBBitmap(blurredAverage);
            depthFrameVisualizer.onBlurredMovingAverageAvailable(bitmap);
            bitmap.recycle();
        }
    }

    private void processImage(Image image) {
        ShortBuffer shortDepthBuffer = image.getPlanes()[0].getBuffer().asShortBuffer();
        int[] mask = new int[WIDTH * HEIGHT];
        int[] depthList = new int[WIDTH * HEIGHT];
        int len = rawMask.length;
        int middlePoint = (int)(rawMask.length / 2 - 1);
        Log.i(TAG, "" + middlePoint);
        int[] noiseReducedMask = new int[WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int index = y * WIDTH + x;
                short depthSample = shortDepthBuffer.get(index);
                int newValue = extractRange(depthSample, CONFIDENCE_FILTER);
                int normalizedValue = normalizeRange(newValue);
                // Store value in the rawMask for visualization
                // 因为用的是后置摄像头，图像是旋转 180 度的，所以倒序赋值数组进行旋转
                depthList[len - index - 1] = newValue;
                rawMask[len - index - 1] = normalizedValue;
//                if (index == middlePoint) {
//                    midDistance = newValue;
//                }

//                int p1Value = averagedMask[index];
//                int p2Value = averagedMaskP2[index];
//                int avgValue = (newValue + p1Value + p2Value) / 3;
//                if (p1Value < 0 || p2Value < 0 || newValue < 0) {
//                    Log.d("TAG", "WHAT");
//                }
//                // Store the new moving average temporarily
//                mask[index] = avgValue;
            }
        }

        // 只取中心的 2 * centerPercent 作为计算深度的依据
        float centerPercent = 0.05f;  // 0 ~ 0.5
        int yStart = (int)((0.5f - centerPercent) * HEIGHT);
        int yEnd = (int)((0.5f + centerPercent) * HEIGHT);
        int xStart = (int)((0.5f - centerPercent) * WIDTH);
        int xEnd = (int)((0.5f + centerPercent) * WIDTH);

        int sum = 0;
        int counter = 0;
        for (int y = yStart; y < yEnd; y++) {
            for (int x = xStart; x < xEnd; x++) {
                int index = y * WIDTH + x;
                int depth = depthList[index];
                if ( depth > 0) {
                    sum += depth;
                    counter += 1;
                }
            }
        }
        if (counter == 0) {
            middlePoint = 0;
        } else {
            averageDistance = sum / counter;
        }

        Log.i(TAG, "" + middlePoint);


//        // Produce a noise reduced version of the raw mask for visualization
//        System.arraycopy(rawMask, 0, noiseReducedMask, 0, rawMask.length);
//        noiseReduceMask = FastBlur.boxBlur(noiseReducedMask, WIDTH, HEIGHT, 1);
//
//        // Remember the last two frames for moving average
//        averagedMaskP2 = averagedMask;
//        averagedMask = mask;
//
//        // Produce a blurred version of the latest moving average result
//        System.arraycopy(averagedMask, 0, blurredAverage, 0, averagedMask.length);
//        blurredAverage = FastBlur.boxBlur(blurredAverage, WIDTH, HEIGHT, 1);
    }

    private int extractRange(short sample, float confidenceFilter) {
        int depthRange = (short) (sample & 0x1FFF);
        int depthConfidence = (short) ((sample >> 13) & 0x7);
        float depthPercentage = depthConfidence == 0 ? 1.f : (depthConfidence - 1) / 7.f;
        if (depthPercentage > confidenceFilter) {
            return depthRange;
        } else {
            return 0;
        }
    }

    private int normalizeRange(int range) {
        float normalized = (float)range - RANGE_MIN;
        // Clamp to min/max
        normalized = Math.max(RANGE_MIN, normalized);
        normalized = Math.min(RANGE_MAX, normalized);
        // Normalize to 0 to 255
        normalized = normalized - RANGE_MIN;
        normalized = normalized / (RANGE_MAX - RANGE_MIN) * 255;
        return (int)normalized;
    }

    private Bitmap convertToRGBBitmap(int[] mask) {
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_4444);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int index = y * WIDTH + x;
                bitmap.setPixel(x, y, Color.argb(255, 0, mask[index],0));
            }
        }
        return bitmap;
    }

}