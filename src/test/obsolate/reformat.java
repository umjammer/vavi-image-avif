// Copyright 2019 Joe Drago. All rights reserved.
// SPDX-License-Identifier: BSD-2-Clause

package vavi.awt.image.avif;

import java.util.EnumSet;

import vavi.awt.image.avif.alpha.avifAlphaParams;
import vavi.awt.image.avif.avif.avifChannelIndex;
import vavi.awt.image.avif.avif.avifChromaUpsampling;
import vavi.awt.image.avif.avif.avifImage;
import vavi.awt.image.avif.avif.avifMatrixCoefficients;
import vavi.awt.image.avif.avif.avifPixelFormat;
import vavi.awt.image.avif.avif.avifPixelFormat.avifPixelFormatInfo;
import vavi.awt.image.avif.avif.avifPlanesFlag;
import vavi.awt.image.avif.avif.avifRGBImage;
import vavi.awt.image.avif.avif.avifRange;
import vavi.awt.image.avif.avif.avifResult;
import vavi.util.ByteUtil;

class reformat {

    class avifReformatState
    {
        // YUV coefficients
        float[] kr = new float[1];
        float[] kg = new float[1];
        float[] kb = new float[1];

        int yuvChannelBytes;
        int rgbChannelBytes;
        int rgbChannelCount;
        int rgbPixelBytes;
        int rgbOffsetBytesR;
        int rgbOffsetBytesG;
        int rgbOffsetBytesB;
        int rgbOffsetBytesA;

        int yuvDepth;
        avifRange yuvRange;
        int yuvMaxChannel;
        int rgbMaxChannel;
        float rgbMaxChannelF;
        float biasY;   // minimum Y value
        float biasUV;  // the value of 0.5 for the appropriate bit depth [128, 512, 2048]
        float biasA;   // minimum A value
        float rangeY;  // difference between max and min Y
        float rangeUV; // difference between max and min UV
        float rangeA;  // difference between max and min A

        avifPixelFormatInfo formatInfo;

        // LUTs for going from YUV limited/full unorm -> full range RGB FP32
        float[] unormFloatTableY = new float[1 << 12];
        float[] unormFloatTableUV = new float[1 << 12];

        avifReformatMode mode;
        // Used by avifImageYUVToRGB() only. avifImageRGBToYUV() uses a local variable (alphaMode) instead.
        avifAlphaMultiplyMode toRGBAlphaMode;
    }

    enum avifReformatMode
    {
        AVIF_REFORMAT_MODE_YUV_COEFFICIENTS, // Normal YUV conversion using coefficients
        AVIF_REFORMAT_MODE_IDENTITY,             // Pack GBR directly into YUV planes (AVIF_MATRIX_COEFFICIENTS_IDENTITY)
        AVIF_REFORMAT_MODE_YCGCO                 // YUV conversion using AVIF_MATRIX_COEFFICIENTS_YCGCO
    }

    enum avifAlphaMultiplyMode
    {
        AVIF_ALPHA_MULTIPLY_MODE_NO_OP,
        AVIF_ALPHA_MULTIPLY_MODE_MULTIPLY,
        AVIF_ALPHA_MULTIPLY_MODE_UNMULTIPLY
    }

