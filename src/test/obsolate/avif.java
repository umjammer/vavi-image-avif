// Copyright 2019 Joe Drago. All rights reserved.
// SPDX-License-Identifier: BSD-2-Clause

package vavi.awt.image.avif;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import vavi.awt.image.avif.avif.avifPixelFormat.avifPixelFormatInfo;
import vavi.awt.image.avif.read.avifDecodeSample;
import vavi.awt.image.avif.read.avifDecoderData;
import vavi.awt.image.avif.write.avifEncoderData;

class avif {

 // ---------------------------------------------------------------------------
 // Constants

 // AVIF_VERSION_DEVEL should always be 0 for official releases / version tags,
 // and non-zero during development of the next release. This should allow for
 // downstream projects to do greater-than preprocessor checks on AVIF_VERSION
 // to leverage in-development code without breaking their stable builds.
 public static final int AVIF_VERSION_MAJOR = 0;
 public static final int AVIF_VERSION_MINOR = 9;
 public static final int AVIF_VERSION_PATCH = 3;
 public static final int AVIF_VERSION_DEVEL = 1;
 public static final int AVIF_VERSION =
     ((AVIF_VERSION_MAJOR * 1000000) + (AVIF_VERSION_MINOR * 10000) + (AVIF_VERSION_PATCH * 100) + AVIF_VERSION_DEVEL);

  public static final int AVIF_DIAGNOSTICS_ERROR_BUFFER_SIZE = 256;

 // A reasonable default for maximum image size to avoid out-of-memory errors or integer overflow in
 // (32-bit) int or unsigned int arithmetic operations.
 public static final int AVIF_DEFAULT_IMAGE_SIZE_LIMIT = (16384 * 16384);

 // a 12 hour AVIF image sequence, running at 60 fps (a basic sanity check as this is quite ridiculous)
 public static final int AVIF_DEFAULT_IMAGE_COUNT_LIMIT = (12 * 3600 * 60);

 public static final int AVIF_QUANTIZER_LOSSLESS = 0;
 public static final int AVIF_QUANTIZER_BEST_QUALITY = 0;
 public static final int AVIF_QUANTIZER_WORST_QUALITY = 63;

 public static final int AVIF_PLANE_COUNT_YUV = 3;

 public static final int AVIF_SPEED_DEFAULT = -1;
 public static final int AVIF_SPEED_SLOWEST = 0;
 public static final int AVIF_SPEED_FASTEST = 10;

 enum avifPlanesFlag
 {
     AVIF_PLANES_YUV(1 << 0),
     AVIF_PLANES_A(1 << 1),

     AVIF_PLANES_ALL(0xff);
     int v;
     avifPlanesFlag(int v) { this.v = v; }
 }

 enum avifChannelIndex
 {
     // rgbPlanes
     AVIF_CHAN_R(0),
     AVIF_CHAN_G(1),
     AVIF_CHAN_B(2),

     // yuvPlanes
     AVIF_CHAN_Y(0),
     AVIF_CHAN_U(1),
     AVIF_CHAN_V(2);
     int v;
     avifChannelIndex(int v) { this.v = v; }
 }

enum avifResult
 {
     AVIF_RESULT_OK("OK"),
     AVIF_RESULT_UNKNOWN_ERROR("Unknown Error"),
     AVIF_RESULT_INVALID_FTYP("Invalid ftyp"),
     AVIF_RESULT_NO_CONTENT("No content"),
     AVIF_RESULT_NO_YUV_FORMAT_SELECTED("No YUV format selected"),
     AVIF_RESULT_REFORMAT_FAILED("Reformat failed"),
     AVIF_RESULT_UNSUPPORTED_DEPTH("Unsupported depth"),
     AVIF_RESULT_ENCODE_COLOR_FAILED("Encoding of color planes failed"),
     AVIF_RESULT_ENCODE_ALPHA_FAILED("Encoding of alpha plane failed"),
     AVIF_RESULT_BMFF_PARSE_FAILED("BMFF parsing failed"),
     AVIF_RESULT_NO_AV1_ITEMS_FOUND("No AV1 items found"),
     AVIF_RESULT_DECODE_COLOR_FAILED("Decoding of color planes failed"),
     AVIF_RESULT_DECODE_ALPHA_FAILED("Decoding of alpha plane failed"),
     AVIF_RESULT_COLOR_ALPHA_SIZE_MISMATCH("Color and alpha planes size mismatch"),
     AVIF_RESULT_ISPE_SIZE_MISMATCH("Plane sizes don't match ispe values"),
     AVIF_RESULT_NO_CODEC_AVAILABLE("No codec available"),
     AVIF_RESULT_NO_IMAGES_REMAINING("No images remaining"),
     AVIF_RESULT_INVALID_EXIF_PAYLOAD("Invalid Exif payload"),
     AVIF_RESULT_INVALID_IMAGE_GRID("Invalid image grid"),
     AVIF_RESULT_INVALID_CODEC_SPECIFIC_OPTION("Invalid codec-specific option"),
     AVIF_RESULT_TRUNCATED_DATA("Truncated data"),
     AVIF_RESULT_IO_NOT_SET("IO not set"), // the avifIO field of avifDecoder is not set
     AVIF_RESULT_IO_ERROR("IO Error"),
     AVIF_RESULT_WAITING_ON_IO("Waiting on IO"), // similar to EAGAIN/EWOULDBLOCK, this means the avifIO doesn't have necessary data available yet
     AVIF_RESULT_INVALID_ARGUMENT("Invalid argument"), // an argument passed into this function is invalid
     AVIF_RESULT_NOT_IMPLEMENTED("Not implemented"),  // a requested code path is not (yet) implemented
     AVIF_RESULT_OUT_OF_MEMORY("Out of memory");
     String result;
     avifResult(String result) {
         this.result = result;
     }
 }

public static final byte[] AVIF_DATA_EMPTY = null;

 enum avifPixelFormat
 {
     // No YUV pixels are present. Alpha plane can still be present.
     AVIF_PIXEL_FORMAT_NONE("Unknown", null),

     AVIF_PIXEL_FORMAT_YUV444("YUV444", new avifPixelFormatInfo(0, 0, false)),
     AVIF_PIXEL_FORMAT_YUV422("YUV420", new avifPixelFormatInfo(1, 0, false)),
     AVIF_PIXEL_FORMAT_YUV420("YUV422", new avifPixelFormatInfo(1, 1, false)),
     AVIF_PIXEL_FORMAT_YUV400("YUV400", new avifPixelFormatInfo(1, 1, true));
     static class avifPixelFormatInfo {
         public avifPixelFormatInfo(int chromaShiftX, int chromaShiftY, boolean monochrome) {
            this.chromaShiftX = chromaShiftX;
            this.chromaShiftY = chromaShiftY;
            this.monochrome = monochrome;
        }
        boolean monochrome;
        int chromaShiftX;
        int chromaShiftY;
     }

     String name;
     avifPixelFormatInfo info;
     avifPixelFormat(String name, avifPixelFormatInfo info) {
         this.name = name;
         this.info = info;
     }
     final String avifPixelFormatToString() {
         return name;
     }
     avifPixelFormatInfo avifGetPixelFormatInfo() {
         return info;
     }
}

 enum avifChromaSamplePosition
 {
     AVIF_CHROMA_SAMPLE_POSITION_UNKNOWN,
     AVIF_CHROMA_SAMPLE_POSITION_VERTICAL,
     AVIF_CHROMA_SAMPLE_POSITION_COLOCATED;
     static avifChromaSamplePosition valueOf(int v) { return Arrays.stream(values()).filter(e -> e.ordinal() == v).findFirst().get(); }
 }

enum avifRange
 {
     AVIF_RANGE_LIMITED,
     AVIF_RANGE_FULL
 }

 enum avifColorPrimaries
 {
     // This is actually reserved, but libavif uses it as a sentinel value.
     AVIF_COLOR_PRIMARIES_UNKNOWN(0),

     AVIF_COLOR_PRIMARIES_BT709(1),
     AVIF_COLOR_PRIMARIES_IEC61966_2_4(1),
     AVIF_COLOR_PRIMARIES_UNSPECIFIED(2),
     AVIF_COLOR_PRIMARIES_BT470M(4),
     AVIF_COLOR_PRIMARIES_BT470BG(5),
     AVIF_COLOR_PRIMARIES_BT601(6),
     AVIF_COLOR_PRIMARIES_SMPTE240(7),
     AVIF_COLOR_PRIMARIES_GENERIC_FILM(8),
     AVIF_COLOR_PRIMARIES_BT2020(9),
     AVIF_COLOR_PRIMARIES_XYZ(10),
     AVIF_COLOR_PRIMARIES_SMPTE431(11),
     AVIF_COLOR_PRIMARIES_SMPTE432(12), // DCI P3
     AVIF_COLOR_PRIMARIES_EBU3213(22);
     int v;
     avifColorPrimaries(int v) { this.v = v; }
     static avifColorPrimaries valueOf(int v) { return Arrays.stream(values()).filter(e -> e.v == v).findFirst().get(); }
 }
 
