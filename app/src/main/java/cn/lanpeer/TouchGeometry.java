package cn.lanpeer;

public final class TouchGeometry {
    private TouchGeometry() {}

    /** Returns normalized coordinates within the fitted video; black bars return null. */
    public static float[] normalize(float x, float y, int viewWidth, int viewHeight, int videoWidth, int videoHeight) {
        if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) return null;
        float scale = Math.min((float) viewWidth / videoWidth, (float) viewHeight / videoHeight);
        float width = videoWidth * scale, height = videoHeight * scale;
        float nx = (x - (viewWidth - width) / 2) / width;
        float ny = (y - (viewHeight - height) / 2) / height;
        if (nx < 0 || nx > 1 || ny < 0 || ny > 1) return null;
        return new float[]{nx, ny};
    }

    /** In fill mode the edges are cropped, so map the visible portion back to the full image. */
    public static float[] normalizeCrop(float x, float y, int viewWidth, int viewHeight, int videoWidth, int videoHeight) {
        if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) return null;
        float scale = Math.max((float) viewWidth / videoWidth, (float) viewHeight / videoHeight);
        float width = videoWidth * scale, height = videoHeight * scale;
        float nx = (x + (width - viewWidth) / 2) / width;
        float ny = (y + (height - viewHeight) / 2) / height;
        if (nx < 0 || nx > 1 || ny < 0 || ny > 1) return null;
        return new float[]{nx, ny};
    }
}
