package vavi.awt.image.jna.avif;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import java.util.Arrays;
import java.util.List;
/**
 * <i>native declaration : avif/avif.h</i><br>
 * This file was autogenerated by <a href="http://jnaerator.googlecode.com/">JNAerator</a>,<br>
 * a tool written by <a href="http://ochafik.com/">Olivier Chafik</a> that <a href="http://code.google.com/p/jnaerator/wiki/CreditsAndLicense">uses a few opensource projects.</a>.<br>
 * For help, please visit <a href="http://nativelibs4java.googlecode.com/">NativeLibs4Java</a> , <a href="http://rococoa.dev.java.net/">Rococoa</a>, or <a href="http://jna.dev.java.net/">JNA</a>.
 */
public class avifRWData extends Structure {
	/** C type : uint8_t* */
	public Pointer data;
	public int size;
	public avifRWData() {
		super();
	}
	protected List<String> getFieldOrder() {
		return Arrays.asList("data", "size");
	}
	/** @param data C type : uint8_t* */
	public avifRWData(Pointer data, int size) {
		super();
		this.data = data;
		this.size = size;
	}
	public avifRWData(Pointer peer) {
		super(peer);
	}
	public static class ByReference extends avifRWData implements Structure.ByReference {
		
	};
	public static class ByValue extends avifRWData implements Structure.ByValue {
		
	};
}