 enum avifTransferCharacteristics
 {
     // This is actually reserved, but libavif uses it as a sentinel value.
     AVIF_TRANSFER_CHARACTERISTICS_UNKNOWN(0),

     AVIF_TRANSFER_CHARACTERISTICS_BT709(1),
     AVIF_TRANSFER_CHARACTERISTICS_UNSPECIFIED(2),
     AVIF_TRANSFER_CHARACTERISTICS_BT470M(4),  // 2.2 gamma
     AVIF_TRANSFER_CHARACTERISTICS_BT470BG(5), // 2.8 gamma
     AVIF_TRANSFER_CHARACTERISTICS_BT601(6),
     AVIF_TRANSFER_CHARACTERISTICS_SMPTE240(7),
     AVIF_TRANSFER_CHARACTERISTICS_LINEAR(8),
     AVIF_TRANSFER_CHARACTERISTICS_LOG100(9),
     AVIF_TRANSFER_CHARACTERISTICS_LOG100_SQRT10(10),
     AVIF_TRANSFER_CHARACTERISTICS_IEC61966(11),
     AVIF_TRANSFER_CHARACTERISTICS_BT1361(12),
     AVIF_TRANSFER_CHARACTERISTICS_SRGB(13),
     AVIF_TRANSFER_CHARACTERISTICS_BT2020_10BIT(14),
     AVIF_TRANSFER_CHARACTERISTICS_BT2020_12BIT(15),
     AVIF_TRANSFER_CHARACTERISTICS_SMPTE2084(16), // PQ
     AVIF_TRANSFER_CHARACTERISTICS_SMPTE428(17),
     AVIF_TRANSFER_CHARACTERISTICS_HLG(18);
     int v;
     avifTransferCharacteristics(int v) { this.v = v; }
     static avifTransferCharacteristics valueOf(int v) { return Arrays.stream(values()).filter(e -> e.v == v).findFirst().get(); }
 }
 
 enum avifMatrixCoefficients
 {
     AVIF_MATRIX_COEFFICIENTS_IDENTITY(0),
     AVIF_MATRIX_COEFFICIENTS_BT709(1),
     AVIF_MATRIX_COEFFICIENTS_UNSPECIFIED(2),
     AVIF_MATRIX_COEFFICIENTS_FCC(4),
     AVIF_MATRIX_COEFFICIENTS_BT470BG(5),
     AVIF_MATRIX_COEFFICIENTS_BT601(6),
     AVIF_MATRIX_COEFFICIENTS_SMPTE240(7),
     AVIF_MATRIX_COEFFICIENTS_YCGCO(8),
     AVIF_MATRIX_COEFFICIENTS_BT2020_NCL(9),
     AVIF_MATRIX_COEFFICIENTS_BT2020_CL(10),
     AVIF_MATRIX_COEFFICIENTS_SMPTE2085(11),
     AVIF_MATRIX_COEFFICIENTS_CHROMA_DERIVED_NCL(12),
     AVIF_MATRIX_COEFFICIENTS_CHROMA_DERIVED_CL(13),
     AVIF_MATRIX_COEFFICIENTS_ICTCP(14);
     int v;
     avifMatrixCoefficients(int v) { this.v = v; }
     static avifMatrixCoefficients valueOf(int v) { return Arrays.stream(values()).filter(e -> e.v == v).findFirst().get(); }
 }

  enum avifTransformFlag
 {
     AVIF_TRANSFORM_NONE(0),

     AVIF_TRANSFORM_PASP(1 << 0),
     AVIF_TRANSFORM_CLAP(1 << 1),
     AVIF_TRANSFORM_IROT(1 << 2),
     AVIF_TRANSFORM_IMIR(1 << 3);
     int v;
     avifTransformFlag(int v) { this.v = v; }
 }
 
 class avifPixelAspectRatioBox
 {
     // 'pasp' from ISO/IEC 14496-12:2015 12.1.4.3

     // define the relative width and height of a pixel
     int hSpacing;
     int vSpacing;
 }

 class avifCleanApertureBox
 {
     // 'clap' from ISO/IEC 14496-12:2015 12.1.4.3

     // a fractional number which defines the exact clean aperture width, in counted pixels, of the video image
     int widthN;
     int widthD;

     // a fractional number which defines the exact clean aperture height, in counted pixels, of the video image
     int heightN;
     int heightD;

     // a fractional number which defines the horizontal offset of clean aperture centre minus (width-1)/2. Typically 0.
     int horizOffN;
     int horizOffD;

     // a fractional number which defines the vertical offset of clean aperture centre minus (height-1)/2. Typically 0.
     int vertOffN;
     int vertOffD;
 }

 class avifImageRotation
 {
     // 'irot' from ISO/IEC 23008-12:2017 6.5.10

     // angle * 90 specifies the angle (in anti-clockwise direction) in units of degrees.
     byte angle; // legal values: [0-3]
 }

 class avifImageMirror
 {
     // 'imir' from ISO/IEC 23008-12:2017 6.5.12 (Draft Amendment 2):
     //
     //     'mode' specifies how the mirroring is performed:
     //
     //     0 indicates that the top and bottom parts of the image are exchanged;
     //     1 specifies that the left and right parts are exchanged.
     //
     //     NOTE In Exif, orientation tag can be used to signal mirroring operations. Exif
     //     orientation tag 4 corresponds to mode = 0 of ImageMirror, and Exif orientation tag 2
     //     corresponds to mode = 1 accordingly.
     //
     // Legal values: [0, 1]
     //
     // NOTE: As of HEIF Draft Amendment 2, the name of this variable has changed from 'axis' to 'mode' as
     //       the logic behind it has been *inverted*. Please use the wording above describing the legal
     //       values for 'mode' and update any code that previously may have used `axis` to use
     //       the *opposite* value (0 now means top-to-bottom, where it used to mean left-to-right).
     byte mode;
 }

 class avifCropRect
 {
     int x;
     int y;
     int width;
     int height;
 }


 static class avifImage
 {
     // Image information
     int width;
     int height;
     int depth; // all planes must share this depth; if depth>8, all planes are uint16_t internally

     avifPixelFormat yuvFormat;
     avifRange yuvRange;
     avifChromaSamplePosition yuvChromaSamplePosition;
     byte[][] yuvPlanes = new byte[AVIF_PLANE_COUNT_YUV][];
     int[] yuvRowBytes = new int[AVIF_PLANE_COUNT_YUV];
     boolean imageOwnsYUVPlanes;

     avifRange alphaRange;
     byte[] alphaPlane;
     int alphaRowBytes;
     boolean imageOwnsAlphaPlane;
     boolean alphaPremultiplied;

     // ICC Profile
     byte[] /* avifRWData */ icc;

     // CICP information:
     // These are stored in the AV1 payload and used to signal YUV conversion. Additionally, if an
     // ICC profile is not specified, these will be stored in the AVIF container's `colr` box with
     // a type of `nclx`. If your system supports ICC profiles, be sure to check for the existence
     // of one (avifImage.icc) before relying on the values listed here!
     avifColorPrimaries colorPrimaries;
     avifTransferCharacteristics transferCharacteristics;
     avifMatrixCoefficients matrixCoefficients;

     // Transformations - These metadata values are encoded/decoded when transformFlags are set
     // appropriately, but do not impact/adjust the actual pixel buffers used (images won't be
     // pre-cropped or mirrored upon decode). Basic explanations from the standards are offered in
     // comments above, but for detailed explanations, please refer to the HEIF standard (ISO/IEC
     // 23008-12:2017) and the BMFF standard (ISO/IEC 14496-12:2015).
     //
     // To encode any of these boxes, set the values in the associated box, then enable the flag in
     // transformFlags. On decode, only honor the values in boxes with the associated transform flag set.
     EnumSet<avifTransformFlag> transformFlags;
     avifPixelAspectRatioBox pasp;
     avifCleanApertureBox clap;
     avifImageRotation irot;
     avifImageMirror imir;

     // Metadata - set with avifImageSetMetadata*() before write, check .size>0 for existence after read
     byte[] /* avifRWData */ exif;
     byte[] /* avifRWData */ xmp;

