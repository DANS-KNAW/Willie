package com.noterik.springfield.willie.util;

import org.apache.log4j.Logger;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;

public class FsEncoding {
	private static final Logger log = Logger.getLogger(FsEncoding.class);

	public FsEncoding() {
		//constructor
	}
	
	public static String encode(String input) {
		//convert UTF-8 to ISO-8859-1 as internal fs decoding expects this
		byte[] charbytes = input.getBytes();
		String originalInput = input;
		CharsetEncoder encoder = Charset.forName("ISO-8859-1").newEncoder();
		CharsetDecoder decoder = Charset.forName("UTF-8").newDecoder();
		try {
			CharBuffer charbuf = decoder.decode(ByteBuffer.wrap(charbytes));
			ByteBuffer bytebuf = encoder.encode(CharBuffer.wrap(charbuf));
			input = new String(bytebuf.array(), 0, bytebuf.limit(), Charset.forName("ISO-8859-1"));
		} catch (CharacterCodingException e) {
			log.debug("Error in coversion from UTF-8 to ISO-8859-1");
			try {
				input = new String(originalInput.getBytes("ISO-8859-1"));
			} catch (UnsupportedEncodingException ex) {
				log.debug("Error in second conversion from ISO-8859-1");
			}
		}
		
		String output="";
		for (int i=0;i<input.length();i++) {
			int code = input.charAt(i);
			if (code>127 && code<1000) {
				output+="\\"+code;
			} else if (code==13) {
				output+="\\013";
			} else {
				if (code==37) {
						output+="\\037";
				} else if (code==35) {
						output+="\\035";
				} else if (code==61) {
					output+="\\061";
				} else if (code==92) {
						output+="\\092";
				}  
				else {
					if (code>999) {
						String t = Integer.toHexString(code);
						if (t.length()==2) {
							output+="\\0x00"+Integer.toHexString(code);
						} else if (t.length()==3) {
						output+="\\0x0"+Integer.toHexString(code);
						} else {
							output+="\\0x"+Integer.toHexString(code);
						}
					} else {
						output+=input.charAt(i);
					}
				}
			}
		}
		return output;
	}
}
