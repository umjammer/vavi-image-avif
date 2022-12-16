package vavi.awt.image.jna.avif;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import java.util.Arrays;
import java.util.List;
import vavi.awt.image.jna.avif.AvifLibrary.avifCodecSpecificOptions;
import vavi.awt.image.jna.avif.AvifLibrary.avifEncoderData;

/**
 * <i>native declaration : avif/avif.h</i><br>
 * This file was autogenerated by <a href="http://jnaerator.googlecode.com/">JNAerator</a>,<br>
 * a tool written by <a href="http://ochafik.com/">Olivier Chafik</a> that <a href="http://code.google.com/p/jnaerator/wiki/CreditsAndLicense">uses a few opensource projects.</a>.<br>
 * For help, please visit <a href="http://nativelibs4java.googlecode.com/">NativeLibs4Java</a> , <a href="http://rococoa.dev.java.net/">Rococoa</a>, or <a href="http://jna.dev.java.net/">JNA</a>.
 */
public class avifEncoder extends Structure {
	/**
	 * @see AvifLibrary.avifCodecChoice
	 * C type : avifCodecChoice
	 */
	public int codecChoice;
	public int maxThreads;
	public int speed;
	/** How many frames between automatic forced keyframes; 0 to disable (default). */
	public int keyframeInterval;
	/** timescale of the media (Hz) */
	public long timescale;
	// changeable encoder settings
	public int minQuantizer;
	public int maxQuantizer;
	public int minQuantizerAlpha;
	public int maxQuantizerAlpha;
	public int tileRowsLog2;
	public int tileColsLog2;
	public int autoTiling;
	/** C type : avifIOStats */
	public avifIOStats ioStats;
	/** C type : avifDiagnostics */
	public avifDiagnostics diag;
	/** C type : avifEncoderData* */
	public avifEncoderData data;
	/** C type : avifCodecSpecificOptions* */
	public avifCodecSpecificOptions csOptions;
	public avifEncoder() {
		super();
	}
	protected List<String> getFieldOrder() {
		return Arrays.asList("codecChoice", "maxThreads", "speed", "keyframeInterval", "timescale", "minQuantizer", "maxQuantizer", "minQuantizerAlpha", "maxQuantizerAlpha", "tileRowsLog2", "tileColsLog2", "autoTiling", "ioStats", "diag", "data", "csOptions");
	}
	public avifEncoder(Pointer peer) {
		super(peer);
	}
	public static class ByReference extends avifEncoder implements Structure.ByReference {
	}
	public static class ByValue extends avifEncoder implements Structure.ByValue {
	}
}