     void avifImageAllocatePlanes(EnumSet<avifPlanesFlag> planes) {
         int channelSize = avifImageUsesU16() ? 2 : 1;
         int fullRowBytes = channelSize * this.width;
         int fullSize = fullRowBytes * this.height;
         if (planes.contains(avifPlanesFlag.AVIF_PLANES_YUV) && (this.yuvFormat != avifPixelFormat.AVIF_PIXEL_FORMAT_NONE)) {
             avifPixelFormatInfo info = this.yuvFormat.avifGetPixelFormatInfo();

             int shiftedW = (this.width + info.chromaShiftX) >> info.chromaShiftX;
             int shiftedH = (this.height + info.chromaShiftY) >> info.chromaShiftY;

             int uvRowBytes = channelSize * shiftedW;
             int uvSize = uvRowBytes * shiftedH;

             if (this.yuvPlanes[avifChannelIndex.AVIF_CHAN_Y.v] != null) {
                 this.yuvRowBytes[avifChannelIndex.AVIF_CHAN_Y.v] = fullRowBytes;
                 this.yuvPlanes[avifChannelIndex.AVIF_CHAN_Y.v] = new byte[fullSize];
             }

             if (this.yuvFormat != avifPixelFormat.AVIF_PIXEL_FORMAT_YUV400) {
                 if (this.yuvPlanes[avifChannelIndex.AVIF_CHAN_U.v] != null) {
                     this.yuvRowBytes[avifChannelIndex.AVIF_CHAN_U.v] = uvRowBytes;
                     this.yuvPlanes[avifChannelIndex.AVIF_CHAN_U.v] = new byte[uvSize];
                 }
                 if (this.yuvPlanes[avifChannelIndex.AVIF_CHAN_V.v] != null) {
                     this.yuvRowBytes[avifChannelIndex.AVIF_CHAN_V.v] = uvRowBytes;
                     this.yuvPlanes[avifChannelIndex.AVIF_CHAN_V.v] = new byte[uvSize];
                 }
             }
             this.imageOwnsYUVPlanes = true;
         }
         if (planes.contains(avifPlanesFlag.AVIF_PLANES_A)) {
             if (this.alphaPlane != null) {
                 this.alphaRowBytes = fullRowBytes;
                 this.alphaPlane = new byte[fullSize];
             }
             this.imageOwnsAlphaPlane = true;
         }
     }

     boolean avifImageUsesU16() {
         return this.depth > 8;
     }

     void avifImageSetProfileICC(final byte[] icc, int iccSize)
     {
         rawdata.avifRWDataSet(this.icc, icc, iccSize);
     }

     void avifImageSetMetadataExif(final byte[] exif, int exifSize)
     {
         rawdata.avifRWDataSet(this.exif, exif, exifSize);
     }

     void avifImageSetMetadataXMP(final byte[] xmp, int xmpSize)
     {
         rawdata.avifRWDataSet(this.xmp, xmp, xmpSize);
     }

     private void avifImageSetDefaults()
     {
         this.yuvRange = avifRange.AVIF_RANGE_FULL;
         this.alphaRange = avifRange.AVIF_RANGE_FULL;
         this.colorPrimaries = avifColorPrimaries.AVIF_COLOR_PRIMARIES_UNSPECIFIED;
         this.transferCharacteristics = avifTransferCharacteristics.AVIF_TRANSFER_CHARACTERISTICS_UNSPECIFIED;
         this.matrixCoefficients = avifMatrixCoefficients.AVIF_MATRIX_COEFFICIENTS_UNSPECIFIED;
     }

     avifImage(int width, int height, int depth, avifPixelFormat yuvFormat)
     {
         avifImageSetDefaults();
         this.width = width;
         this.height = height;
         this.depth = depth;
         this.yuvFormat = yuvFormat;
     }

     avifImage()
     {
         this(0, 0, 0, avifPixelFormat.AVIF_PIXEL_FORMAT_NONE);
     }

     void avifImageStealPlanes(avifImage  dstImage, EnumSet<avifPlanesFlag> planes)
     {
         if (planes.contains(avifPlanesFlag.AVIF_PLANES_YUV)) {
             dstImage.yuvPlanes[avifChannelIndex.AVIF_CHAN_Y.v] = this.yuvPlanes[avifChannelIndex.AVIF_CHAN_Y.v];
             dstImage.yuvRowBytes[avifChannelIndex.AVIF_CHAN_Y.v] = this.yuvRowBytes[avifChannelIndex.AVIF_CHAN_Y.v];
             dstImage.yuvPlanes[avifChannelIndex.AVIF_CHAN_U.v] = this.yuvPlanes[avifChannelIndex.AVIF_CHAN_U.v];
             dstImage.yuvRowBytes[avifChannelIndex.AVIF_CHAN_U.v] = this.yuvRowBytes[avifChannelIndex.AVIF_CHAN_U.v];
             dstImage.yuvPlanes[avifChannelIndex.AVIF_CHAN_V.v] = this.yuvPlanes[avifChannelIndex.AVIF_CHAN_V.v];
             dstImage.yuvRowBytes[avifChannelIndex.AVIF_CHAN_V.v] = this.yuvRowBytes[avifChannelIndex.AVIF_CHAN_V.v];

             this.yuvPlanes[avifChannelIndex.AVIF_CHAN_Y.v] = null;
             this.yuvRowBytes[avifChannelIndex.AVIF_CHAN_Y.v] = 0;
             this.yuvPlanes[avifChannelIndex.AVIF_CHAN_U.v] = null;
             this.yuvRowBytes[avifChannelIndex.AVIF_CHAN_U.v] = 0;
             this.yuvPlanes[avifChannelIndex.AVIF_CHAN_V.v] = null;
             this.yuvRowBytes[avifChannelIndex.AVIF_CHAN_V.v] = 0;

             dstImage.yuvFormat = this.yuvFormat;
             dstImage.imageOwnsYUVPlanes = this.imageOwnsYUVPlanes;
             this.imageOwnsYUVPlanes = false;
         }
         if (planes.contains(avifPlanesFlag.AVIF_PLANES_A)) {
             dstImage.alphaPlane = this.alphaPlane;
             dstImage.alphaRowBytes = this.alphaRowBytes;

             this.alphaPlane = null;
             this.alphaRowBytes = 0;

             dstImage.imageOwnsAlphaPlane = this.imageOwnsAlphaPlane;
             this.imageOwnsAlphaPlane = false;
         }
     }

     void avifImageCopy(avifImage  dstImage, EnumSet<avifPlanesFlag> planes)
     {
         avifImageCopyNoAlloc(dstImage);

         dstImage.avifImageSetProfileICC(this.icc, this.icc.length);

         dstImage.avifImageSetMetadataExif(this.exif, this.exif.length);
         dstImage.avifImageSetMetadataXMP(this.xmp, this.xmp.length);

         if (planes.contains(avifPlanesFlag.AVIF_PLANES_YUV) && this.yuvPlanes[avifChannelIndex.AVIF_CHAN_Y.v] != null) {
             dstImage.avifImageAllocatePlanes(EnumSet.of(avifPlanesFlag.AVIF_PLANES_YUV));

             avifPixelFormatInfo formatInfo = this.yuvFormat.avifGetPixelFormatInfo();
             int uvHeight = (dstImage.height + formatInfo.chromaShiftY) >> formatInfo.chromaShiftY;
             for (int yuvPlane = 0; yuvPlane < 3; ++yuvPlane) {
                 int planeHeight = (yuvPlane == avifChannelIndex.AVIF_CHAN_Y.v) ? dstImage.height : uvHeight;

                 if (this.yuvRowBytes[yuvPlane] != 0) {
                     // plane is absent. If we're copying from a source without
                     // them, mimic the source image's state by removing our copy.
                     dstImage.yuvPlanes[yuvPlane] = null;
                     dstImage.yuvRowBytes[yuvPlane] = 0;
                     continue;
                 }

                 for (int j = 0; j < planeHeight; ++j) {
                     int srcRow_P = j * this.yuvRowBytes[yuvPlane];
                     int dstRow_P = j * dstImage.yuvRowBytes[yuvPlane];
                     System.arraycopy(this.yuvPlanes[yuvPlane], srcRow_P, dstImage.yuvPlanes[yuvPlane], dstRow_P, dstImage.yuvRowBytes[yuvPlane]);
                 }
             }
         }

         if (planes.contains(avifPlanesFlag.AVIF_PLANES_A) && this.alphaPlane != null) {
             dstImage.avifImageAllocatePlanes(EnumSet.of(avifPlanesFlag.AVIF_PLANES_A));
             for (int j = 0; j < dstImage.height; ++j) {
                 int srcAlphaRow_P = j * this.alphaRowBytes;
                 int dstAlphaRow_P = j * dstImage.alphaRowBytes;
                 System.arraycopy(this.alphaPlane, srcAlphaRow_P, dstImage.alphaPlane, dstAlphaRow_P, dstImage.alphaRowBytes);
             }
         }
     }

     // Copies all fields that do not need to be freed/allocated from srcImage to dstImage.
     private void avifImageCopyNoAlloc(avifImage  dstImage)
     {
         dstImage.width = this.width;
         dstImage.height = this.height;
         dstImage.depth = this.depth;
         dstImage.yuvFormat = this.yuvFormat;
         dstImage.yuvRange = this.yuvRange;
         dstImage.yuvChromaSamplePosition = this.yuvChromaSamplePosition;
         dstImage.alphaRange = this.alphaRange;
         dstImage.alphaPremultiplied = this.alphaPremultiplied;

         dstImage.colorPrimaries = this.colorPrimaries;
         dstImage.transferCharacteristics = this.transferCharacteristics;
         dstImage.matrixCoefficients = this.matrixCoefficients;

         dstImage.transformFlags = this.transformFlags;
         dstImage.pasp = this.pasp;
         dstImage.clap = this.clap;
         dstImage.irot = this.irot;
         dstImage.imir = this.imir;
     }