    class YUVBlock
{
    float y;
    float u;
    float v;
};

colr colr = new colr();

private boolean avifPrepareReformatState(final avifImage image, final avifRGBImage rgb, avifReformatState state)
{
    if ((image.depth != 8) && (image.depth != 10) && (image.depth != 12)) {
        return false;
    }
    if ((rgb.depth != 8) && (rgb.depth != 10) && (rgb.depth != 12) && (rgb.depth != 16)) {
        return false;
    }
    if (rgb.isFloat && rgb.depth != 16) {
        return false;
    }

    // These matrix coefficients values are currently unsupported. Revise this list as more support is added.
    //
    // YCgCo performs limited-full range adjustment on R,G,B but the current implementation performs range adjustment
    // on Y,U,V. So YCgCo with limited range is unsupported.
    if ((image.matrixCoefficients.v == 3 /* CICP reserved */) ||
        ((image.matrixCoefficients == avifMatrixCoefficients.AVIF_MATRIX_COEFFICIENTS_YCGCO) && (image.yuvRange == avifRange.AVIF_RANGE_LIMITED)) ||
        (image.matrixCoefficients == avifMatrixCoefficients.AVIF_MATRIX_COEFFICIENTS_BT2020_CL) ||
        (image.matrixCoefficients == avifMatrixCoefficients.AVIF_MATRIX_COEFFICIENTS_SMPTE2085) ||
        (image.matrixCoefficients == avifMatrixCoefficients.AVIF_MATRIX_COEFFICIENTS_CHROMA_DERIVED_CL) ||
        (image.matrixCoefficients.v >= avifMatrixCoefficients.AVIF_MATRIX_COEFFICIENTS_ICTCP.v)) { // Note the >= catching "future" CICP values here too
        return false;
    }

    if ((image.matrixCoefficients == avifMatrixCoefficients.AVIF_MATRIX_COEFFICIENTS_IDENTITY) && (image.yuvFormat != avifPixelFormat.AVIF_PIXEL_FORMAT_YUV444)) {
        return false;
    }

    if (image.yuvFormat == avifPixelFormat.AVIF_PIXEL_FORMAT_NONE) {
        return false;
    }

    state.formatInfo = image.yuvFormat.avifGetPixelFormatInfo();
    colr.avifCalcYUVCoefficients(image, state.kr, state.kg, state.kb);
    state.mode = avifReformatMode.AVIF_REFORMAT_MODE_YUV_COEFFICIENTS;

    if (image.matrixCoefficients == avifMatrixCoefficients.AVIF_MATRIX_COEFFICIENTS_IDENTITY) {
        state.mode = avifReformatMode.AVIF_REFORMAT_MODE_IDENTITY;
    } else if (image.matrixCoefficients == avifMatrixCoefficients.AVIF_MATRIX_COEFFICIENTS_YCGCO) {
        state.mode = avifReformatMode.AVIF_REFORMAT_MODE_YCGCO;
    }

    if (state.mode != avifReformatMode.AVIF_REFORMAT_MODE_YUV_COEFFICIENTS) {
        state.kr[0] = 0.0f;
        state.kg[0] = 0.0f;
        state.kb[0] = 0.0f;
    }

    state.yuvChannelBytes = (image.depth > 8) ? 2 : 1;
    state.rgbChannelBytes = (rgb.depth > 8) ? 2 : 1;
    state.rgbChannelCount = rgb.format.avifRGBFormatChannelCount();
    state.rgbPixelBytes = state.rgbChannelBytes * state.rgbChannelCount;

    switch (rgb.format) {
        case AVIF_RGB_FORMAT_RGB:
            state.rgbOffsetBytesR = state.rgbChannelBytes * 0;
            state.rgbOffsetBytesG = state.rgbChannelBytes * 1;
            state.rgbOffsetBytesB = state.rgbChannelBytes * 2;
            state.rgbOffsetBytesA = 0;
            break;
        case AVIF_RGB_FORMAT_RGBA:
            state.rgbOffsetBytesR = state.rgbChannelBytes * 0;
            state.rgbOffsetBytesG = state.rgbChannelBytes * 1;
            state.rgbOffsetBytesB = state.rgbChannelBytes * 2;
            state.rgbOffsetBytesA = state.rgbChannelBytes * 3;
            break;
        case AVIF_RGB_FORMAT_ARGB:
            state.rgbOffsetBytesA = state.rgbChannelBytes * 0;
            state.rgbOffsetBytesR = state.rgbChannelBytes * 1;
            state.rgbOffsetBytesG = state.rgbChannelBytes * 2;
            state.rgbOffsetBytesB = state.rgbChannelBytes * 3;
            break;
        case AVIF_RGB_FORMAT_BGR:
            state.rgbOffsetBytesB = state.rgbChannelBytes * 0;
            state.rgbOffsetBytesG = state.rgbChannelBytes * 1;
            state.rgbOffsetBytesR = state.rgbChannelBytes * 2;
            state.rgbOffsetBytesA = 0;
            break;
        case AVIF_RGB_FORMAT_BGRA:
            state.rgbOffsetBytesB = state.rgbChannelBytes * 0;
            state.rgbOffsetBytesG = state.rgbChannelBytes * 1;
            state.rgbOffsetBytesR = state.rgbChannelBytes * 2;
            state.rgbOffsetBytesA = state.rgbChannelBytes * 3;
            break;
        case AVIF_RGB_FORMAT_ABGR:
            state.rgbOffsetBytesA = state.rgbChannelBytes * 0;
            state.rgbOffsetBytesB = state.rgbChannelBytes * 1;
            state.rgbOffsetBytesG = state.rgbChannelBytes * 2;
            state.rgbOffsetBytesR = state.rgbChannelBytes * 3;
            break;

        default:
            return false;
    }

    state.yuvDepth = image.depth;
    state.yuvRange = image.yuvRange;
    state.yuvMaxChannel = (1 << image.depth) - 1;
    state.rgbMaxChannel = (1 << rgb.depth) - 1;
    state.rgbMaxChannelF = state.rgbMaxChannel;
    state.biasY = (state.yuvRange == avifRange.AVIF_RANGE_LIMITED) ? (float)(16 << (state.yuvDepth - 8)) : 0.0f;
    state.biasUV = 1 << (state.yuvDepth - 1);
    state.biasA = (image.alphaRange == avifRange.AVIF_RANGE_LIMITED) ? (float)(16 << (state.yuvDepth - 8)) : 0.0f;
    state.rangeY = (state.yuvRange == avifRange.AVIF_RANGE_LIMITED) ? (219 << (state.yuvDepth - 8)) : state.yuvMaxChannel;
    state.rangeUV = (state.yuvRange == avifRange.AVIF_RANGE_LIMITED) ? (224 << (state.yuvDepth - 8)) : state.yuvMaxChannel;
    state.rangeA = (image.alphaRange == avifRange.AVIF_RANGE_LIMITED) ? (219 << (state.yuvDepth - 8)) : state.yuvMaxChannel;

    int cpCount = 1 << image.depth;
    if (state.mode == avifReformatMode.AVIF_REFORMAT_MODE_IDENTITY) {
        for (int cp = 0; cp < cpCount; ++cp) {
            state.unormFloatTableY[cp] = (cp - state.biasY) / state.rangeY;
            state.unormFloatTableUV[cp] = (cp - state.biasY) / state.rangeY;
        }
    } else {
        for (int cp = 0; cp < cpCount; ++cp) {
            // Review this when implementing YCgCo limited range support.
            state.unormFloatTableY[cp] = (cp - state.biasY) / state.rangeY;
            state.unormFloatTableUV[cp] = (cp - state.biasUV) / state.rangeUV;
        }
    }

    state.toRGBAlphaMode = avifAlphaMultiplyMode.AVIF_ALPHA_MULTIPLY_MODE_NO_OP;
    if (image.alphaPlane != null) {
        if (!rgb.format.avifRGBFormatHasAlpha() || rgb.ignoreAlpha) {
            // if we are converting some image with alpha into a format without alpha, we should do 'premultiply alpha' before
            // discarding alpha plane. This has the same effect of rendering this image on a black background, which makes sense.
            if (!image.alphaPremultiplied) {
                state.toRGBAlphaMode = avifAlphaMultiplyMode.AVIF_ALPHA_MULTIPLY_MODE_MULTIPLY;
            }
        } else {
            if (!image.alphaPremultiplied && rgb.alphaPremultiplied) {
                state.toRGBAlphaMode = avifAlphaMultiplyMode.AVIF_ALPHA_MULTIPLY_MODE_MULTIPLY;
            } else if (image.alphaPremultiplied && !rgb.alphaPremultiplied) {
                state.toRGBAlphaMode = avifAlphaMultiplyMode.AVIF_ALPHA_MULTIPLY_MODE_UNMULTIPLY;
            }
        }
    }

    return true;
}

// Formulas 20-31 from https://www.itu.int/rec/T-REC-H.273-201612-I/en
private int avifReformatStateYToUNorm(avifReformatState state, float v)
{
    int unorm = Math.round(v * state.rangeY + state.biasY);
    return avif.AVIF_CLAMP(unorm, 0, state.yuvMaxChannel);
}

private int avifReformatStateUVToUNorm(avifReformatState state, float v)
{
    int unorm;

    // YCgCo performs limited-full range adjustment on R,G,B but the current implementation performs range adjustment
    // on Y,U,V. So YCgCo with limited range is unsupported.
    assert((state.mode != avifReformatMode.AVIF_REFORMAT_MODE_YCGCO) || (state.yuvRange == avifRange.AVIF_RANGE_FULL));

    if (state.mode == avifReformatMode.AVIF_REFORMAT_MODE_IDENTITY) {
        unorm = Math.round(v * state.rangeY + state.biasY);
    } else {
        unorm = Math.round(v * state.rangeUV + state.biasUV);
    }

    return avif.AVIF_CLAMP(unorm, 0, state.yuvMaxChannel);
}

avifResult avifImageRGBToYUV(avifImage image, final avifRGBImage rgb)
{
    if (rgb.pixels != null) {
        return avifResult.AVIF_RESULT_REFORMAT_FAILED;
    }

    avifReformatState state = new avifReformatState();
    if (!avifPrepareReformatState(image, rgb, state)) {
        return avifResult.AVIF_RESULT_REFORMAT_FAILED;
    }

    if (rgb.isFloat) {
        return avifResult.AVIF_RESULT_NOT_IMPLEMENTED;
    }

    avifAlphaMultiplyMode alphaMode = avifAlphaMultiplyMode.AVIF_ALPHA_MULTIPLY_MODE_NO_OP;
    image.avifImageAllocatePlanes(EnumSet.of(avifPlanesFlag.AVIF_PLANES_YUV));
    if (rgb.format.avifRGBFormatHasAlpha() && !rgb.ignoreAlpha) {
        image.avifImageAllocatePlanes(EnumSet.of(avifPlanesFlag.AVIF_PLANES_A));
        if (!rgb.alphaPremultiplied && image.alphaPremultiplied) {
            alphaMode = avifAlphaMultiplyMode.AVIF_ALPHA_MULTIPLY_MODE_MULTIPLY;
        } else if (rgb.alphaPremultiplied && !image.alphaPremultiplied) {
            alphaMode = avifAlphaMultiplyMode.AVIF_ALPHA_MULTIPLY_MODE_UNMULTIPLY;
        }
    }

    final float kr = state.kr[0];
    final float kg = state.kg[0];
    final float kb = state.kb[0];

    YUVBlock[][] yuvBlock = new YUVBlock[2][2];
    float[] rgbPixel = new float[3];
    final float rgbMaxChannelF = state.rgbMaxChannelF;
    byte[][] yuvPlanes = image.yuvPlanes;
    int[] yuvRowBytes = image.yuvRowBytes;
    for (int outerJ = 0; outerJ < image.height; outerJ += 2) {
        for (int outerI = 0; outerI < image.width; outerI += 2) {
            int blockW = 2, blockH = 2;
            if ((outerI + 1) >= image.width) {
                blockW = 1;
            }
            if ((outerJ + 1) >= image.height) {
                blockH = 1;
            }

            // Convert an entire 2x2 block to YUV, and populate any fully sampled channels as we go
            for (int bJ = 0; bJ < blockH; ++bJ) {
                for (int bI = 0; bI < blockW; ++bI) {
                    int i = outerI + bI;
                    int j = outerJ + bJ;

                    // Unpack RGB into normalized float
                    if (state.rgbChannelBytes > 1) {
                        rgbPixel[0] = ByteUtil.readBeShort(rgb.pixels, state.rgbOffsetBytesR + (i * state.rgbPixelBytes) + (j * rgb.rowBytes)) / rgbMaxChannelF;
                        rgbPixel[1] = ByteUtil.readBeShort(rgb.pixels, state.rgbOffsetBytesG + (i * state.rgbPixelBytes) + (j * rgb.rowBytes)) / rgbMaxChannelF;
                        rgbPixel[2] = ByteUtil.readBeShort(rgb.pixels, state.rgbOffsetBytesB + (i * state.rgbPixelBytes) + (j * rgb.rowBytes)) / rgbMaxChannelF;
                    } else {
                        rgbPixel[0] = rgb.pixels[state.rgbOffsetBytesR + (i * state.rgbPixelBytes) + (j * rgb.rowBytes)] / rgbMaxChannelF;
                        rgbPixel[1] = rgb.pixels[state.rgbOffsetBytesG + (i * state.rgbPixelBytes) + (j * rgb.rowBytes)] / rgbMaxChannelF;
                        rgbPixel[2] = rgb.pixels[state.rgbOffsetBytesB + (i * state.rgbPixelBytes) + (j * rgb.rowBytes)] / rgbMaxChannelF;
                    }

                    if (alphaMode != avifAlphaMultiplyMode.AVIF_ALPHA_MULTIPLY_MODE_NO_OP) {
                        float a;
                        if (state.rgbChannelBytes > 1) {
                            a = ByteUtil.readBeShort(rgb.pixels, state.rgbOffsetBytesA + (i * state.rgbPixelBytes) + (j * rgb.rowBytes)) / rgbMaxChannelF;
                        } else {
                            a = rgb.pixels[state.rgbOffsetBytesA + (i * state.rgbPixelBytes) + (j * rgb.rowBytes)] / rgbMaxChannelF;
                        }

                        if (alphaMode == avifAlphaMultiplyMode.AVIF_ALPHA_MULTIPLY_MODE_MULTIPLY) {
                            if (a == 0) {
                                rgbPixel[0] = 0;
                                rgbPixel[1] = 0;
                                rgbPixel[2] = 0;
                            } else if (a < 1.0f) {
                                rgbPixel[0] *= a;
                                rgbPixel[1] *= a;
                                rgbPixel[2] *= a;
                            }
                        } else {
                            // alphaMode == AVIF_ALPHA_MULTIPLY_MODE_UNMULTIPLY
                            if (a == 0) {
                                rgbPixel[0] = 0;
                                rgbPixel[1] = 0;
                                rgbPixel[2] = 0;
                            } else if (a < 1.0f) {
                                rgbPixel[0] /= a;
                                rgbPixel[1] /= a;
                                rgbPixel[2] /= a;
                                rgbPixel[0] = Math.min(rgbPixel[0], 1.0f);
                                rgbPixel[1] = Math.min(rgbPixel[1], 1.0f);
                                rgbPixel[2] = Math.min(rgbPixel[2], 1.0f);
                            }
                        }
                    }

                    // RGB . YUV conversion
                    if (state.mode == avifReformatMode.AVIF_REFORMAT_MODE_IDENTITY) {
                        // Formulas 41,42,43 from https://www.itu.int/rec/T-REC-H.273-201612-I/en
                        yuvBlock[bI][bJ].y = rgbPixel[1]; // G
                        yuvBlock[bI][bJ].u = rgbPixel[2]; // B
                        yuvBlock[bI][bJ].v = rgbPixel[0]; // R
                    } else if (state.mode == avifReformatMode.AVIF_REFORMAT_MODE_YCGCO) {
                        // Formulas 44,45,46 from https://www.itu.int/rec/T-REC-H.273-201612-I/en
                        yuvBlock[bI][bJ].y = 0.5f * rgbPixel[1] + 0.25f * (rgbPixel[0] + rgbPixel[2]);
                        yuvBlock[bI][bJ].u = 0.5f * rgbPixel[1] - 0.25f * (rgbPixel[0] + rgbPixel[2]);
                        yuvBlock[bI][bJ].v = 0.5f * (rgbPixel[0] - rgbPixel[2]);
                    } else {
                        float Y = (kr * rgbPixel[0]) + (kg * rgbPixel[1]) + (kb * rgbPixel[2]);
                        yuvBlock[bI][bJ].y = Y;
                        yuvBlock[bI][bJ].u = (rgbPixel[2] - Y) / (2 * (1 - kb));
                        yuvBlock[bI][bJ].v = (rgbPixel[0] - Y) / (2 * (1 - kr));
                    }

                    if (state.yuvChannelBytes > 1) {
                        short pY = (short)avifReformatStateYToUNorm(state, yuvBlock[bI][bJ].y);
                        ByteUtil.writeBeShort(pY, yuvPlanes[avifChannelIndex.AVIF_CHAN_Y.v], (i * 2) + (j * yuvRowBytes[avifChannelIndex.AVIF_CHAN_Y.v]));
                        if (image.yuvFormat == avifPixelFormat.AVIF_PIXEL_FORMAT_YUV444) {
                            // YUV444, full chroma
                            short pU = (short) avifReformatStateUVToUNorm(state, yuvBlock[bI][bJ].u);
                            ByteUtil.writeBeShort(pU, yuvPlanes[avifChannelIndex.AVIF_CHAN_U.v], (i * 2) + (j * yuvRowBytes[avifChannelIndex.AVIF_CHAN_U.v]));
                            short pV = (short) avifReformatStateUVToUNorm(state, yuvBlock[bI][bJ].v);
                            ByteUtil.writeBeShort(pV, yuvPlanes[avifChannelIndex.AVIF_CHAN_V.v], (i * 2) + (j * yuvRowBytes[avifChannelIndex.AVIF_CHAN_V.v]));
                        }
                    } else {
                        yuvPlanes[avifChannelIndex.AVIF_CHAN_Y.v][i + (j * yuvRowBytes[avifChannelIndex.AVIF_CHAN_Y.v])] =
                            (byte) avifReformatStateYToUNorm(state, yuvBlock[bI][bJ].y);
                        if (image.yuvFormat == avifPixelFormat.AVIF_PIXEL_FORMAT_YUV444) {
                            // YUV444, full chroma
                            yuvPlanes[avifChannelIndex.AVIF_CHAN_U.v][i + (j * yuvRowBytes[avifChannelIndex.AVIF_CHAN_U.v])] =
                                (byte) avifReformatStateUVToUNorm(state, yuvBlock[bI][bJ].u);
                            yuvPlanes[avifChannelIndex.AVIF_CHAN_V.v][i + (j * yuvRowBytes[avifChannelIndex.AVIF_CHAN_V.v])] =
                                (byte) avifReformatStateUVToUNorm(state, yuvBlock[bI][bJ].v);
                        }
                    }
                }
            }

            // Populate any subsampled channels with averages from the 2x2 block
            if (image.yuvFormat == avifPixelFormat.AVIF_PIXEL_FORMAT_YUV420) {
                // YUV420, average 4 samples (2x2)

                float sumU = 0.0f;
                float sumV = 0.0f;
                for (int bJ = 0; bJ < blockH; ++bJ) {
                    for (int bI = 0; bI < blockW; ++bI) {
                        sumU += yuvBlock[bI][bJ].u;
                        sumV += yuvBlock[bI][bJ].v;
                    }
                }
                float totalSamples = blockW * blockH;
                float avgU = sumU / totalSamples;
                float avgV = sumV / totalSamples;

                final int chromaShiftX = 1;
                final int chromaShiftY = 1;
                int uvI = outerI >> chromaShiftX;
                int uvJ = outerJ >> chromaShiftY;
                if (state.yuvChannelBytes > 1) {
                    short pU = (short) avifReformatStateUVToUNorm(state, avgU);
                    ByteUtil.writeBeShort(pU, yuvPlanes[avifChannelIndex.AVIF_CHAN_U.v], (uvI * 2) + (uvJ * yuvRowBytes[avifChannelIndex.AVIF_CHAN_U.v]));
                    short pV = (short) avifReformatStateUVToUNorm(state, avgV);
                    ByteUtil.writeBeShort(pV, yuvPlanes[avifChannelIndex.AVIF_CHAN_V.v], (uvI * 2) + (uvJ * yuvRowBytes[avifChannelIndex.AVIF_CHAN_V.v]));
                } else {
                    yuvPlanes[avifChannelIndex.AVIF_CHAN_U.v][uvI + (uvJ * yuvRowBytes[avifChannelIndex.AVIF_CHAN_U.v])] = (byte) avifReformatStateUVToUNorm(state, avgU);
                    yuvPlanes[avifChannelIndex.AVIF_CHAN_V.v][uvI + (uvJ * yuvRowBytes[avifChannelIndex.AVIF_CHAN_V.v])] = (byte) avifReformatStateUVToUNorm(state, avgV);
                }
            } else if (image.yuvFormat == avifPixelFormat.AVIF_PIXEL_FORMAT_YUV422) {
                // YUV422, average 2 samples (1x2), twice

                for (int bJ = 0; bJ < blockH; ++bJ) {
                    float sumU = 0.0f;
                    float sumV = 0.0f;
                    for (int bI = 0; bI < blockW; ++bI) {
                        sumU += yuvBlock[bI][bJ].u;
                        sumV += yuvBlock[bI][bJ].v;
                    }
                    float totalSamples = blockW;
                    float avgU = sumU / totalSamples;
                    float avgV = sumV / totalSamples;

                    final int chromaShiftX = 1;
                    int uvI = outerI >> chromaShiftX;
                    int uvJ = outerJ + bJ;
                    if (state.yuvChannelBytes > 1) {
                        short pU = (short)avifReformatStateUVToUNorm(state, avgU);
                        ByteUtil.writeBeShort(pU, yuvPlanes[avifChannelIndex.AVIF_CHAN_U.v], (uvI * 2) + (uvJ * yuvRowBytes[avifChannelIndex.AVIF_CHAN_U.v]));
                        short pV = (short)avifReformatStateUVToUNorm(state, avgV);
                        ByteUtil.writeBeShort(pV, yuvPlanes[avifChannelIndex.AVIF_CHAN_V.v], (uvI * 2) + (uvJ * yuvRowBytes[avifChannelIndex.AVIF_CHAN_V.v]));
                    } else {
                        yuvPlanes[avifChannelIndex.AVIF_CHAN_U.v][uvI + (uvJ * yuvRowBytes[avifChannelIndex.AVIF_CHAN_U.v])] =
                            (byte)avifReformatStateUVToUNorm(state, avgU);
                        yuvPlanes[avifChannelIndex.AVIF_CHAN_V.v][uvI + (uvJ * yuvRowBytes[avifChannelIndex.AVIF_CHAN_V.v])] =
                            (byte)avifReformatStateUVToUNorm(state, avgV);
                    }
                }
            }
        }
    }

    if (image.alphaPlane != null && image.alphaRowBytes != 0) {
        avifAlphaParams params = new avifAlphaParams();

        params.width = image.width;
        params.height = image.height;
        params.dstDepth = image.depth;
        params.dstRange = image.alphaRange;
        params.dstPlane = image.alphaPlane;
        params.dstRowBytes = image.alphaRowBytes;
        params.dstOffsetBytes = 0;
        params.dstPixelBytes = state.yuvChannelBytes;

        if (rgb.format.avifRGBFormatHasAlpha() && !rgb.ignoreAlpha) {
            params.srcDepth = rgb.depth;
            params.srcRange = avifRange.AVIF_RANGE_FULL;
            params.srcPlane = rgb.pixels;
            params.srcRowBytes = rgb.rowBytes;
            params.srcOffsetBytes = state.rgbOffsetBytesA;
            params.srcPixelBytes = state.rgbPixelBytes;

            params.avifReformatAlpha();
        } else {
            params.avifFillAlpha();
        }
    }
    return avifResult.AVIF_RESULT_OK;
}

// Note: This function handles alpha (un)multiply.
private avifResult avifImageYUVAnyToRGBAnySlow(final avifImage image,
                                              avifRGBImage rgb,
                                              avifReformatState state,
                                              final avifChromaUpsampling chromaUpsampling)
{
    // Aliases for some state
    final float kr = state.kr[0];
    final float kg = state.kg[0];
    final float kb = state.kb[0];
    final float[] unormFloatTableY = state.unormFloatTableY;
    final float[] unormFloatTableUV = state.unormFloatTableUV;
    final int yuvChannelBytes = state.yuvChannelBytes;
    final int rgbPixelBytes = state.rgbPixelBytes;

    // Aliases for plane data
    final byte[] yPlane = image.yuvPlanes[avifChannelIndex.AVIF_CHAN_Y.v];
    final byte[] uPlane = image.yuvPlanes[avifChannelIndex.AVIF_CHAN_U.v];
    final byte[] vPlane = image.yuvPlanes[avifChannelIndex.AVIF_CHAN_V.v];
    final byte[] aPlane = image.alphaPlane;
    final int yRowBytes = image.yuvRowBytes[avifChannelIndex.AVIF_CHAN_Y.v];
    final int uRowBytes = image.yuvRowBytes[avifChannelIndex.AVIF_CHAN_U.v];
    final int vRowBytes = image.yuvRowBytes[avifChannelIndex.AVIF_CHAN_V.v];
    final int aRowBytes = image.alphaRowBytes;

    // Various observations and limits
    final boolean hasColor = (uPlane != null && vPlane != null && (image.yuvFormat != avifPixelFormat.AVIF_PIXEL_FORMAT_YUV400));
    final short yuvMaxChannel = (short)state.yuvMaxChannel;
    final float rgbMaxChannelF = state.rgbMaxChannelF;

    // These are the only supported built-ins
    assert((chromaUpsampling == avifChromaUpsampling.AVIF_CHROMA_UPSAMPLING_BILINEAR) || (chromaUpsampling == avifChromaUpsampling.AVIF_CHROMA_UPSAMPLING_NEAREST));

    // If toRGBAlphaMode is active (not no-op), assert that the alpha plane is present. The end of
    // the avifPrepareReformatState() function should ensure this, but this assert makes it clear
    // to clang's analyzer.
    assert state.toRGBAlphaMode == avifAlphaMultiplyMode.AVIF_ALPHA_MULTIPLY_MODE_NO_OP || aPlane != null;

    for (int j = 0; j < image.height; ++j) {
        final int uvJ = j >> state.formatInfo.chromaShiftY;
        final int ptrY8 = j * yRowBytes; // yPlane
        final int ptrU8 = uPlane != null ? uvJ * uRowBytes : -1; // uPlane
        final int ptrV8 = vPlane != null  ? uvJ * vRowBytes : -1; // vPlane
        final int ptrA8 = aPlane != null  ? j * aRowBytes : -1; // aPlane
        final int ptrY16 = ptrY8;
        final int ptrU16 = ptrU8;
        final int ptrV16 = ptrV8;
        final int ptrA16 = ptrA8;

        int ptrR = state.rgbOffsetBytesR + (j * rgb.rowBytes); // rgb.pixels
        int ptrG = state.rgbOffsetBytesG + (j * rgb.rowBytes); // rgb.pixels
        int ptrB = state.rgbOffsetBytesB + (j * rgb.rowBytes); // rgb.pixels

        for (int i = 0; i < image.width; ++i) {
            int uvI = i >> state.formatInfo.chromaShiftX;
            float Y, Cb = 0.5f, Cr = 0.5f;

            // Calculate Y
            short unormY;
            if (image.depth == 8) {
                unormY = yPlane[ptrY8 + i];
            } else {
                // clamp incoming data to protect against bad LUT lookups
                unormY = (short) Math.min(ByteUtil.readBeShort(yPlane, ptrY16 + i * Short.BYTES), yuvMaxChannel);
            }
            Y = unormFloatTableY[unormY];

            // Calculate Cb and Cr
            if (hasColor) {
                if (image.yuvFormat == avifPixelFormat.AVIF_PIXEL_FORMAT_YUV444) {
                    short unormU, unormV;

                    if (image.depth == 8) {
                        unormU = uPlane[ptrU8 + uvI];
                        unormV = vPlane[ptrV8 + uvI];
                    } else {
                        // clamp incoming data to protect against bad LUT lookups
                        unormU = (short) Math.min(ByteUtil.readBeShort(uPlane, ptrU16 + uvI * Short.BYTES), yuvMaxChannel);
                        unormV = (short) Math.min(ByteUtil.readBeShort(vPlane, ptrV16 + uvI * Short.BYTES), yuvMaxChannel);
                    }

                    Cb = unormFloatTableUV[unormU];
                    Cr = unormFloatTableUV[unormV];
                } else {
                    // Upsample to 444:
                    //
                    // *   *   *   *
                    //   A       B
                    // *   1   2   *
                    //
                    // *   3   4   *
                    //   C       D
                    // *   *   *   *
                    //
                    // When converting from YUV420 to RGB, for any given "high-resolution" RGB
                    // coordinate (1,2,3,4,*), there are up to four "low-resolution" UV samples
                    // (A,B,C,D) that are "nearest" to the pixel. For RGB pixel #1, A is the closest
                    // UV sample, B and C are "adjacent" to it on the same row and column, and D is
                    // the diagonal. For RGB pixel 3, C is the closest UV sample, A and D are
                    // adjacent, and B is the diagonal. Sometimes the adjacent pixel on the same row
                    // is to the left or right, and sometimes the adjacent pixel on the same column
                    // is up or down. For any edge or corner, there might only be only one or two
                    // samples nearby, so they'll be duplicated.
                    //
                    // The following code attempts to find all four nearest UV samples and put them
                    // in the following unormU and unormV grid as follows:
                    //
                    // unorm[0][0] = closest         ( weights: bilinear: 9/16, nearest: 1 )
                    // unorm[1][0] = adjacent col    ( weights: bilinear: 3/16, nearest: 0 )
                    // unorm[0][1] = adjacent row    ( weights: bilinear: 3/16, nearest: 0 )
                    // unorm[1][1] = diagonal        ( weights: bilinear: 1/16, nearest: 0 )
                    //
                    // It then weights them according to the requested upsampling set in avifRGBImage.

                    short[][] unormU = new short[2][2], unormV = new short[2][2];

                    // How many bytes to add to a byte[] pointer index to get to the adjacent (lesser) sample in a given direction
                    int uAdjCol, vAdjCol, uAdjRow, vAdjRow;
                    if ((i == 0) || ((i == (image.width - 1)) && ((i % 2) != 0))) {
                        uAdjCol = 0;
                        vAdjCol = 0;
                    } else {
                        if ((i % 2) != 0) {
                            uAdjCol = yuvChannelBytes;
                            vAdjCol = yuvChannelBytes;
                        } else {
                            uAdjCol = -1 * yuvChannelBytes;
                            vAdjCol = -1 * yuvChannelBytes;
                        }
                    }

                    // For YUV422, uvJ will always be a fresh value (always corresponds to j), so
                    // we'll simply duplicate the sample as if we were on the top or bottom row and
                    // it'll behave as plain old linear (1D) upsampling, which is all we want.
                    if ((j == 0) || ((j == (image.height - 1)) && ((j % 2) != 0)) || (image.yuvFormat == avifPixelFormat.AVIF_PIXEL_FORMAT_YUV422)) {
                        uAdjRow = 0;
                        vAdjRow = 0;
                    } else {
                        if ((j % 2) != 0) {
                            uAdjRow = uRowBytes;
                            vAdjRow = vRowBytes;
                        } else {
                            uAdjRow = -1 * uRowBytes;
                            vAdjRow = -1 * vRowBytes;
                        }
                    }

                    if (image.depth == 8) {
                        unormU[0][0] = uPlane[(uvJ * uRowBytes) + (uvI * yuvChannelBytes)];
                        unormV[0][0] = vPlane[(uvJ * vRowBytes) + (uvI * yuvChannelBytes)];
                        unormU[1][0] = uPlane[(uvJ * uRowBytes) + (uvI * yuvChannelBytes) + uAdjCol];
                        unormV[1][0] = vPlane[(uvJ * vRowBytes) + (uvI * yuvChannelBytes) + vAdjCol];
                        unormU[0][1] = uPlane[(uvJ * uRowBytes) + (uvI * yuvChannelBytes) + uAdjRow];
                        unormV[0][1] = vPlane[(uvJ * vRowBytes) + (uvI * yuvChannelBytes) + vAdjRow];
                        unormU[1][1] = uPlane[(uvJ * uRowBytes) + (uvI * yuvChannelBytes) + uAdjCol + uAdjRow];
                        unormV[1][1] = vPlane[(uvJ * vRowBytes) + (uvI * yuvChannelBytes) + vAdjCol + vAdjRow];
                    } else {
                        unormU[0][0] = ByteUtil.readBeShort(uPlane, ((uvJ * uRowBytes) + (uvI * yuvChannelBytes)) * Short.BYTES);
                        unormV[0][0] = ByteUtil.readBeShort(vPlane, ((uvJ * vRowBytes) + (uvI * yuvChannelBytes)) * Short.BYTES);
                        unormU[1][0] = ByteUtil.readBeShort(uPlane, ((uvJ * uRowBytes) + (uvI * yuvChannelBytes) + uAdjCol) * Short.BYTES);
                        unormV[1][0] = ByteUtil.readBeShort(vPlane, ((uvJ * vRowBytes) + (uvI * yuvChannelBytes) + vAdjCol) * Short.BYTES);
                        unormU[0][1] = ByteUtil.readBeShort(uPlane, ((uvJ * uRowBytes) + (uvI * yuvChannelBytes) + uAdjRow) * Short.BYTES);
                        unormV[0][1] = ByteUtil.readBeShort(vPlane, ((uvJ * vRowBytes) + (uvI * yuvChannelBytes) + vAdjRow) * Short.BYTES);
                        unormU[1][1] = ByteUtil.readBeShort(uPlane, ((uvJ * uRowBytes) + (uvI * yuvChannelBytes) + uAdjCol + uAdjRow) * Short.BYTES);
                        unormV[1][1] = ByteUtil.readBeShort(vPlane, ((uvJ * vRowBytes) + (uvI * yuvChannelBytes) + vAdjCol + vAdjRow) * Short.BYTES);

                        // clamp incoming data to protect against bad LUT lookups
                        for (int bJ = 0; bJ < 2; ++bJ) {
                            for (int bI = 0; bI < 2; ++bI) {
                                unormU[bI][bJ] = (short) Math.min(unormU[bI][bJ], yuvMaxChannel);
                                unormV[bI][bJ] = (short) Math.min(unormV[bI][bJ], yuvMaxChannel);
                            }
                        }
                    }

                    if (chromaUpsampling == avifChromaUpsampling.AVIF_CHROMA_UPSAMPLING_BILINEAR) {
                        // Bilinear filtering with weights
                        Cb = (unormFloatTableUV[unormU[0][0]] * (9.0f / 16.0f)) + (unormFloatTableUV[unormU[1][0]] * (3.0f / 16.0f)) +
                             (unormFloatTableUV[unormU[0][1]] * (3.0f / 16.0f)) + (unormFloatTableUV[unormU[1][1]] * (1.0f / 16.0f));
                        Cr = (unormFloatTableUV[unormV[0][0]] * (9.0f / 16.0f)) + (unormFloatTableUV[unormV[1][0]] * (3.0f / 16.0f)) +
                             (unormFloatTableUV[unormV[0][1]] * (3.0f / 16.0f)) + (unormFloatTableUV[unormV[1][1]] * (1.0f / 16.0f));
                    } else {
                        assert(chromaUpsampling == avifChromaUpsampling.AVIF_CHROMA_UPSAMPLING_NEAREST);

                        // Nearest neighbor; ignore all UVs but the closest one
                        Cb = unormFloatTableUV[unormU[0][0]];
                        Cr = unormFloatTableUV[unormV[0][0]];
                    }
                }
            }

            float R, G, B;
            if (hasColor) {
                if (state.mode == avifReformatMode.AVIF_REFORMAT_MODE_IDENTITY) {
                    // Identity (GBR): Formulas 41,42,43 from https://www.itu.int/rec/T-REC-H.273-201612-I/en
                    G = Y;
                    B = Cb;
                    R = Cr;
                } else if (state.mode == avifReformatMode.AVIF_REFORMAT_MODE_YCGCO) {
                    // YCgCo: Formulas 47,48,49,50 from https://www.itu.int/rec/T-REC-H.273-201612-I/en
                    final float t = Y - Cb;
                    G = Y + Cb;
                    B = t - Cr;
                    R = t + Cr;
                } else {
                    // Normal YUV
                    R = Y + (2 * (1 - kr)) * Cr;
                    B = Y + (2 * (1 - kb)) * Cb;
                    G = Y - ((2 * ((kr * (1 - kr) * Cr) + (kb * (1 - kb) * Cb))) / kg);
                }
            } else {
                // Monochrome: just populate all channels with luma (identity mode is irrelevant)
                R = Y;
                G = Y;
                B = Y;
            }

            float Rc = avif.AVIF_CLAMP(R, 0.0f, 1.0f);
            float Gc = avif.AVIF_CLAMP(G, 0.0f, 1.0f);
            float Bc = avif.AVIF_CLAMP(B, 0.0f, 1.0f);

            if (state.toRGBAlphaMode != avifAlphaMultiplyMode.AVIF_ALPHA_MULTIPLY_MODE_NO_OP) {
                // Calculate A
                short unormA;
                if (image.depth == 8) {
                    unormA = aPlane[ptrA8 + i];
                } else {
                    unormA = (short) Math.min(ByteUtil.readBeShort(aPlane, ptrA16 + i * Short.BYTES), yuvMaxChannel);
                }
                final float A = (unormA - state.biasA) / state.rangeA;
                final float Ac = avif.AVIF_CLAMP(A, 0.0f, 1.0f);

                if (state.toRGBAlphaMode == avifAlphaMultiplyMode.AVIF_ALPHA_MULTIPLY_MODE_MULTIPLY) {
                    if (Ac == 0.0f) {
                        Rc = 0.0f;
                        Gc = 0.0f;
                        Bc = 0.0f;
                    } else if (Ac < 1.0f) {
                        Rc *= Ac;
                        Gc *= Ac;
                        Bc *= Ac;
                    }
                } else {
                    // state.toRGBAlphaMode == AVIF_ALPHA_MULTIPLY_MODE_UNMULTIPLY
                    if (Ac == 0.0f) {
                        Rc = 0.0f;
                        Gc = 0.0f;
                        Bc = 0.0f;
                    } else if (Ac < 1.0f) {
                        Rc /= Ac;
                        Gc /= Ac;
                        Bc /= Ac;
                        Rc = Math.min(Rc, 1.0f);
                        Gc = Math.min(Gc, 1.0f);
                        Bc = Math.min(Bc, 1.0f);
                    }
                }
            }

            if (rgb.depth == 8) {
                rgb.pixels[ptrR] = (byte) (0.5f + (Rc * rgbMaxChannelF));
                rgb.pixels[ptrG] = (byte) (0.5f + (Gc * rgbMaxChannelF));
                rgb.pixels[ptrB] = (byte) (0.5f + (Bc * rgbMaxChannelF));
                ptrR += rgbPixelBytes;
                ptrG += rgbPixelBytes;
                ptrB += rgbPixelBytes;
            } else {
                ByteUtil.writeBeShort((short) (0.5f + (Rc * rgbMaxChannelF)), rgb.pixels, ptrR);
                ByteUtil.writeBeShort((short) (0.5f + (Gc * rgbMaxChannelF)), rgb.pixels, ptrG);
                ByteUtil.writeBeShort((short) (0.5f + (Bc * rgbMaxChannelF)), rgb.pixels, ptrB);
                ptrR += rgbPixelBytes * Short.BYTES;
                ptrG += rgbPixelBytes * Short.BYTES;
                ptrB += rgbPixelBytes * Short.BYTES;
            }
        }
    }
    return avifResult.AVIF_RESULT_OK;
}

private avifResult avifImageYUV16ToRGB16Color(final avifImage image, avifRGBImage rgb, avifReformatState state)
{
    final float kr = state.kr[0];
    final float kg = state.kg[0];
    final float kb = state.kb[0];
    final int rgbPixelBytes = state.rgbPixelBytes;
    final float[] unormFloatTableY = state.unormFloatTableY;
    final float[] unormFloatTableUV = state.unormFloatTableUV;

    final short yuvMaxChannel = (short)state.yuvMaxChannel;
    final float rgbMaxChannelF = state.rgbMaxChannelF;
    for (int j = 0; j < image.height; ++j) {
        final int uvJ = j >> state.formatInfo.chromaShiftY;
        final int ptrY = (j * image.yuvRowBytes[avifChannelIndex.AVIF_CHAN_Y.v]); // image.yuvPlanes[avifChannelIndex.AVIF_CHAN_Y.v]
        final int ptrU = (uvJ * image.yuvRowBytes[avifChannelIndex.AVIF_CHAN_U.v]); // image.yuvPlanes[avifChannelIndex.AVIF_CHAN_U.v]
        final int ptrV = (uvJ * image.yuvRowBytes[avifChannelIndex.AVIF_CHAN_V.v]); // image.yuvPlanes[avifChannelIndex.AVIF_CHAN_V.v]
        int ptrR = state.rgbOffsetBytesR + (j * rgb.rowBytes); // rgb.pixels
        int ptrG = state.rgbOffsetBytesG + (j * rgb.rowBytes);
        int ptrB = state.rgbOffsetBytesB + (j * rgb.rowBytes);

        for (int i = 0; i < image.width; ++i) {
            int uvI = i >> state.formatInfo.chromaShiftX;

            // clamp incoming data to protect against bad LUT lookups
            final short unormY = (short) Math.min(image.yuvPlanes[avifChannelIndex.AVIF_CHAN_Y.v][ptrY + i], yuvMaxChannel);
            final short unormU = (short) Math.min(image.yuvPlanes[avifChannelIndex.AVIF_CHAN_U.v][ptrU + uvI], yuvMaxChannel);
            final short unormV = (short) Math.min(image.yuvPlanes[avifChannelIndex.AVIF_CHAN_V.v][ptrV + uvI], yuvMaxChannel);

            // Convert unorm to float
            final float Y = unormFloatTableY[unormY];
            final float Cb = unormFloatTableUV[unormU];
            final float Cr = unormFloatTableUV[unormV];

            final float R = Y + (2 * (1 - kr)) * Cr;
            final float B = Y + (2 * (1 - kb)) * Cb;
            final float G = Y - ((2 * ((kr * (1 - kr) * Cr) + (kb * (1 - kb) * Cb))) / kg);
            final float Rc = avif.AVIF_CLAMP(R, 0.0f, 1.0f);
            final float Gc = avif.AVIF_CLAMP(G, 0.0f, 1.0f);
            final float Bc = avif.AVIF_CLAMP(B, 0.0f, 1.0f);

            ByteUtil.writeBeShort((short) (0.5f + (Rc * rgbMaxChannelF)), rgb.pixels, ptrR);
            ByteUtil.writeBeShort((short) (0.5f + (Gc * rgbMaxChannelF)), rgb.pixels, ptrG);
            ByteUtil.writeBeShort((short) (0.5f + (Bc * rgbMaxChannelF)), rgb.pixels, ptrB);

            ptrR += rgbPixelBytes * Short.BYTES;
            ptrG += rgbPixelBytes * Short.BYTES;
            ptrB += rgbPixelBytes * Short.BYTES;
        }
    }
    return avifResult.AVIF_RESULT_OK;
}

private avifResult avifImageYUV16ToRGB16Mono(final avifImage image, avifRGBImage rgb, avifReformatState state)
{
    final float kr = state.kr[0];
    final float kg = state.kg[0];
    final float kb = state.kb[0];
    final int rgbPixelBytes = state.rgbPixelBytes;
    final float[] unormFloatTableY = state.unormFloatTableY;

    final short yuvMaxChannel = (short)state.yuvMaxChannel;
    final float rgbMaxChannelF = state.rgbMaxChannelF;
    for (int j = 0; j < image.height; ++j) {
        final int ptrY = (j * image.yuvRowBytes[avifChannelIndex.AVIF_CHAN_Y.v]); // image.yuvPlanes[avifChannelIndex.AVIF_CHAN_Y.v]
        int ptrR = state.rgbOffsetBytesR + (j * rgb.rowBytes); // rgb.pixels
        int ptrG = state.rgbOffsetBytesG + (j * rgb.rowBytes);
        int ptrB = state.rgbOffsetBytesB + (j * rgb.rowBytes);

        for (int i = 0; i < image.width; ++i) {
            // clamp incoming data to protect against bad LUT lookups
            final short unormY = (short) Math.min(image.yuvPlanes[avifChannelIndex.AVIF_CHAN_Y.v][ptrY + i], yuvMaxChannel);

            // Convert unorm to float
            final float Y = unormFloatTableY[unormY];
            final float Cb = 0.0f;
            final float Cr = 0.0f;

            final float R = Y + (2 * (1 - kr)) * Cr;
            final float B = Y + (2 * (1 - kb)) * Cb;
            final float G = Y - ((2 * ((kr * (1 - kr) * Cr) + (kb * (1 - kb) * Cb))) / kg);
            final float Rc = avif.AVIF_CLAMP(R, 0.0f, 1.0f);
            final float Gc = avif.AVIF_CLAMP(G, 0.0f, 1.0f);
            final float Bc = avif.AVIF_CLAMP(B, 0.0f, 1.0f);

            ByteUtil.writeBeShort((short)(0.5f + (Rc * rgbMaxChannelF)), rgb.pixels, ptrR);
            ByteUtil.writeBeShort((short)(0.5f + (Gc * rgbMaxChannelF)), rgb.pixels, ptrG);
            ByteUtil.writeBeShort((short)(0.5f + (Bc * rgbMaxChannelF)), rgb.pixels, ptrB);

            ptrR += rgbPixelBytes * Short.BYTES;
            ptrG += rgbPixelBytes * Short.BYTES;
            ptrB += rgbPixelBytes * Short.BYTES;
        }
    }
    return avifResult.AVIF_RESULT_OK;
}

private avifResult avifImageYUV16ToRGB8Color(final avifImage image, avifRGBImage rgb, avifReformatState state)
{
    final float kr = state.kr[0];
    final float kg = state.kg[0];
    final float kb = state.kb[0];
    final int rgbPixelBytes = state.rgbPixelBytes;
    final float[] unormFloatTableY = state.unormFloatTableY;
    final float[] unormFloatTableUV = state.unormFloatTableUV;

    final short yuvMaxChannel = (short)state.yuvMaxChannel;
    final float rgbMaxChannelF = state.rgbMaxChannelF;
    for (int j = 0; j < image.height; ++j) {
        final int uvJ = j >> state.formatInfo.chromaShiftY;
        final int ptrY = (j * image.yuvRowBytes[avifChannelIndex.AVIF_CHAN_Y.v]); // image.yuvPlanes[avifChannelIndex.AVIF_CHAN_Y.v]
        final int ptrU = (uvJ * image.yuvRowBytes[avifChannelIndex.AVIF_CHAN_U.v]); // image.yuvPlanes[avifChannelIndex.AVIF_CHAN_U.v]
        final int ptrV = (uvJ * image.yuvRowBytes[avifChannelIndex.AVIF_CHAN_V.v]); // image.yuvPlanes[avifChannelIndex.AVIF_CHAN_V.v]
        int ptrR = state.rgbOffsetBytesR + (j * rgb.rowBytes); // rgb.pixels
        int ptrG = state.rgbOffsetBytesG + (j * rgb.rowBytes);
        int ptrB = state.rgbOffsetBytesB + (j * rgb.rowBytes);

        for (int i = 0; i < image.width; ++i) {
            int uvI = i >> state.formatInfo.chromaShiftX;

            // clamp incoming data to protect against bad LUT lookups
            final short unormY = (short) Math.min(image.yuvPlanes[avifChannelIndex.AVIF_CHAN_Y.v][ptrY + i], yuvMaxChannel);
            final short unormU = (short) Math.min(image.yuvPlanes[avifChannelIndex.AVIF_CHAN_U.v][ptrU + uvI], yuvMaxChannel);
            final short unormV = (short) Math.min(image.yuvPlanes[avifChannelIndex.AVIF_CHAN_V.v][ptrV + uvI], yuvMaxChannel);

            // Convert unorm to float
            final float Y = unormFloatTableY[unormY];
            final float Cb = unormFloatTableUV[unormU];
            final float Cr = unormFloatTableUV[unormV];

            final float R = Y + (2 * (1 - kr)) * Cr;
            final float B = Y + (2 * (1 - kb)) * Cb;
            final float G = Y - ((2 * ((kr * (1 - kr) * Cr) + (kb * (1 - kb) * Cb))) / kg);
            final float Rc = avif.AVIF_CLAMP(R, 0.0f, 1.0f);
            final float Gc = avif.AVIF_CLAMP(G, 0.0f, 1.0f);
            final float Bc = avif.AVIF_CLAMP(B, 0.0f, 1.0f);

            rgb.pixels[ptrR] = (byte)(0.5f + (Rc * rgbMaxChannelF));
            rgb.pixels[ptrG] = (byte)(0.5f + (Gc * rgbMaxChannelF));
            rgb.pixels[ptrB] = (byte)(0.5f + (Bc * rgbMaxChannelF));

            ptrR += rgbPixelBytes;
            ptrG += rgbPixelBytes;
            ptrB += rgbPixelBytes;
        }
    }
    return avifResult.AVIF_RESULT_OK;
}

private avifResult avifImageYUV16ToRGB8Mono(final avifImage image, avifRGBImage rgb, avifReformatState state)
{
    final float kr = state.kr[0];
    final float kg = state.kg[0];
    final float kb = state.kb[0];
    final int rgbPixelBytes = state.rgbPixelBytes;
    final float[] unormFloatTableY = state.unormFloatTableY;

    final short yuvMaxChannel = (short)state.yuvMaxChannel;
    final float rgbMaxChannelF = state.rgbMaxChannelF;
    for (int j = 0; j < image.height; ++j) {
        final int ptrY = (j * image.yuvRowBytes[avifChannelIndex.AVIF_CHAN_Y.v]); // image.yuvPlanes[avifChannelIndex.AVIF_CHAN_Y.v]
        int ptrR = state.rgbOffsetBytesR + (j * rgb.rowBytes); // rgb.pixels
        int ptrG = state.rgbOffsetBytesG + (j * rgb.rowBytes);
        int ptrB = state.rgbOffsetBytesB + (j * rgb.rowBytes);

        for (int i = 0; i < image.width; ++i) {
            // clamp incoming data to protect against bad LUT lookups
            final short unormY = (short) Math.min(image.yuvPlanes[avifChannelIndex.AVIF_CHAN_Y.v][ptrY + i], yuvMaxChannel);

            // Convert unorm to float
            final float Y = unormFloatTableY[unormY];
            final float Cb = 0.0f;
            final float Cr = 0.0f;

            final float R = Y + (2 * (1 - kr)) * Cr;
            final float B = Y + (2 * (1 - kb)) * Cb;
            final float G = Y - ((2 * ((kr * (1 - kr) * Cr) + (kb * (1 - kb) * Cb))) / kg);
            final float Rc = avif.AVIF_CLAMP(R, 0.0f, 1.0f);
            final float Gc = avif.AVIF_CLAMP(G, 0.0f, 1.0f);
            final float Bc = avif.AVIF_CLAMP(B, 0.0f, 1.0f);

            rgb.pixels[ptrR] = (byte)(0.5f + (Rc * rgbMaxChannelF));
            rgb.pixels[ptrG] = (byte)(0.5f + (Gc * rgbMaxChannelF));
            rgb.pixels[ptrB] = (byte)(0.5f + (Bc * rgbMaxChannelF));

            ptrR += rgbPixelBytes;
            ptrG += rgbPixelBytes;
            ptrB += rgbPixelBytes;
        }
    }
    return avifResult.AVIF_RESULT_OK;
}

private avifResult avifImageYUV8ToRGB16Color(final avifImage image, avifRGBImage rgb, avifReformatState state)
{
    final float kr = state.kr[0];
    final float kg = state.kg[0];
    final float kb = state.kb[0];
    final int rgbPixelBytes = state.rgbPixelBytes;
    final float[] unormFloatTableY = state.unormFloatTableY;
    final float[] unormFloatTableUV = state.unormFloatTableUV;

    final float rgbMaxChannelF = state.rgbMaxChannelF;
    for (int j = 0; j < image.height; ++j) {
        final int uvJ = j >> state.formatInfo.chromaShiftY;
        final int ptrY = (j * image.yuvRowBytes[avifChannelIndex.AVIF_CHAN_Y.v]); // image.yuvPlanes[avifChannelIndex.AVIF_CHAN_Y.v]
        final int ptrU = (uvJ * image.yuvRowBytes[avifChannelIndex.AVIF_CHAN_U.v]); // image.yuvPlanes[avifChannelIndex.AVIF_CHAN_U.v]
        final int ptrV = (uvJ * image.yuvRowBytes[avifChannelIndex.AVIF_CHAN_V.v]); // image.yuvPlanes[avifChannelIndex.AVIF_CHAN_V.v]
        int ptrR = state.rgbOffsetBytesR + (j * rgb.rowBytes); // rgb.pixels
        int ptrG = state.rgbOffsetBytesG + (j * rgb.rowBytes);
        int ptrB = state.rgbOffsetBytesB + (j * rgb.rowBytes);

        for (int i = 0; i < image.width; ++i) {
            int uvI = i >> state.formatInfo.chromaShiftX;

            // Convert unorm to float (no clamp necessary, the full byte[] range is a legal lookup)
            final float Y = unormFloatTableY[image.yuvPlanes[avifChannelIndex.AVIF_CHAN_Y.v][ptrY + i]];
            final float Cb = unormFloatTableUV[image.yuvPlanes[avifChannelIndex.AVIF_CHAN_U.v][ptrU + uvI]];
            final float Cr = unormFloatTableUV[image.yuvPlanes[avifChannelIndex.AVIF_CHAN_V.v][ptrV + uvI]];

            final float R = Y + (2 * (1 - kr)) * Cr;
            final float B = Y + (2 * (1 - kb)) * Cb;
            final float G = Y - ((2 * ((kr * (1 - kr) * Cr) + (kb * (1 - kb) * Cb))) / kg);
            final float Rc = avif.AVIF_CLAMP(R, 0.0f, 1.0f);
            final float Gc = avif.AVIF_CLAMP(G, 0.0f, 1.0f);
            final float Bc = avif.AVIF_CLAMP(B, 0.0f, 1.0f);

            ByteUtil.writeBeShort((short)(0.5f + (Rc * rgbMaxChannelF)), rgb.pixels, ptrR);
            ByteUtil.writeBeShort((short)(0.5f + (Gc * rgbMaxChannelF)), rgb.pixels, ptrG);
            ByteUtil.writeBeShort((short)(0.5f + (Bc * rgbMaxChannelF)), rgb.pixels, ptrB);

            ptrR += rgbPixelBytes * Short.BYTES;
            ptrG += rgbPixelBytes * Short.BYTES;
            ptrB += rgbPixelBytes * Short.BYTES;
        }
    }
    return avifResult.AVIF_RESULT_OK;
}

private avifResult avifImageYUV8ToRGB16Mono(final avifImage image, avifRGBImage rgb, avifReformatState state)
{
    final float kr = state.kr[0];
    final float kg = state.kg[0];
    final float kb = state.kb[0];
    final int rgbPixelBytes = state.rgbPixelBytes;
    final float[] unormFloatTableY = state.unormFloatTableY;

    final float rgbMaxChannelF = state.rgbMaxChannelF;
    for (int j = 0; j < image.height; ++j) {
        final int ptrY = (j * image.yuvRowBytes[avifChannelIndex.AVIF_CHAN_Y.v]); // image.yuvPlanes[avifChannelIndex.AVIF_CHAN_Y.v]
        int ptrR = state.rgbOffsetBytesR + (j * rgb.rowBytes); // rgb.pixels
        int ptrG = state.rgbOffsetBytesG + (j * rgb.rowBytes);
        int ptrB = state.rgbOffsetBytesB + (j * rgb.rowBytes);

        for (int i = 0; i < image.width; ++i) {
            // Convert unorm to float (no clamp necessary, the full byte[] range is a legal lookup)
            final float Y = unormFloatTableY[image.yuvPlanes[avifChannelIndex.AVIF_CHAN_Y.v][ptrY + i]];
            final float Cb = 0.0f;
            final float Cr = 0.0f;

            final float R = Y + (2 * (1 - kr)) * Cr;
            final float B = Y + (2 * (1 - kb)) * Cb;
            final float G = Y - ((2 * ((kr * (1 - kr) * Cr) + (kb * (1 - kb) * Cb))) / kg);
            final float Rc = avif.AVIF_CLAMP(R, 0.0f, 1.0f);
            final float Gc = avif.AVIF_CLAMP(G, 0.0f, 1.0f);
            final float Bc = avif.AVIF_CLAMP(B, 0.0f, 1.0f);

            ByteUtil.writeBeShort((short)(0.5f + (Rc * rgbMaxChannelF)), rgb.pixels, ptrR);
            ByteUtil.writeBeShort((short)(0.5f + (Gc * rgbMaxChannelF)), rgb.pixels, ptrG);
            ByteUtil.writeBeShort((short)(0.5f + (Bc * rgbMaxChannelF)), rgb.pixels, ptrB);

            ptrR += rgbPixelBytes * Short.BYTES;
            ptrG += rgbPixelBytes * Short.BYTES;
            ptrB += rgbPixelBytes * Short.BYTES;
        }
    }
    return avifResult.AVIF_RESULT_OK;
}

private avifResult avifImageIdentity8ToRGB8ColorFullRange(final avifImage image, avifRGBImage rgb, avifReformatState state)
{
    final int rgbPixelBytes = state.rgbPixelBytes;
    for (int j = 0; j < image.height; ++j) {
        final int ptrY = (j * image.yuvRowBytes[avifChannelIndex.AVIF_CHAN_Y.v]); // image.yuvPlanes[avifChannelIndex.AVIF_CHAN_Y.v]
        final int ptrU = (j * image.yuvRowBytes[avifChannelIndex.AVIF_CHAN_U.v]); // image.yuvPlanes[avifChannelIndex.AVIF_CHAN_U.v]
        final int ptrV = (j * image.yuvRowBytes[avifChannelIndex.AVIF_CHAN_V.v]); // image.yuvPlanes[avifChannelIndex.AVIF_CHAN_V.v]
        int ptrR = state.rgbOffsetBytesR + (j * rgb.rowBytes); // rgb.pixels
        int ptrG = state.rgbOffsetBytesG + (j * rgb.rowBytes);
        int ptrB = state.rgbOffsetBytesB + (j * rgb.rowBytes);

        for (int i = 0; i < image.width; ++i) {
            rgb.pixels[ptrR] = image.yuvPlanes[avifChannelIndex.AVIF_CHAN_V.v][ptrV + i];
            rgb.pixels[ptrG] = image.yuvPlanes[avifChannelIndex.AVIF_CHAN_Y.v][ptrY + i];
            rgb.pixels[ptrB] = image.yuvPlanes[avifChannelIndex.AVIF_CHAN_U.v][ptrU + i];

            ptrR += rgbPixelBytes;
            ptrG += rgbPixelBytes;
            ptrB += rgbPixelBytes;
        }
    }
    return avifResult.AVIF_RESULT_OK;
}

private avifResult avifImageYUV8ToRGB8Color(final avifImage image, avifRGBImage rgb, avifReformatState state)
{
    final float kr = state.kr[0];
    final float kg = state.kg[0];
    final float kb = state.kb[0];
    final int rgbPixelBytes = state.rgbPixelBytes;
    final float[] unormFloatTableY = state.unormFloatTableY;
    final float[] unormFloatTableUV = state.unormFloatTableUV;

    final float rgbMaxChannelF = state.rgbMaxChannelF;
    for (int j = 0; j < image.height; ++j) {
        final int uvJ = j >> state.formatInfo.chromaShiftY;
        final int ptrY = (j * image.yuvRowBytes[avifChannelIndex.AVIF_CHAN_Y.v]); // image.yuvPlanes[avifChannelIndex.AVIF_CHAN_Y.v]
        final int ptrU = (uvJ * image.yuvRowBytes[avifChannelIndex.AVIF_CHAN_U.v]); // image.yuvPlanes[avifChannelIndex.AVIF_CHAN_U.v]
        final int ptrV = (uvJ * image.yuvRowBytes[avifChannelIndex.AVIF_CHAN_V.v]); // image.yuvPlanes[avifChannelIndex.AVIF_CHAN_V.v]
        int ptrR = state.rgbOffsetBytesR + (j * rgb.rowBytes); // rgb.pixels
        int ptrG = state.rgbOffsetBytesG + (j * rgb.rowBytes);
        int ptrB = state.rgbOffsetBytesB + (j * rgb.rowBytes);

        for (int i = 0; i < image.width; ++i) {
            int uvI = i >> state.formatInfo.chromaShiftX;

            // Convert unorm to float (no clamp necessary, the full byte[] range is a legal lookup)
            final float Y = unormFloatTableY[image.yuvPlanes[avifChannelIndex.AVIF_CHAN_Y.v][ptrY + i]];
            final float Cb = unormFloatTableUV[image.yuvPlanes[avifChannelIndex.AVIF_CHAN_U.v][ptrU + uvI]];
            final float Cr = unormFloatTableUV[image.yuvPlanes[avifChannelIndex.AVIF_CHAN_V.v][ptrV + uvI]];

            final float R = Y + (2 * (1 - kr)) * Cr;
            final float B = Y + (2 * (1 - kb)) * Cb;
            final float G = Y - ((2 * ((kr * (1 - kr) * Cr) + (kb * (1 - kb) * Cb))) / kg);
            final float Rc = avif.AVIF_CLAMP(R, 0.0f, 1.0f);
            final float Gc = avif.AVIF_CLAMP(G, 0.0f, 1.0f);
            final float Bc = avif.AVIF_CLAMP(B, 0.0f, 1.0f);

            rgb.pixels[ptrR] = (byte)(0.5f + (Rc * rgbMaxChannelF));
            rgb.pixels[ptrG] = (byte)(0.5f + (Gc * rgbMaxChannelF));
            rgb.pixels[ptrB] = (byte)(0.5f + (Bc * rgbMaxChannelF));

            ptrR += rgbPixelBytes;
            ptrG += rgbPixelBytes;
            ptrB += rgbPixelBytes;
        }
    }
    return avifResult.AVIF_RESULT_OK;
}

private avifResult avifImageYUV8ToRGB8Mono(final avifImage image, avifRGBImage rgb, avifReformatState state)
{
    final float kr = state.kr[0];
    final float kg = state.kg[0];
    final float kb = state.kb[0];
    final int rgbPixelBytes = state.rgbPixelBytes;
    final float[] unormFloatTableY = state.unormFloatTableY;

    final float rgbMaxChannelF = state.rgbMaxChannelF;
    for (int j = 0; j < image.height; ++j) {
        final int ptrY = (j * image.yuvRowBytes[avifChannelIndex.AVIF_CHAN_Y.v]); // image.yuvPlanes[avifChannelIndex.AVIF_CHAN_Y.v]
        int ptrR = state.rgbOffsetBytesR + (j * rgb.rowBytes); // rgb.pixels
        int ptrG = state.rgbOffsetBytesG + (j * rgb.rowBytes);
        int ptrB = state.rgbOffsetBytesB + (j * rgb.rowBytes);

        for (int i = 0; i < image.width; ++i) {
            // Convert unorm to float (no clamp necessary, the full byte[] range is a legal lookup)
            final float Y = unormFloatTableY[image.yuvPlanes[avifChannelIndex.AVIF_CHAN_Y.v][ptrY + i]];
            final float Cb = 0.0f;
            final float Cr = 0.0f;

            final float R = Y + (2 * (1 - kr)) * Cr;
            final float B = Y + (2 * (1 - kb)) * Cb;
            final float G = Y - ((2 * ((kr * (1 - kr) * Cr) + (kb * (1 - kb) * Cb))) / kg);
            final float Rc = avif.AVIF_CLAMP(R, 0.0f, 1.0f);
            final float Gc = avif.AVIF_CLAMP(G, 0.0f, 1.0f);
            final float Bc = avif.AVIF_CLAMP(B, 0.0f, 1.0f);

            rgb.pixels[ptrR] = (byte)(0.5f + (Rc * rgbMaxChannelF));
            rgb.pixels[ptrG] = (byte)(0.5f + (Gc * rgbMaxChannelF));
            rgb.pixels[ptrB] = (byte)(0.5f + (Bc * rgbMaxChannelF));

            ptrR += rgbPixelBytes;
            ptrG += rgbPixelBytes;
            ptrB += rgbPixelBytes;
        }
    }
    return avifResult.AVIF_RESULT_OK;
}

private avifResult avifRGBImageToF16(avifRGBImage rgb)
{
    avifResult libyuvResult = reformat_libyuv.avifRGBImageToF16LibYUV(rgb);
    if (libyuvResult != avifResult.AVIF_RESULT_NOT_IMPLEMENTED) {
        return libyuvResult;
    }
    final int channelCount = rgb.format.avifRGBFormatChannelCount();
    final float scale = 1.0f / ((1 << rgb.depth) - 1);
    // This constant comes from libyuv. For details, see here:
    // https://chromium.googlesource.com/libyuv/libyuv/+/2f87e9a7/source/row_common.cc#3537
    final float multiplier = 1.9259299444e-34f * scale;
    int pixelRowBase = 0; // rgb.pixels
    final int stride = rgb.rowBytes >> 1;
    for (int j = 0; j < rgb.height; ++j) {
        int pixel = pixelRowBase;
        for (int i = 0; i < rgb.width * channelCount; ++i, ++pixel) {
            float f16 = ByteUtil.readBeShort(rgb.pixels, pixel * Short.BYTES) * multiplier;
            pixel = (short)(Float.floatToIntBits(f16) >> 13);
        }
        pixelRowBase += stride * Short.BYTES;
    }
    return avifResult.AVIF_RESULT_OK;
}

avifResult avifImageYUVToRGB(final avifImage image, avifRGBImage rgb)
{
    if (image.yuvPlanes[avifChannelIndex.AVIF_CHAN_Y.v] != null) {
        return avifResult.AVIF_RESULT_REFORMAT_FAILED;
    }

    avifReformatState state = new avifReformatState();
    if (!avifPrepareReformatState(image, rgb, state)) {
        return avifResult.AVIF_RESULT_REFORMAT_FAILED;
    }

    avifAlphaMultiplyMode alphaMultiplyMode = state.toRGBAlphaMode;
    boolean convertedWithLibYUV = false;
    if (alphaMultiplyMode == avifAlphaMultiplyMode.AVIF_ALPHA_MULTIPLY_MODE_NO_OP || rgb.format.avifRGBFormatHasAlpha()) {
        avifResult libyuvResult = reformat_libyuv.avifImageYUVToRGBLibYUV(image, rgb);
        if (libyuvResult == avifResult.AVIF_RESULT_OK) {
            convertedWithLibYUV = true;
        } else {
            if (libyuvResult != avifResult.AVIF_RESULT_NOT_IMPLEMENTED) {
                return libyuvResult;
            }
        }
    }

    // Reformat alpha, if user asks for it, or (un)multiply processing needs it.
    if (rgb.format.avifRGBFormatHasAlpha() && (!rgb.ignoreAlpha || (alphaMultiplyMode != avifAlphaMultiplyMode.AVIF_ALPHA_MULTIPLY_MODE_NO_OP))) {
        avifAlphaParams params = new avifAlphaParams();

        params.width = rgb.width;
        params.height = rgb.height;
        params.dstDepth = rgb.depth;
        params.dstRange = avifRange.AVIF_RANGE_FULL;
        params.dstPlane = rgb.pixels;
        params.dstRowBytes = rgb.rowBytes;
        params.dstOffsetBytes = state.rgbOffsetBytesA;
        params.dstPixelBytes = state.rgbPixelBytes;

        if (image.alphaPlane != null && image.alphaRowBytes != 0) {
            params.srcDepth = image.depth;
            params.srcRange = image.alphaRange;
            params.srcPlane = image.alphaPlane;
            params.srcRowBytes = image.alphaRowBytes;
            params.srcOffsetBytes = 0;
            params.srcPixelBytes = state.yuvChannelBytes;

            params.avifReformatAlpha();
        } else {
            if (!convertedWithLibYUV) { // libyuv fills alpha for us
                params.avifFillAlpha();
            }
        }
    }

    if (!convertedWithLibYUV) {
        // libyuv is either unavailable or unable to perform the specific conversion required here.
        // Look over the available built-in "fast" routines for YUV.RGB conversion and see if one
        // fits the current combination, or as a last resort, call avifImageYUVAnyToRGBAnySlow(),
        // which handles every possibly YUV.RGB combination, but very slowly (in comparison).

        avifResult convertResult = avifResult.AVIF_RESULT_NOT_IMPLEMENTED;

        avifChromaUpsampling chromaUpsampling;
        switch (rgb.chromaUpsampling) {
            case AVIF_CHROMA_UPSAMPLING_AUTOMATIC:
            case AVIF_CHROMA_UPSAMPLING_BEST_QUALITY:
            case AVIF_CHROMA_UPSAMPLING_BILINEAR:
            default:
                chromaUpsampling = avifChromaUpsampling.AVIF_CHROMA_UPSAMPLING_BILINEAR;
                break;

            case AVIF_CHROMA_UPSAMPLING_FASTEST:
            case AVIF_CHROMA_UPSAMPLING_NEAREST:
                chromaUpsampling = avifChromaUpsampling.AVIF_CHROMA_UPSAMPLING_NEAREST;
                break;
        }

        final boolean hasColor =
            (image.yuvRowBytes[avifChannelIndex.AVIF_CHAN_U.v] != 0 && image.yuvRowBytes[avifChannelIndex.AVIF_CHAN_V.v] != 0 && (image.yuvFormat != avifPixelFormat.AVIF_PIXEL_FORMAT_YUV400));

        if ((!hasColor || (image.yuvFormat == avifPixelFormat.AVIF_PIXEL_FORMAT_YUV444) || (chromaUpsampling == avifChromaUpsampling.AVIF_CHROMA_UPSAMPLING_NEAREST)) &&
            (alphaMultiplyMode == avifAlphaMultiplyMode.AVIF_ALPHA_MULTIPLY_MODE_NO_OP || rgb.format.avifRGBFormatHasAlpha())) {
            // Explanations on the above conditional:
            // * None of these fast paths currently support bilinear upsampling, so avoid all of them
            //   unless the YUV data isn't subsampled or they explicitly requested AVIF_CHROMA_UPSAMPLING_NEAREST.
            // * None of these fast paths currently handle alpha (un)multiply, so avoid all of them
            //   if we can't do alpha (un)multiply as a separated post step (destination format doesn't have alpha).

            if (state.mode == avifReformatMode.AVIF_REFORMAT_MODE_IDENTITY) {
                if ((image.depth == 8) && (rgb.depth == 8) && (image.yuvFormat == avifPixelFormat.AVIF_PIXEL_FORMAT_YUV444) &&
                    (image.yuvRange == avifRange.AVIF_RANGE_FULL)) {
                    convertResult = avifImageIdentity8ToRGB8ColorFullRange(image, rgb, state);
                }

                // TODO: Add more fast paths for identity
            } else if (state.mode == avifReformatMode.AVIF_REFORMAT_MODE_YUV_COEFFICIENTS) {
                if (image.depth > 8) {
                    // yuv:u16

                    if (rgb.depth > 8) {
                        // yuv:u16, rgb:u16

                        if (hasColor) {
                            convertResult = avifImageYUV16ToRGB16Color(image, rgb, state);
                        } else {
                            convertResult = avifImageYUV16ToRGB16Mono(image, rgb, state);
                        }
                    } else {
                        // yuv:u16, rgb:u8

                        if (hasColor) {
                            convertResult = avifImageYUV16ToRGB8Color(image, rgb, state);
                        } else {
                            convertResult = avifImageYUV16ToRGB8Mono(image, rgb, state);
                        }
                    }
                } else {
                    // yuv:u8

                    if (rgb.depth > 8) {
                        // yuv:u8, rgb:u16

                        if (hasColor) {
                            convertResult = avifImageYUV8ToRGB16Color(image, rgb, state);
                        } else {
                            convertResult = avifImageYUV8ToRGB16Mono(image, rgb, state);
                        }
                    } else {
                        // yuv:u8, rgb:u8

                        if (hasColor) {
                            convertResult = avifImageYUV8ToRGB8Color(image, rgb, state);
                        } else {
                            convertResult = avifImageYUV8ToRGB8Mono(image, rgb, state);
                        }
                    }
                }
            }
        }

        if (convertResult == avifResult.AVIF_RESULT_NOT_IMPLEMENTED) {
            // If we get here, there is no fast path for this combination. Time to be slow!
            convertResult = avifImageYUVAnyToRGBAnySlow(image, rgb, state, chromaUpsampling);

            // The slow path also handles alpha (un)multiply, so forget the operation here.
            alphaMultiplyMode = avifAlphaMultiplyMode.AVIF_ALPHA_MULTIPLY_MODE_NO_OP;
        }

        if (convertResult != avifResult.AVIF_RESULT_OK) {
            return convertResult;
        }
    }

    // Process alpha premultiplication, if necessary
    if (alphaMultiplyMode == avifAlphaMultiplyMode.AVIF_ALPHA_MULTIPLY_MODE_MULTIPLY) {
        avifResult result = alpha.avifRGBImagePremultiplyAlpha(rgb);
        if (result != avifResult.AVIF_RESULT_OK) {
            return result;
        }
    } else if (alphaMultiplyMode == avifAlphaMultiplyMode.AVIF_ALPHA_MULTIPLY_MODE_UNMULTIPLY) {
        avifResult result = alpha.avifRGBImageUnpremultiplyAlpha(rgb);
        if (result != avifResult.AVIF_RESULT_OK) {
            return result;
        }
    }

    // Convert pixels to half floats (F16), if necessary.
    if (rgb.isFloat) {
        return avifRGBImageToF16(rgb);
    }

    return avifResult.AVIF_RESULT_OK;
}

// Limited -> Full
// Plan: subtract limited offset, then multiply by ratio of FULLSIZE/LIMITEDSIZE (rounding), then clamp.
// RATIO = (FULLY - 0) / (MAXLIMITEDY - MINLIMITEDY)
// -----------------------------------------
// ( ( (v - MINLIMITEDY)                    | subtract limited offset
//     * FULLY                              | multiply numerator of ratio
//   ) + ((MAXLIMITEDY - MINLIMITEDY) / 2)  | add 0.5 (half of denominator) to round
// ) / (MAXLIMITEDY - MINLIMITEDY)          | divide by denominator of ratio
// AVIF_CLAMP(v, 0, FULLY)                  | clamp to full range
// -----------------------------------------
private static final int LIMITED_TO_FULL(int v, int MINLIMITEDY, int MAXLIMITEDY, int FULLY) {
    v = (((v - MINLIMITEDY) * FULLY) + ((MAXLIMITEDY - MINLIMITEDY) / 2)) / (MAXLIMITEDY - MINLIMITEDY);
    return avif.AVIF_CLAMP(v, 0, FULLY);
}

// Full -> Limited
// Plan: multiply by ratio of LIMITEDSIZE/FULLSIZE (rounding), then add limited offset, then clamp.
// RATIO = (MAXLIMITEDY - MINLIMITEDY) / (FULLY - 0)
// -----------------------------------------
// ( ( (v * (MAXLIMITEDY - MINLIMITEDY))    | multiply numerator of ratio
//     + (FULLY / 2)                        | add 0.5 (half of denominator) to round
//   ) / FULLY                              | divide by denominator of ratio
// ) + MINLIMITEDY                          | add limited offset
//  AVIF_CLAMP(v, MINLIMITEDY, MAXLIMITEDY) | clamp to limited range
// -----------------------------------------
private static final int FULL_TO_LIMITED(int v, int MINLIMITEDY, int MAXLIMITEDY, int FULLY) {
    v = (((v * (MAXLIMITEDY - MINLIMITEDY)) + (FULLY / 2)) / FULLY) + MINLIMITEDY;
    return avif.AVIF_CLAMP(v, MINLIMITEDY, MAXLIMITEDY);
}

public static int avifLimitedToFullY(int depth, int v)
{
    switch (depth) {
        case 8:
            return  LIMITED_TO_FULL(v, 16, 235, 255);
        case 10:
            return LIMITED_TO_FULL(v, 64, 940, 1023);
        case 12:
            return LIMITED_TO_FULL(v, 256, 3760, 4095);
    }
    return v;
}

public static int avifLimitedToFullUV(int depth, int v)
{
    switch (depth) {
        case 8:
            return LIMITED_TO_FULL(v, 16, 240, 255);
        case 10:
            return LIMITED_TO_FULL(v, 64, 960, 1023);
        case 12:
            return LIMITED_TO_FULL(v, 256, 3840, 4095);
    }
    return v;
}

public static int avifFullToLimitedY(int depth, int v)
{
    switch (depth) {
        case 8:
            return FULL_TO_LIMITED(v, 16, 235, 255);
        case 10:
            return FULL_TO_LIMITED(v, 64, 940, 1023);
        case 12:
            return FULL_TO_LIMITED(v, 256, 3760, 4095);
    }
    return v;
}

public static int avifFullToLimitedUV(int depth, int v)
{
    switch (depth) {
        case 8:
            return FULL_TO_LIMITED(v, 16, 240, 255);
        case 10:
            return FULL_TO_LIMITED(v, 64, 960, 1023);
        case 12:
            return FULL_TO_LIMITED(v, 256, 3840, 4095);
    }
    return v;
}
}