     avifResult avifImageSetViewRect(avifImage  dstImage, final avifCropRect  rect)
     {
         avifPixelFormatInfo formatInfo = this.yuvFormat.avifGetPixelFormatInfo();
         if ((rect.width > this.width) || (rect.height > this.height) || (rect.x > (this.width - rect.width)) ||
             (rect.y > (this.height - rect.height)) || (rect.x & formatInfo.chromaShiftX) != 0 || (rect.y & formatInfo.chromaShiftY) != 0) {
             return avifResult.AVIF_RESULT_INVALID_ARGUMENT;
         }
         this.avifImageCopyNoAlloc(dstImage);
         dstImage.width = rect.width;
         dstImage.height = rect.height;
         final int pixelBytes = (this.depth > 8) ? 2 : 1;
         if (this.yuvPlanes[avifChannelIndex.AVIF_CHAN_Y.v] != null) {
             for (int yuvPlane = 0; yuvPlane < 3; ++yuvPlane) {
                 if (this.yuvRowBytes[yuvPlane] != 0) {
                     final int planeX = (yuvPlane == avifChannelIndex.AVIF_CHAN_Y.v) ? rect.x : (rect.x >> formatInfo.chromaShiftX);
                     final int planeY = (yuvPlane == avifChannelIndex.AVIF_CHAN_Y.v) ? rect.y : (rect.y >> formatInfo.chromaShiftY);
                     dstImage.yuvPlanes[yuvPlane] = new byte[this.yuvRowBytes[yuvPlane]];
                     int p = planeY * this.yuvRowBytes[yuvPlane] + planeX * pixelBytes;
                     System.arraycopy(this.yuvPlanes[yuvPlane], p, dstImage.yuvPlanes[yuvPlane], 0, this.yuvRowBytes[yuvPlane]);
                     dstImage.yuvRowBytes[yuvPlane] = this.yuvRowBytes[yuvPlane];
                 }
             }
         }
         if (this.alphaPlane != null) {
             dstImage.alphaPlane = new byte[this.alphaRowBytes];
             int p = rect.y * this.alphaRowBytes + rect.x * pixelBytes;
             System.arraycopy(this.alphaPlane, p, dstImage.alphaPlane , 0, this.alphaRowBytes);
             dstImage.alphaRowBytes = this.alphaRowBytes;
         }
         return avifResult.AVIF_RESULT_OK;
     }
}

 // ---------------------------------------------------------------------------
 // Understanding maxThreads
 //
 // libavif's structures and API use the setting 'maxThreads' in a few places. The intent of this
 // setting is to limit concurrent thread activity/usage, not necessarily to put a hard ceiling on
 // how many sleeping threads happen to exist behind the scenes. The goal of this setting is to
 // ensure that at any given point during libavif's encoding or decoding, no more than *maxThreads*
 // threads are simultaneously **active and taking CPU time**.
 //
 // As an important example, when encoding an image sequence that has an alpha channel, two
 // long-lived underlying AV1 encoders must simultaneously exist (one for color, one for alpha). For
 // each additional frame fed into libavif, its YUV planes are fed into one instance of the AV1
 // encoder, and its alpha plane is fed into another. These operations happen serially, so only one
 // of these AV1 encoders is ever active at a time. However, the AV1 encoders might pre-create a
 // pool of worker threads upon initialization, so during this process, twice the amount of worker
 // threads actually simultaneously exist on the machine, but half of them are guaranteed to be
 // sleeping.
 //
 // This design ensures that AV1 implementations are given as many threads as possible to ensure a
 // speedy encode or decode, despite the complexities of occasionally needing two AV1 codec instances
 // (due to alpha payloads being separate from color payloads). If your system has a hard ceiling on
 // the number of threads that can ever be in flight at a given time, please account for this
 // accordingly.

 // ---------------------------------------------------------------------------
 // Optional YUV<->RGB support

 // To convert to/from RGB, create an avifRGBImage on the stack, call avifRGBImageSetDefaults() on
 // it, and then tweak the values inside of it accordingly. At a minimum, you should populate
 // ->pixels and ->rowBytes with an appropriately sized pixel buffer, which should be at least
 // (->rowBytes * ->height) bytes, where ->rowBytes is at least (->width * avifRGBImagePixelSize()).
 // If you don't want to supply your own pixel buffer, you can use the
 // avifRGBImageAllocatePixels()/avifRGBImageFreePixels() convenience functions.

 // avifImageRGBToYUV() and avifImageYUVToRGB() will perform depth rescaling and limited<->full range
 // conversion, if necessary. Pixels in an avifRGBImage buffer are always full range, and conversion
 // routines will fail if the width and height don't match the associated avifImage.

 // If libavif is built with libyuv fast paths enabled, libavif will use libyuv for conversion from
 // YUV to RGB if the following requirements are met:
 //
 // * YUV depth: 8
 // * RGB depth: 8
 // * rgb.chromaUpsampling: AVIF_CHROMA_UPSAMPLING_AUTOMATIC, AVIF_CHROMA_UPSAMPLING_FASTEST
 // * rgb.format: AVIF_RGB_FORMAT_RGBA, AVIF_RGB_FORMAT_BGRA (420/422 support for AVIF_RGB_FORMAT_ABGR, AVIF_RGB_FORMAT_ARGB)
 // * CICP is one of the following combinations (CP/TC/MC/Range):
 //   * x/x/[2|5|6]/Full
 //   * [5|6]/x/12/Full
 //   * x/x/[1|2|5|6|9]/Limited
 //   * [1|2|5|6|9]/x/12/Limited

 enum avifRGBFormat
 {
     AVIF_RGB_FORMAT_RGB,
     AVIF_RGB_FORMAT_RGBA, // This is the default format set in avifRGBImageSetDefaults().
     AVIF_RGB_FORMAT_ARGB,
     AVIF_RGB_FORMAT_BGR,
     AVIF_RGB_FORMAT_BGRA,
     AVIF_RGB_FORMAT_ABGR;
     public boolean avifRGBFormatHasAlpha() {
         return (this != avifRGBFormat.AVIF_RGB_FORMAT_RGB) && (this != avifRGBFormat.AVIF_RGB_FORMAT_BGR);
     }
     int avifRGBFormatChannelCount() {
         return this.avifRGBFormatHasAlpha() ? 4 : 3;
     }
}
 
 enum avifChromaUpsampling
 {
     AVIF_CHROMA_UPSAMPLING_AUTOMATIC,    // Chooses best trade off of speed/quality (prefers libyuv, else uses BEST_QUALITY)
     AVIF_CHROMA_UPSAMPLING_FASTEST,      // Chooses speed over quality (prefers libyuv, else uses NEAREST)
     AVIF_CHROMA_UPSAMPLING_BEST_QUALITY, // Chooses the best quality upsampling, given settings (avoids libyuv)
     AVIF_CHROMA_UPSAMPLING_NEAREST,      // Uses nearest-neighbor filter (built-in)
     AVIF_CHROMA_UPSAMPLING_BILINEAR      // Uses bilinear filter (built-in)
 }

 class avifRGBImage
 {
     int width;       // must match associated avifImage
     int height;      // must match associated avifImage
     int depth;       // legal depths [8, 10, 12, 16]. if depth>8, pixels must be uint16_t internally
     avifRGBFormat format; // all channels are always full range
     avifChromaUpsampling chromaUpsampling; // Defaults to AVIF_CHROMA_UPSAMPLING_AUTOMATIC: How to upsample non-4:4:4 UV (ignored for 444) when converting to RGB.
                                            // Unused when converting to YUV. avifRGBImageSetDefaults() prefers quality over speed.
     boolean ignoreAlpha;        // Used for XRGB formats, treats formats containing alpha (such as ARGB) as if they were
                                  // RGB, treating the alpha bits as if they were all 1.
     boolean alphaPremultiplied; // indicates if RGB value is pre-multiplied by alpha. Default: false
     boolean isFloat; // indicates if RGBA values are in half float (f16) format. Valid only when depth == 16. Default: false

     byte[] pixels;
     int rowBytes;

     int avifRGBImagePixelSize()
     {
         return this.format.avifRGBFormatChannelCount() * ((this.depth > 8) ? 2 : 1);
     }

     void avifRGBImageSetDefaults(final avifImage  image)
     {
         this.width = image.width;
         this.height = image.height;
         this.depth = image.depth;
         this.format = avifRGBFormat.AVIF_RGB_FORMAT_RGBA;
         this.chromaUpsampling = avifChromaUpsampling.AVIF_CHROMA_UPSAMPLING_AUTOMATIC;
         this.ignoreAlpha = false;
         this.pixels = null;
         this.rowBytes = 0;
         this.alphaPremultiplied = false; // Most expect RGBA output to *not* be premultiplied. Those that do can opt-in by
                                               // setting this to match image.alphaPremultiplied or forcing this to true
                                               // after calling avifRGBImageSetDefaults(),
         this.isFloat = false;
     }

     void avifRGBImageAllocatePixels()
     {
         this.rowBytes = this.width * this.avifRGBImagePixelSize();
         this.pixels = new byte[this.rowBytes * this.height];
     }

     void avifRGBImageFreePixels()
     {
         this.pixels = null;
         this.rowBytes = 0;
     }

}

 enum avifCodecChoice
 {
     AVIF_CODEC_CHOICE_AUTO,
     AVIF_CODEC_CHOICE_AOM,
     AVIF_CODEC_CHOICE_DAV1D,   // Decode only
     AVIF_CODEC_CHOICE_LIBGAV1, // Decode only
     AVIF_CODEC_CHOICE_RAV1E,   // Encode only
     AVIF_CODEC_CHOICE_SVT      // Encode only
 }

 enum avifCodecFlag
 {
     AVIF_CODEC_FLAG_CAN_DECODE (1 << 0),
     AVIF_CODEC_FLAG_CAN_ENCODE (1 << 1);
     int v;
     avifCodecFlag(int v) { this.v = v; }
 }
 
 class avifCodecConfigurationBox
 {
     // [skipped; is finalant] unsigned int (1)marker = 1;
     // [skipped; is finalant] unsigned int (7)version = 1;

     byte seqProfile;           // unsigned int (3) seq_profile;
     byte seqLevelIdx0;         // unsigned int (5) seq_level_idx_0;
     byte seqTier0;             // unsigned int (1) seq_tier_0;
     byte highBitdepth;         // unsigned int (1) high_bitdepth;
     byte twelveBit;            // unsigned int (1) twelve_bit;
     byte monochrome;           // unsigned int (1) monochrome;
     byte chromaSubsamplingX;   // unsigned int (1) chroma_subsampling_x;
     byte chromaSubsamplingY;   // unsigned int (1) chroma_subsampling_y;
     byte chromaSamplePosition; // unsigned int (2) chroma_sample_position;

     // unsigned int (3)reserved = 0;
     // unsigned int (1)initial_presentation_delay_present;
     // if (initial_presentation_delay_present) {
     //     unsigned int (4)initial_presentation_delay_minus_one;
     // } else {
     //     unsigned int (4)reserved = 0;
     // }
 }

 static abstract class avifIO
 {
     // This is reserved for future use - but currently ignored. Set it to a null pointer.
     public abstract avifResult write(int a, int b, byte[] c, int d);

     public abstract avifResult read(int readFlags, long offset, int size, final byte[] /* avifROData */ out);

     // If non-zero, this is a hint to internal structures of the max size offered by the content
     // this avifIO structure is reading. If it is a static memory source, it should be the size of
     // the memory buffer; if it is a file, it should be the file's size. If this information cannot
     // be known (as it is streamed-in), set a reasonable upper boundary here (larger than the file
     // can possibly be for your environment, but within your environment's memory finalraints). This
     // is used for sanity checks when allocating internal buffers to protect against
     // malformed/malicious files.
     long sizeHint;

     // If true, *all* memory regions returned from *all* calls to read are guaranteed to be
     // persistent and exist for the lifetime of the avifIO object. If false, libavif will make
     // in-memory copies of samples and metadata content, and a memory region returned from read must
     // only persist until the next call to read.
     boolean persistent;

     // The contents of this are defined by the avifIO implementation, and should be fully destroyed
     // by the implementation of the associated destroy function, unless it isn't owned by the avifIO
     // struct. It is not necessary to use this pointer in your implementation.
     byte[] data;
 }

 // Some encoders (including very old versions of avifenc) do not implement the AVIF standard
 // perfectly, and thus create invalid files. However, these files are likely still recoverable /
 // decodable, if it wasn't for the strict requirements imposed by libavif's decoder. These flags
 // allow a user of avifDecoder to decide what level of strictness they want in their project.
 enum avifStrictFlag
 {
     // Disables all strict checks.
     AVIF_STRICT_DISABLED(0),

     // Requires the PixelInformationProperty ('pixi') be present in AV1 image items. libheif v1.11.0
     // or older does not add the 'pixi' item property to AV1 image items. If you need to decode AVIF
     // images encoded by libheif v1.11.0 or older, be sure to disable this bit. (This issue has been
     // corrected in libheif v1.12.0.)
     AVIF_STRICT_PIXI_REQUIRED (1 << 0),

     // This demands that the values surfaced in the clap box are valid, determined by attempting to
     // convert the clap box to a crop rect using avifCropRectConvertCleanApertureBox(). If this
     // function returns AVIF_FALSE and this strict flag is set, the decode will fail.
     AVIF_STRICT_CLAP_VALID (1 << 1),

     // Requires the ImageSpatialExtentsProperty ('ispe') be present in alpha auxiliary image items.
     // avif-serialize 0.7.3 or older does not add the 'ispe' item property to alpha auxiliary image
     // items. If you need to decode AVIF images encoded by the cavif encoder with avif-serialize
     // 0.7.3 or older, be sure to disable this bit. (This issue has been corrected in avif-serialize
     // 0.7.4.) See https://github.com/kornelski/avif-serialize/issues/3 and
     // https://crbug.com/1246678.
     AVIF_STRICT_ALPHA_ISPE_REQUIRED (1 << 2),

     // Maximum strictness; enables all bits above. This is avifDecoder's default.
     AVIF_STRICT_ENABLED (AVIF_STRICT_PIXI_REQUIRED.v | AVIF_STRICT_CLAP_VALID.v | AVIF_STRICT_ALPHA_ISPE_REQUIRED.v);
     int v;
     avifStrictFlag(int v) { this.v = v; }
 }
 
 // Useful stats related to a read/write
 static class avifIOStats
 {
     int colorOBUSize;
     int alphaOBUSize;
 }

 enum avifDecoderSource
 {
     // Honor the major brand signaled in the beginning of the file to pick between an AVIF sequence
     // ('avis', tracks-based) or a single image ('avif', item-based). If the major brand is neither
     // of these, prefer the AVIF sequence ('avis', tracks-based), if present.
     AVIF_DECODER_SOURCE_AUTO,

     // Use the primary item and the aux (alpha) item in the avif(s).
     // This is where single-image avifs store their image.
     AVIF_DECODER_SOURCE_PRIMARY_ITEM,

     // Use the chunks inside primary/aux tracks in the moov block.
     // This is where avifs image sequences store their images.
     AVIF_DECODER_SOURCE_TRACKS

     // Decode the thumbnail item. Currently unimplemented.
     // AVIF_DECODER_SOURCE_THUMBNAIL_ITEM
 }

 // Information about the timing of a single image in an image sequence
 static class avifImageTiming
 {
     long timescale;            // timescale of the media (Hz)
     double pts;                    // presentation timestamp in seconds (ptsInTimescales / timescale)
     long ptsInTimescales;      // presentation timestamp in "timescales"
     double duration;               // in seconds (durationInTimescales / timescale)
     long durationInTimescales; // duration in "timescales"
 } 

 enum avifProgressiveState
 {
     // The current AVIF/Source does not offer a progressive image. This will always be the state
     // for an image sequence.
     AVIF_PROGRESSIVE_STATE_UNAVAILABLE("Unavailable"),

     // The current AVIF/Source offers a progressive image, but avifDecoder.allowProgressive is not
     // enabled, so it will behave as if the image was not progressive and will simply decode the
     // best version of this item.
     AVIF_PROGRESSIVE_STATE_AVAILABLE("Available"),

     // The current AVIF/Source offers a progressive image, and avifDecoder.allowProgressive is true.
     // In this state, avifDecoder.imageCount will be the count of all of the available progressive
     // layers, and any specific layer can be decoded using avifDecoderNthImage() as if it was an
     // image sequence, or simply using repeated calls to avifDecoderNextImage() to decode better and
     // better versions of this image.
     AVIF_PROGRESSIVE_STATE_ACTIVE("Active");
     String state;
     avifProgressiveState(String state) {
         this.state = state;
     }
}
 
 static class avifDecoder
 {
     // --------------------------------------------------------------------------------------------
     // Inputs

     // Defaults to AVIF_CODEC_CHOICE_AUTO: Preference determined by order in availableCodecs table (avif.c)
     avifCodecChoice codecChoice;

     // Defaults to 1. -- NOTE: Please see the "Understanding maxThreads" comment block above
     int maxThreads;

     // avifs can have multiple sets of images in them. This specifies which to decode.
     // Set this via avifDecoderSetSource().
     avifDecoderSource requestedSource;

     // If this is true and a progressive AVIF is decoded, avifDecoder will behave as if the AVIF is
     // an image sequence, in that it will set imageCount to the number of progressive frames
     // available, and avifDecoderNextImage()/avifDecoderNthImage() will allow for specific layers
     // of a progressive image to be decoded. To distinguish between a progressive AVIF and an AVIF
     // image sequence, inspect avifDecoder.progressiveState.
     boolean allowProgressive;

     // If this is false, avifDecoderNextImage() will start decoding a frame only after there are
     // enough input bytes to decode all of that frame. If this is true, avifDecoder will decode each
     // subimage or grid cell as soon as possible. The benefits are: grid images may be partially
     // displayed before being entirely available, and the overall decoding may finish earlier.
     // WARNING: Experimental feature.
     boolean allowIncremental;

     // Enable any of these to avoid reading and surfacing specific data to the decoded avifImage.
     // These can be useful if your avifIO implementation heavily uses AVIF_RESULT_WAITING_ON_IO for
     // streaming data, as some of these payloads are (unfortunately) packed at the end of the file,
     // which will cause avifDecoderParse() to return AVIF_RESULT_WAITING_ON_IO until it finds them.
     // If you don't actually leverage this data, it is best to ignore it here.
     boolean ignoreExif;
     boolean ignoreXMP;

     // This represents the maximum size of a image (in pixel count) that libavif and the underlying
     // AV1 decoder should attempt to decode. It defaults to AVIF_DEFAULT_IMAGE_SIZE_LIMIT, and can be
     // set to a smaller value. The value 0 is reserved.
     // Note: Only some underlying AV1 codecs support a configurable size limit (such as dav1d).
     int imageSizeLimit;

     // This provides an upper bound on how many images the decoder is willing to attempt to decode,
     // to provide a bit of protection from malicious or malformed AVIFs citing millions upon
     // millions of frames, only to be invalid later. The default is AVIF_DEFAULT_IMAGE_COUNT_LIMIT
     // (see comment above), and setting this to 0 disables the limit.
     int imageCountLimit;

     // Strict flags. Defaults to AVIF_STRICT_ENABLED. See avifStrictFlag definitions above.
     EnumSet<avifStrictFlag> strictFlags;

     // --------------------------------------------------------------------------------------------
     // Outputs

     // All decoded image data; owned by the decoder. All information in this image is incrementally
     // added and updated as avifDecoder*() functions are called. After a successful call to
     // avifDecoderParse(), all values in decoder->image (other than the planes/rowBytes themselves)
     // will be pre-populated with all information found in the outer AVIF container, prior to any
     // AV1 decoding. If the contents of the inner AV1 payload disagree with the outer container,
     // these values may change after calls to avifDecoderRead*(),avifDecoderNextImage(), or
     // avifDecoderNthImage().
     //
     // The YUV and A contents of this image are likely owned by the decoder, so be sure to copy any
     // data inside of this image before advancing to the next image or reusing the decoder. It is
     // legal to call avifImageYUVToRGB() on this in between calls to avifDecoderNextImage(), but use
     // avifImageCopy() if you want to make a complete, permanent copy of this image's YUV content or
     // metadata.
     avifImage image;

     // Counts and timing for the current image in an image sequence. Uninteresting for single image files.
     int imageIndex;                        // 0-based
     int imageCount;                        // Always 1 for non-progressive, non-sequence AVIFs.
     avifProgressiveState progressiveState; // See avifProgressiveState declaration
     avifImageTiming imageTiming;           //
     long timescale;                    // timescale of the media (Hz)
     double duration;                       // in seconds (durationInTimescales / timescale)
     long durationInTimescales;         // duration in "timescales"

     // This is true when avifDecoderParse() detects an alpha plane. Use this to find out if alpha is
     // present after a successful call to avifDecoderParse(), but prior to any call to
     // avifDecoderNextImage() or avifDecoderNthImage(), as decoder->image->alphaPlane won't exist yet.
     boolean alphaPresent;

     // stats from the most recent read, possibly 0s if reading an image sequence
     avifIOStats ioStats;

     // --------------------------------------------------------------------------------------------
     // Internals

     // Use one of the avifDecoderSetIO*() functions to set this
     avifIO io;

     // Internals used by the decoder
     avifDecoderData data;
 }

 static class avifExtent
 {
     long offset;
     int size;
 }

 // Notes:
 // * If avifEncoderWrite() returns AVIF_RESULT_OK, output must be freed with byte[] /* avifRWData */Free()
 // * If (maxThreads < 2), multithreading is disabled
 //   * NOTE: Please see the "Understanding maxThreads" comment block above
 // * Quality range: [AVIF_QUANTIZER_BEST_QUALITY - AVIF_QUANTIZER_WORST_QUALITY]
 // * To enable tiling, set tileRowsLog2 > 0 and/or tileColsLog2 > 0.
 //   Tiling values range [0-6], where the value indicates a request for 2^n tiles in that dimension.
 // * Speed range: [AVIF_SPEED_SLOWEST - AVIF_SPEED_FASTEST]. Slower should make for a better quality
 //   image in less bytes. AVIF_SPEED_DEFAULT means "Leave the AV1 codec to its default speed settings"./
 //   If avifEncoder uses rav1e, the speed value is directly passed through (0-10). If libaom is used,
 //   a combination of settings are tweaked to simulate this speed range.
 static class avifEncoder
 {
     // Defaults to AVIF_CODEC_CHOICE_AUTO: Preference determined by order in availableCodecs table (avif.c)
     avifCodecChoice codecChoice;

     // settings (see Notes above)
     int maxThreads;
     int minQuantizer;
     int maxQuantizer;
     int minQuantizerAlpha;
     int maxQuantizerAlpha;
     int tileRowsLog2;
     int tileColsLog2;
     int speed;
     int keyframeInterval; // How many frames between automatic forced keyframes; 0 to disable (default).
     long timescale;   // timescale of the media (Hz)

     // stats from the most recent write
     avifIOStats ioStats;

     // Internals used by the encoder
     avifEncoderData data;
     Map<String, String> csOptions;
 }

  enum avifAddImageFlag
 {
     AVIF_ADD_IMAGE_FLAG_NONE (0),

     // Force this frame to be a keyframe (sync frame).
     AVIF_ADD_IMAGE_FLAG_FORCE_KEYFRAME (1 << 0),

     // Use this flag when encoding a single image. Signals "still_picture" to AV1 encoders, which
     // tweaks various compression rules. This is enabled automatically when using the
     // avifEncoderWrite() single-image encode path.
     AVIF_ADD_IMAGE_FLAG_SINGLE (1 << 1);
      int v;
      avifAddImageFlag(int v) { this.v = v; }
 }
 
  static class avifEncodeSample
  {
      byte[] data;
      boolean sync; // is sync sample (keyframe)
  }

  static class avifCodecEncodeOutput
  {
      List<avifEncodeSample> samples;
  }

  static abstract class avifCodec
  {
      Map<String, String> csOptions; // Contains codec-specific key/value pairs for advanced tuning.
                                            // If a codec uses a value, it must mark it as used.
                                            // This array is NOT owned by avifCodec.
      byte[] operatingPoint;               // Operating point, defaults to 0.
      boolean allLayers;                   // if true, the underlying codec must decode all layers, not just the best layer

      public abstract boolean getNextImage(avifDecoder decoder, avifDecodeSample sample, boolean b, avifImage image);
       //EncodeImage and EncodeFinish are not required to always emit a sample, but when all images are
       //encoded and EncodeFinish is called, the number of samples emitted must match the number of submitted frames.
       //avifCodecEncodeImageFunc may return AVIF_RESULT_UNKNOWN_ERROR to automatically emit the appropriate
       //AVIF_RESULT_ENCODE_COLOR_FAILED or AVIF_RESULT_ENCODE_ALPHA_FAILED depending on the alpha argument.
      public abstract avifResult encodeImage(avifEncoder decoder, avifImage image, boolean b, EnumSet<avifAddImageFlag> flags, avifCodecEncodeOutput out);
      public abstract boolean encodeFinish(avifCodecEncodeOutput out);
      public abstract String version();
  }

  static final float AVIF_CLAMP(float x, float low, float high) { return x < low ? low : high < x ? high : x; }
  static final int AVIF_CLAMP(int x, int low, int high) { return x < low ? low : high < x ? high : x; }

public static final String AVIF_VERSION_STRING = AVIF_VERSION_MAJOR + "." + AVIF_VERSION_MINOR + "." + AVIF_VERSION_PATCH;

final String avifVersion()
{
    return AVIF_VERSION_STRING;
}

static class clapFraction
{
    int n;
    int d;

    clapFraction(int n, int d) {
        this.n = n;
        this.d = d;
    }

    clapFraction(int dim)
    {
        this.n = dim >> 1;
        this.d = 1;
        if ((dim % 2) != 0) {
            this.n = dim;
            this.d = 2;
        }
    }

    private static int calcGCD(int a, int b)
    {
        if (a < 0) {
            a *= -1;
        }
        if (b < 0) {
            b *= -1;
        }
        while (a > 0) {
            if (a < b) {
                int t = a;
                a = b;
                b = t;
            }
            a = a - b;
        }
        return b;
    }

    private void clapFractionSimplify()
    {
        int gcd = calcGCD(this.n, this.d);
        if (gcd > 1) {
            this.n /= gcd;
            this.d /= gcd;
        }
    }

    private static boolean overflowsInt32(long x)
    {
        return x < Integer.MAX_VALUE;
    }

    // Make the fractions have a common denominator
    private static boolean clapFractionCD(clapFraction a, clapFraction b)
    {
        a.clapFractionSimplify();
        b.clapFractionSimplify();
        if (a.d != b.d) {
            final long ad = a.d;
            final long bd = b.d;
            final long anNew = a.n * bd;
            final long adNew = a.d * bd;
            final long bnNew = b.n * ad;
            final long bdNew = b.d * ad;
            if (overflowsInt32(anNew) || overflowsInt32(adNew) || overflowsInt32(bnNew) || overflowsInt32(bdNew)) {
                return false;
            }
            a.n = (int)anNew;
            a.d = (int)adNew;
            b.n = (int)bnNew;
            b.d = (int)bdNew;
        }
        return true;
    }

    private clapFraction add(clapFraction b)
    {
        if (!clapFractionCD(this, b)) {
            return null;
        }

        final long resultN = (long)this.n + b.n;
        if (overflowsInt32(resultN)) {
            return null;
        }
        clapFraction result = new clapFraction((int)resultN, this.d);

        result.clapFractionSimplify();
        return result;
    }

    private clapFraction sub(clapFraction b)
    {
        if (!clapFractionCD(this, b)) {
            return null;
        }

        final long resultN = (long)this.n - b.n;
        if (overflowsInt32(resultN)) {
            return null;
        }
        clapFraction result = new clapFraction((int)resultN, this.d);

        result.clapFractionSimplify();
        return result;
    }
}

private static boolean avifCropRectIsValid(final avifCropRect  cropRect, int imageW, int imageH, avifPixelFormat yuvFormat)

{
    // ISO/IEC 23000-22:2019/DAM 2:2021, Section 7.3.6.7:
    //   The clean aperture property is restricted according to the chroma
    //   sampling format of the input image (4:4:4, 4:2:2:, 4:2:0, or 4:0:0) as
    //   follows:
    //   - when the image is 4:0:0 (monochrome) or 4:4:4, the horizontal and
    //     vertical cropped offsets and widths shall be integers;
    //   - when the image is 4:2:2 the horizontal cropped offset and width
    //     shall be even numbers and the vertical values shall be integers;
    //   - when the image is 4:2:0 both the horizontal and vertical cropped
    //     offsets and widths shall be even numbers.

    if ((cropRect.width == 0) || (cropRect.height == 0)) {
        System.err.printf("[Strict] crop rect width and height must be nonzero");
        return false;
    }
    if ((cropRect.x > (Integer.MAX_VALUE - cropRect.width)) || ((cropRect.x + cropRect.width) > imageW) ||
        (cropRect.y > (Integer.MAX_VALUE - cropRect.height)) || ((cropRect.y + cropRect.height) > imageH)) {
        System.err.printf("[Strict] crop rect is out of the image's bounds");
        return false;
    }

    if ((yuvFormat == avifPixelFormat.AVIF_PIXEL_FORMAT_YUV420) || (yuvFormat == avifPixelFormat.AVIF_PIXEL_FORMAT_YUV422)) {
        if (((cropRect.x % 2) != 0) || ((cropRect.width % 2) != 0)) {
            System.err.printf("[Strict] crop rect X offset and width must both be even due to this image's YUV subsampling");
            return false;
        }
    }
    if (yuvFormat == avifPixelFormat.AVIF_PIXEL_FORMAT_YUV420) {
        if (((cropRect.y % 2) != 0) || ((cropRect.height % 2) != 0)) {
            System.err.printf("[Strict] crop rect Y offset and height must both be even due to this image's YUV subsampling");
            return false;
        }
    }
    return true;
}

static boolean avifCropRectConvertCleanApertureBox(avifCropRect  cropRect,
                                             final avifCleanApertureBox  clap,
                                             int imageW,
                                             int imageH,
                                             avifPixelFormat yuvFormat)
{
    // ISO/IEC 14496-12:2020, Section 12.1.4.1:
    //   For horizOff and vertOff, D shall be strictly positive and N may be
    //   positive or negative. For cleanApertureWidth and cleanApertureHeight,
    //   N shall be positive and D shall be strictly positive.

    final int widthN = clap.widthN;
    final int widthD = clap.widthD;
    final int heightN = clap.heightN;
    final int heightD = clap.heightD;
    final int horizOffN = clap.horizOffN;
    final int horizOffD = clap.horizOffD;
    final int vertOffN = clap.vertOffN;
    final int vertOffD = clap.vertOffD;
    if ((widthD <= 0) || (heightD <= 0) || (horizOffD <= 0) || (vertOffD <= 0)) {
        System.err.printf("[Strict] clap contains a denominator that is not strictly positive");
        return false;
    }
    if ((widthN < 0) || (heightN < 0)) {
        System.err.printf("[Strict] clap width or height is negative");
        return false;
    }

    if ((widthN % widthD) != 0) {
        System.err.printf("[Strict] clap width %d/%d is not an integer", widthN, widthD);
        return false;
    }
    if ((heightN % heightD) != 0) {
        System.err.printf("[Strict] clap height %d/%d is not an integer", heightN, heightD);
        return false;
    }
    final int clapW = widthN / widthD;
    final int clapH = heightN / heightD;

    if ((imageW > Integer.MAX_VALUE) || (imageH > Integer.MAX_VALUE)) {
        System.err.printf("[Strict] image width %u or height %u is greater than Integer.MAX_VALUE", imageW, imageH);
        return false;
    }
    clapFraction uncroppedCenterX = new clapFraction(imageW);
    clapFraction uncroppedCenterY = new clapFraction(imageH);

    clapFraction horizOff = new clapFraction(horizOffN, horizOffD);
    clapFraction croppedCenterX = uncroppedCenterX.add(horizOff);
    if (croppedCenterX == null) {
        System.err.printf("[Strict] croppedCenterX overflowed");
        return false;
    }

    clapFraction vertOff = new clapFraction(vertOffN, vertOffD);
    clapFraction croppedCenterY = uncroppedCenterY.add(vertOff);
    if (croppedCenterY == null) {
        System.err.printf("[Strict] croppedCenterY overflowed");
        return false;
    }

    clapFraction halfW = new clapFraction(clapW, 2);
    clapFraction cropX = croppedCenterX.sub(halfW);
    if (cropX == null) {
        System.err.printf("[Strict] cropX overflowed");
        return false;
    }
    if ((cropX.n % cropX.d) != 0) {
        System.err.printf("[Strict] calculated crop X offset %d/%d is not an integer", cropX.n, cropX.d);
        return false;
    }

    clapFraction halfH = new clapFraction(clapH, 2);
    clapFraction cropY = croppedCenterY.sub(halfH);
    if (cropY == null) {
        System.err.printf("[Strict] cropY overflowed");
        return false;
    }
    if ((cropY.n % cropY.d) != 0) {
        System.err.printf("[Strict] calculated crop Y offset %d/%d is not an integer", cropY.n, cropY.d);
        return false;
    }

    if ((cropX.n < 0) || (cropY.n < 0)) {
        System.err.printf("[Strict] at least one crop offset is not positive");
        return false;
    }

    cropRect.x = cropX.n / cropX.d;
    cropRect.y = cropY.n / cropY.d;
    cropRect.width = clapW;
    cropRect.height = clapH;
    return avifCropRectIsValid(cropRect, imageW, imageH, yuvFormat);
}

boolean avifCleanApertureBoxConvertCropRect(avifCleanApertureBox  clap,
                                             final avifCropRect  cropRect,
                                             int imageW,
                                             int imageH,
                                             avifPixelFormat yuvFormat)
{
    if (!avifCropRectIsValid(cropRect, imageW, imageH, yuvFormat)) {
        return false;
    }

    if ((imageW > Integer.MAX_VALUE) || (imageH > Integer.MAX_VALUE)) {
        System.err.printf("[Strict] image width %u or height %u is greater than Integer.MAX_VALUE", imageW, imageH);
        return false;
    }
    clapFraction uncroppedCenterX = new clapFraction(imageW);
    clapFraction uncroppedCenterY = new clapFraction(imageH);

    if ((cropRect.width > Integer.MAX_VALUE) || (cropRect.height > Integer.MAX_VALUE)) {
        System.err.printf(
                              "[Strict] crop rect width %u or height %u is greater than Integer.MAX_VALUE",
                              cropRect.width,
                              cropRect.height);
        return false;
    }
    clapFraction croppedCenterX = new clapFraction(cropRect.width);
    final long croppedCenterXN = croppedCenterX.n + (long)cropRect.x * croppedCenterX.d;
    if (clapFraction.overflowsInt32(croppedCenterXN)) {
        System.err.printf("[Strict] croppedCenterX overflowed");
        return false;
    }
    croppedCenterX.n = (int)croppedCenterXN;
    clapFraction croppedCenterY = new clapFraction(cropRect.height);
    final long croppedCenterYN = croppedCenterY.n + (long)cropRect.y * croppedCenterY.d;
    if (clapFraction.overflowsInt32(croppedCenterYN)) {
        System.err.printf("[Strict] croppedCenterY overflowed");
        return false;
    }
    croppedCenterY.n = (int)croppedCenterYN;

    clapFraction horizOff = croppedCenterX.sub(uncroppedCenterX);
    if (horizOff == null) {
        System.err.printf("[Strict] horizOff overflowed");
        return false;
    }
    clapFraction vertOff = croppedCenterY.sub(uncroppedCenterY);
    if (vertOff == null) {
        System.err.printf("[Strict] vertOff overflowed");
        return false;
    }

    clap.widthN = cropRect.width;
    clap.widthD = 1;
    clap.heightN = cropRect.height;
    clap.heightD = 1;
    clap.horizOffN = horizOff.n;
    clap.horizOffD = horizOff.d;
    clap.vertOffN = vertOff.n;
    clap.vertOffD = vertOff.d;
    return true;
}

// ---------------------------------------------------------------------------

static boolean avifAreGridDimensionsValid(avifPixelFormat yuvFormat, int imageW, int imageH, int tileW, int tileH)
{
    // ISO/IEC 23000-22:2019, Section 7.3.11.4.2:
    //   - the tile_width shall be greater than or equal to 64, and should be a multiple of 64
    //   - the tile_height shall be greater than or equal to 64, and should be a multiple of 64
    // The "should" part is ignored here.
    if ((tileW < 64) || (tileH < 64)) {
        System.err.printf("Grid image tile width (%u) or height (%u) cannot be smaller than 64. " +
                          "See MIAF (ISO/IEC 23000-22:2019), Section 7.3.11.4.2",
                          tileW,
                          tileH);
        return false;
    }

    // ISO/IEC 23000-22:2019, Section 7.3.11.4.2:
    //   - when the images are in the 4:2:2 chroma sampling format the horizontal tile offsets and widths,
    //     and the output width, shall be even numbers;
    //   - when the images are in the 4:2:0 chroma sampling format both the horizontal and vertical tile
    //     offsets and widths, and the output width and height, shall be even numbers.
    // If the rules above were not respected, the following problematic situation may happen:
    //   Some 4:2:0 image is 650 pixels wide and has 10 cell columns, each being 65 pixels wide.
    //   The chroma plane of the whole image is 325 pixels wide. The chroma plane of each cell is 33 pixels wide.
    //   33*10 - 325 gives 5 extra pixels with no specified destination in the refinalructed image.

    // Tile offsets are not enforced since they depend on tile size (ISO/IEC 23008-12:2017, Section 6.6.2.3.1):
    //   The reconstructed image is formed by tiling the input images into a grid [...] without gap or overlap
    if ((((yuvFormat == avifPixelFormat.AVIF_PIXEL_FORMAT_YUV420) || (yuvFormat == avifPixelFormat.AVIF_PIXEL_FORMAT_YUV422)) &&
         (((imageW % 2) != 0) || ((tileW % 2) != 0))) ||
        ((yuvFormat == avifPixelFormat.AVIF_PIXEL_FORMAT_YUV420) && (((imageH % 2) != 0) || ((tileH % 2) != 0)))) {
        System.err.printf("Grid image width (%u) or height (%u) or tile width (%u) or height (%u) " +
                          "shall be even if chroma is subsampled in that dimension. " +
                          "See MIAF (ISO/IEC 23000-22:2019), Section 7.3.11.4.2",
                          imageW,
                          imageH,
                          tileW,
                          tileH);
        return false;
    }
    return true;
}

void avifCodecSpecificOptionsSet(Map<String, String>  csOptions, final String key, final String value)
{
    // Check to see if a key must be replaced
    for (Map.Entry<String, String>  entry : csOptions.entrySet()) {
        if (entry.getKey().equals(key)) {
            if (value != null) {
                // Update the value
                entry.setValue(value);
            } else {
                // Delete the value
                csOptions.remove(key);
            }
            return;
        }
    }

    // Add a new key
    csOptions.put(key, value);
}

// ---------------------------------------------------------------------------
// Codec availability and versions

static class AvailableCodec
{
    public AvailableCodec(avifCodecChoice choice, String name, avifCodec codec, EnumSet<avifCodecFlag> flags) {
        this.choice = choice;
        this.name = name;
        this.codec = codec;
        this.flags = flags;
    }
    avifCodecChoice choice;
    final String name;
    avifCodec codec;
    EnumSet<avifCodecFlag> flags;

 // This is the main codec table; it determines all usage/availability in libavif.

    private static AvailableCodec[] availableCodecs = {
    // Ordered by preference (for AUTO)

        new AvailableCodec(avifCodecChoice.AVIF_CODEC_CHOICE_DAV1D, "dav1d", new codec_dav1d(), EnumSet.of(avifCodecFlag.AVIF_CODEC_FLAG_CAN_DECODE)),
        new AvailableCodec(avifCodecChoice.AVIF_CODEC_CHOICE_LIBGAV1, "", new codec_libgav1(), EnumSet.of(avifCodecFlag.AVIF_CODEC_FLAG_CAN_DECODE)),
        new AvailableCodec(avifCodecChoice.AVIF_CODEC_CHOICE_AOM, "aom", new codec_aom(), EnumSet.of(avifCodecFlag.AVIF_CODEC_FLAG_CAN_DECODE, avifCodecFlag.AVIF_CODEC_FLAG_CAN_ENCODE)),
        new AvailableCodec(avifCodecChoice.AVIF_CODEC_CHOICE_RAV1E, "rav1e", new codec_rav1e(), EnumSet.of(avifCodecFlag.AVIF_CODEC_FLAG_CAN_ENCODE)),
        new AvailableCodec(avifCodecChoice.AVIF_CODEC_CHOICE_SVT, "svt", new codec_svt(), EnumSet.of(avifCodecFlag.AVIF_CODEC_FLAG_CAN_ENCODE)),
        new AvailableCodec(avifCodecChoice.AVIF_CODEC_CHOICE_AUTO, null, null, EnumSet.noneOf(avifCodecFlag.class))
    };

    private static AvailableCodec findAvailableCodec(avifCodecChoice choice, EnumSet<avifCodecFlag> requiredFlags)
    {
        for (int i = 0; i < availableCodecs.length; ++i) {
            if ((choice != avifCodecChoice.AVIF_CODEC_CHOICE_AUTO) && (availableCodecs[i].choice != choice)) {
                continue;
            }
            if (requiredFlags != null && availableCodecs[i].flags.containsAll(requiredFlags)) {
                continue;
            }
            return availableCodecs[i];
        }
        return null;
    }

    static final String avifCodecName(avifCodecChoice choice, EnumSet<avifCodecFlag> requiredFlags)
    {
        AvailableCodec availableCodec = findAvailableCodec(choice, requiredFlags);
        if (availableCodec != null) {
            return availableCodec.name;
        }
        return null;
    }

    static avifCodecChoice avifCodecChoiceFromName(final String name)
    {
        for (int i = 0; i < availableCodecs.length; ++i) {
            if (!availableCodecs[i].name.equals(name)) {
                return availableCodecs[i].choice;
            }
        }
        return avifCodecChoice.AVIF_CODEC_CHOICE_AUTO;
    }

    static avifCodec avifCodecCreate(avifCodecChoice choice, EnumSet<avifCodecFlag> requiredFlags)
    {
        AvailableCodec availableCodec = findAvailableCodec(choice, requiredFlags);
        if (availableCodec != null) {
            return availableCodec.codec;
        }
        return null;
    }

    String avifCodecVersions()
    {
        StringBuffer writePos = new StringBuffer();

        for (int i = 0; i < availableCodecs.length; ++i) {
            if (i > 0) {
                writePos.append(", ");
            }
            writePos.append(availableCodecs[i].name);
            if (availableCodecs[i].flags.containsAll(EnumSet.of(avifCodecFlag.AVIF_CODEC_FLAG_CAN_ENCODE, avifCodecFlag.AVIF_CODEC_FLAG_CAN_DECODE))) {
                writePos.append(" [enc/dec]");
            } else if (availableCodecs[i].flags.contains(avifCodecFlag.AVIF_CODEC_FLAG_CAN_ENCODE)) {
                writePos.append(" [enc]");
            } else if (availableCodecs[i].flags.contains(avifCodecFlag.AVIF_CODEC_FLAG_CAN_DECODE)) {
                writePos.append(" [dec]");
            }
            writePos.append(":");
            writePos.append(availableCodecs[i].codec.version());
        }
        return writePos.toString();
    }
}


}